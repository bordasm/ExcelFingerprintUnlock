package hu.bordasm.excelfingerprintunlock;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;

final class SecretStore {
    private static final Object LOCK = new Object();
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "excel_xlsx_password_key_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String FILE_NAME = "excel_xlsx_password_v1.bin";
    private static final int MAGIC = 0x58465055; // XFPU
    private static final int FORMAT_VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BYTES = 16;
    private static final int MAX_PLAINTEXT_BYTES = 4096;
    private static final int MAX_FILE_BYTES = 8192;
    private static final byte[] AAD = (
            "hu.bordasm.excelfingerprintunlock|xlsx-open-password|blob-v1|"
                    + KEY_ALIAS).getBytes(StandardCharsets.US_ASCII);

    enum State {
        EMPTY,
        READY,
        INVALID
    }

    static final class Inspection {
        final State state;
        final String hardware;
        final String safeCode;

        Inspection(State state, String hardware) {
            this(state, hardware, "");
        }

        Inspection(State state, String hardware, String safeCode) {
            this.state = state;
            this.hardware = hardware;
            this.safeCode = safeCode;
        }
    }

    /** A stable diagnostic code with no key material, password data or provider message. */
    static final class SetupException extends GeneralSecurityException {
        private static final long serialVersionUID = 1L;
        private final String safeCode;

        SetupException(String safeCode) {
            super(safeCode);
            this.safeCode = safeCode;
        }

        SetupException(String safeCode, Throwable cause) {
            super(safeCode, cause);
            this.safeCode = safeCode;
        }

        String safeCode() {
            return safeCode;
        }
    }

    static final class EncryptionSession {
        final Cipher cipher;
        private boolean consumed;

        EncryptionSession(Cipher cipher) {
            this.cipher = cipher;
        }
    }

    static final class DecryptionSession {
        final Cipher cipher;
        private byte[] ciphertext;
        private boolean consumed;

        DecryptionSession(Cipher cipher, byte[] ciphertext) {
            this.cipher = cipher;
            this.ciphertext = ciphertext;
        }

        void wipe() {
            if (ciphertext != null) {
                Arrays.fill(ciphertext, (byte) 0);
                ciphertext = null;
            }
        }
    }

    private static final class Record {
        final byte[] iv;
        final byte[] ciphertext;

        Record(byte[] iv, byte[] ciphertext) {
            this.iv = iv;
            this.ciphertext = ciphertext;
        }

        void wipe() {
            Arrays.fill(iv, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
        }
    }

    private SecretStore() {
    }

    static Inspection inspect(Context context) {
        synchronized (LOCK) {
            try {
                KeyStore keyStore = loadKeyStore();
                boolean keyExists = keyStore.containsAlias(KEY_ALIAS);
                Record record;
                try {
                    record = readRecord(context);
                } catch (FileNotFoundException missingRecord) {
                    if (!keyExists) {
                        return new Inspection(State.EMPTY, "nincs kulcs");
                    }
                    // Az első biometrikus mentés alatt a kulcs már létezik, a blob még
                    // nem. Az inspect ezért nem töröl és nem indít Keystore-műveletet.
                    // Egy invalid orphan kulcsot createEncryptionSession regenerál.
                    SecretKey key = getRequiredKey(keyStore);
                    String hardware = verifyKeyPolicy(key);
                    return new Inspection(State.EMPTY, hardware);
                } catch (IOException invalidRecord) {
                    return new Inspection(State.INVALID,
                            "új jelszóbeállítás szükséges", "E-ST-01");
                }

                try {
                    if (!keyExists) {
                        return new Inspection(State.INVALID, "a kulcs hiányzik");
                    }
                    SecretKey key = getRequiredKey(keyStore);
                    String hardware = verifyKeyPolicy(key);
                    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                    cipher.init(Cipher.DECRYPT_MODE, key,
                            new GCMParameterSpec(128, record.iv));
                    return new Inspection(State.READY, hardware);
                } finally {
                    record.wipe();
                }
            } catch (SetupException error) {
                return new Inspection(State.INVALID,
                        "új jelszóbeállítás szükséges", error.safeCode());
            } catch (Exception error) {
                return new Inspection(State.INVALID,
                        "új jelszóbeállítás szükséges", "E-IN-01");
            }
        }
    }

    static EncryptionSession createEncryptionSession(Context context)
            throws GeneralSecurityException, IOException {
        Inspection inspection = inspect(context);
        State existingState = inspection.state;
        if (existingState == State.INVALID) {
            throw new SetupException(inspection.safeCode.isEmpty()
                    ? "E-ST-01" : inspection.safeCode);
        }
        synchronized (LOCK) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    SecretKey key = getOrCreateKey();
                    verifyKeyPolicy(key);
                    Cipher cipher;
                    try {
                        cipher = Cipher.getInstance(TRANSFORMATION);
                    } catch (GeneralSecurityException | RuntimeException error) {
                        throw new SetupException("E-CF-01", error);
                    }
                    try {
                        cipher.init(Cipher.ENCRYPT_MODE, key);
                    } catch (KeyPermanentlyInvalidatedException invalidated) {
                        throw invalidated;
                    } catch (GeneralSecurityException | RuntimeException error) {
                        throw new SetupException("E-CI-01", error);
                    }
                    byte[] iv = cipher.getIV();
                    if (iv == null || iv.length != IV_BYTES) {
                        throw new SetupException("E-IV-01");
                    }
                    Arrays.fill(iv, (byte) 0);
                    return new EncryptionSession(cipher);
                } catch (KeyPermanentlyInvalidatedException invalidated) {
                    if (existingState != State.EMPTY || attempt != 0) {
                        throw new SetupException("E-KX-01", invalidated);
                    }
                    // Blob nélküli, invalid orphan kulcs: nincs elveszíthető titok.
                    KeyStore keyStore = loadKeyStore();
                    if (keyStore.containsAlias(KEY_ALIAS)) {
                        keyStore.deleteEntry(KEY_ALIAS);
                    }
                }
            }
            throw new SetupException("E-KX-01");
        }
    }

    static void completeEncryption(Context context, EncryptionSession session,
                                   Cipher authenticatedCipher, byte[] plaintext)
            throws GeneralSecurityException, IOException {
        try {
            if (session == null || authenticatedCipher == null || plaintext == null
                    || plaintext.length == 0 || plaintext.length > MAX_PLAINTEXT_BYTES) {
                throw new GeneralSecurityException("Invalid enrollment session");
            }
            synchronized (LOCK) {
                if (session.consumed || authenticatedCipher != session.cipher) {
                    throw new GeneralSecurityException("Enrollment session mismatch");
                }
                session.consumed = true;
                byte[] encrypted = null;
                byte[] iv = null;
                try {
                    authenticatedCipher.updateAAD(AAD);
                    encrypted = authenticatedCipher.doFinal(plaintext);
                    iv = authenticatedCipher.getIV();
                    if (iv == null || iv.length != IV_BYTES
                            || encrypted.length < GCM_TAG_BYTES
                            || encrypted.length > MAX_PLAINTEXT_BYTES + GCM_TAG_BYTES) {
                        throw new GeneralSecurityException("Invalid encrypted record");
                    }
                    writeRecord(context, iv, encrypted);
                } finally {
                    if (encrypted != null) {
                        Arrays.fill(encrypted, (byte) 0);
                    }
                    if (iv != null) {
                        Arrays.fill(iv, (byte) 0);
                    }
                }
            }
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    static DecryptionSession createDecryptionSession(Context context)
            throws GeneralSecurityException, IOException {
        synchronized (LOCK) {
            Record record = readRecord(context);
            try {
                SecretKey key = getRequiredKey(loadKeyStore());
                verifyKeyPolicy(key);
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, key,
                        new GCMParameterSpec(128, record.iv));
                byte[] ciphertext = Arrays.copyOf(record.ciphertext, record.ciphertext.length);
                return new DecryptionSession(cipher, ciphertext);
            } finally {
                record.wipe();
            }
        }
    }

    static byte[] completeDecryption(DecryptionSession session, Cipher authenticatedCipher)
            throws GeneralSecurityException {
        if (session == null || authenticatedCipher == null) {
            throw new GeneralSecurityException("Invalid decryption session");
        }
        synchronized (LOCK) {
            if (session.consumed || authenticatedCipher != session.cipher
                    || session.ciphertext == null) {
                throw new GeneralSecurityException("Decryption session mismatch");
            }
            session.consumed = true;
            try {
                authenticatedCipher.updateAAD(AAD);
                byte[] plaintext = authenticatedCipher.doFinal(session.ciphertext);
                if (plaintext.length == 0 || plaintext.length > MAX_PLAINTEXT_BYTES) {
                    Arrays.fill(plaintext, (byte) 0);
                    throw new GeneralSecurityException("Invalid plaintext length");
                }
                return plaintext;
            } finally {
                session.wipe();
            }
        }
    }

    static void reset(Context context) throws GeneralSecurityException {
        synchronized (LOCK) {
            new AtomicFile(recordFile(context)).delete();
            KeyStore keyStore = loadKeyStore();
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS);
            }
        }
    }

    private static SecretKey getOrCreateKey() throws GeneralSecurityException {
        KeyStore keyStore = loadKeyStore();
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return getRequiredKey(keyStore);
        }
        KeyGenerator generator;
        try {
            generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        } catch (GeneralSecurityException | RuntimeException error) {
            throw new SetupException("E-KG-01", error);
        }
        KeyGenParameterSpec spec;
        try {
            spec = new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(
                            0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    .setInvalidatedByBiometricEnrollment(true)
                    .setUnlockedDeviceRequired(true)
                    .build();
            generator.init(spec);
        } catch (GeneralSecurityException | RuntimeException error) {
            throw new SetupException("E-KG-02", error);
        }
        SecretKey key;
        try {
            key = generator.generateKey();
        } catch (RuntimeException error) {
            throw new SetupException("E-KG-03", error);
        }
        try {
            verifyKeyPolicy(key);
            return key;
        } catch (GeneralSecurityException error) {
            deleteNewKeyAfterFailure(error);
            throw error;
        } catch (RuntimeException error) {
            SetupException wrapped = new SetupException("E-KI-02", error);
            deleteNewKeyAfterFailure(wrapped);
            throw wrapped;
        }
    }

    private static void deleteNewKeyAfterFailure(GeneralSecurityException original) {
        try {
            loadKeyStore().deleteEntry(KEY_ALIAS);
        } catch (GeneralSecurityException | RuntimeException cleanupError) {
            original.addSuppressed(cleanupError);
        }
    }

    private static KeyStore loadKeyStore() throws GeneralSecurityException {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            return keyStore;
        } catch (GeneralSecurityException | IOException | RuntimeException error) {
            throw new SetupException("E-KS-01", error);
        }
    }

    private static SecretKey getRequiredKey(KeyStore keyStore)
            throws GeneralSecurityException {
        Key key;
        try {
            key = keyStore.getKey(KEY_ALIAS, null);
        } catch (GeneralSecurityException | RuntimeException error) {
            throw new SetupException("E-KS-02", error);
        }
        if (!(key instanceof SecretKey)) {
            throw new SetupException("E-KS-03");
        }
        return (SecretKey) key;
    }

    private static String verifyKeyPolicy(SecretKey key) throws GeneralSecurityException {
        KeyInfo info;
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(
                    key.getAlgorithm(), ANDROID_KEYSTORE);
            info = (KeyInfo) factory.getKeySpec(key, KeyInfo.class);
        } catch (GeneralSecurityException | RuntimeException error) {
            throw new SetupException("E-KI-01", error);
        }
        int securityLevel = info.getSecurityLevel();
        int mismatch = 0;
        if (securityLevel != KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
                && securityLevel != KeyProperties.SECURITY_LEVEL_STRONGBOX) {
            mismatch |= 0x0001;
        }
        if (!info.isUserAuthenticationRequired()) {
            mismatch |= 0x0002;
        }
        if (!info.isUserAuthenticationRequirementEnforcedBySecureHardware()) {
            mismatch |= 0x0004;
        }
        int authenticationDuration = info.getUserAuthenticationValidityDurationSeconds();
        // Keystore2 reports auth-per-use as 0; legacy providers/documentation may report -1.
        if (authenticationDuration != 0 && authenticationDuration != -1) {
            mismatch |= 0x0008;
        }
        if (info.getUserAuthenticationType() != KeyProperties.AUTH_BIOMETRIC_STRONG) {
            mismatch |= 0x0010;
        }
        if (!info.isInvalidatedByBiometricEnrollment()) {
            mismatch |= 0x0020;
        }
        if (info.getKeySize() != 256) {
            mismatch |= 0x0040;
        }
        if (info.getOrigin() != KeyProperties.ORIGIN_GENERATED) {
            mismatch |= 0x0080;
        }
        if (info.getPurposes()
                != (KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)) {
            mismatch |= 0x0100;
        }
        if (!containsExactly(info.getBlockModes(), KeyProperties.BLOCK_MODE_GCM)) {
            mismatch |= 0x0200;
        }
        if (!containsExactly(info.getEncryptionPaddings(),
                KeyProperties.ENCRYPTION_PADDING_NONE)) {
            mismatch |= 0x0400;
        }
        if (mismatch != 0) {
            throw new SetupException(String.format(
                    Locale.ROOT, "E-KP-%04X", mismatch));
        }
        return securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
                ? "StrongBox" : "TEE";
    }

    private static boolean containsExactly(String[] values, String expected) {
        return values != null && values.length == 1 && expected.equals(values[0]);
    }

    private static File recordFile(Context context) {
        return new File(context.getNoBackupFilesDir(), FILE_NAME);
    }

    private static Record readRecord(Context context) throws IOException {
        AtomicFile atomicFile = new AtomicFile(recordFile(context));
        try (FileInputStream rawInput = atomicFile.openRead();
             DataInputStream input = new DataInputStream(
                     new BufferedInputStream(rawInput))) {
            long fileLength = rawInput.getChannel().size();
            if (fileLength <= 0 || fileLength > MAX_FILE_BYTES) {
                throw new IOException("Encrypted record size invalid");
            }
            if (input.readInt() != MAGIC || input.readUnsignedByte() != FORMAT_VERSION) {
                throw new IOException("Encrypted record format mismatch");
            }
            int ivLength = input.readUnsignedByte();
            int ciphertextLength = input.readInt();
            if (ivLength != IV_BYTES
                    || ciphertextLength < GCM_TAG_BYTES
                    || ciphertextLength > MAX_PLAINTEXT_BYTES + GCM_TAG_BYTES) {
                throw new IOException("Encrypted record length invalid");
            }
            byte[] iv = new byte[ivLength];
            byte[] ciphertext = new byte[ciphertextLength];
            input.readFully(iv);
            input.readFully(ciphertext);
            if (input.read() != -1) {
                Arrays.fill(iv, (byte) 0);
                Arrays.fill(ciphertext, (byte) 0);
                throw new IOException("Encrypted record has trailing data");
            }
            return new Record(iv, ciphertext);
        }
    }

    private static void writeRecord(Context context, byte[] iv, byte[] ciphertext)
            throws IOException {
        AtomicFile atomicFile = new AtomicFile(recordFile(context));
        FileOutputStream stream = null;
        boolean committed = false;
        try {
            stream = atomicFile.startWrite();
            DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(stream));
            output.writeInt(MAGIC);
            output.writeByte(FORMAT_VERSION);
            output.writeByte(iv.length);
            output.writeInt(ciphertext.length);
            output.write(iv);
            output.write(ciphertext);
            output.flush();
            atomicFile.finishWrite(stream);
            committed = true;
            stream = null;
        } finally {
            if (!committed && stream != null) {
                atomicFile.failWrite(stream);
            }
        }
    }
}
