package hu.bordasm.excelfingerprintunlock;

import android.app.Activity;
import android.app.assist.AssistStructure;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.service.autofill.Dataset;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;

public final class AutofillAuthActivity extends FragmentActivity {
    static final String ACTION_AUTHENTICATE =
            "hu.bordasm.excelfingerprintunlock.action.AUTHENTICATE";

    private AuthState state;
    private AutofillId targetId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppSecurity.secureWindow(getWindow());
        setCanceledResult();

        state = new ViewModelProvider(this).get(AuthState.class);
        if (state.terminal || !AppSecurity.isNonDebuggable(this)
                || !ACTION_AUTHENTICATE.equals(getIntent().getAction())) {
            finishCanceled();
            return;
        }

        AssistStructure structure = readAssistStructure(getIntent());
        if (!ExcelIdentity.isTrustedExcelStructure(this, structure)) {
            finishCanceled();
            return;
        }
        targetId = ExcelStructure.findSingleEligibleField(structure);
        if (targetId == null
                || SecretStore.inspect(this).state != SecretStore.State.READY
                || BiometricManager.from(this).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG)
                != BiometricManager.BIOMETRIC_SUCCESS) {
            finishCanceled();
            return;
        }

        BiometricPrompt prompt = new BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result) {
                        finishAuthenticated(result);
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        // Recoverable: the system prompt remains open.
                    }

                    @Override
                    public void onAuthenticationError(
                            int errorCode, @NonNull CharSequence errString) {
                        finishCanceled();
                    }
                });

        if (state.session == null && !state.promptStarted) {
            try {
                state.session = SecretStore.createDecryptionSession(this);
                state.promptStarted = true;
                prompt.authenticate(new BiometricPrompt.PromptInfo.Builder()
                                .setTitle(getString(R.string.biometric_unlock_title))
                                .setSubtitle(getString(R.string.biometric_unlock_subtitle))
                                .setAllowedAuthenticators(
                                        BiometricManager.Authenticators.BIOMETRIC_STRONG)
                                .setNegativeButtonText(getString(R.string.cancel))
                                .build(),
                        new BiometricPrompt.CryptoObject(state.session.cipher));
            } catch (GeneralSecurityException | java.io.IOException error) {
                finishCanceled();
            }
        }
    }

    private void finishAuthenticated(BiometricPrompt.AuthenticationResult result) {
        if (state == null || state.terminal || state.session == null || targetId == null) {
            finishCanceled();
            return;
        }

        byte[] plaintext = null;
        char[] passwordChars = null;
        try {
            BiometricPrompt.CryptoObject cryptoObject = result.getCryptoObject();
            Cipher authenticatedCipher = cryptoObject == null ? null : cryptoObject.getCipher();
            if (authenticatedCipher == null) {
                throw new GeneralSecurityException("Missing authenticated cipher");
            }
            plaintext = SecretStore.completeDecryption(state.session, authenticatedCipher);
            state.session = null;
            passwordChars = Utf8Codec.decode(plaintext);
            String password = new String(passwordChars);

            RemoteViews presentation = new RemoteViews(getPackageName(),
                    R.layout.autofill_item);
            presentation.setTextViewText(R.id.autofill_text,
                    getString(R.string.autofill_locked_label));
            Dataset unlocked = new Dataset.Builder(presentation)
                    .setValue(targetId, AutofillValue.forText(password))
                    .setId("xlsx_password_unlocked_v1")
                    .build();

            Intent reply = new Intent();
            reply.putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, unlocked);
            reply.putExtra(
                    AutofillManager.EXTRA_AUTHENTICATION_RESULT_EPHEMERAL_DATASET,
                    true);
            state.terminal = true;
            setResult(Activity.RESULT_OK, reply);
            finish();
        } catch (GeneralSecurityException | java.nio.charset.CharacterCodingException error) {
            finishCanceled();
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
            if (passwordChars != null) {
                Arrays.fill(passwordChars, '\0');
            }
        }
    }

    private void finishCanceled() {
        if (state != null) {
            state.terminal = true;
            state.clearSession();
        }
        setCanceledResult();
        finish();
    }

    private void setCanceledResult() {
        Intent canceled = new Intent();
        canceled.putExtras(Bundle.EMPTY);
        setResult(Activity.RESULT_CANCELED, canceled);
    }

    @SuppressWarnings("deprecation")
    private static AssistStructure readAssistStructure(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(
                    AutofillManager.EXTRA_ASSIST_STRUCTURE, AssistStructure.class);
        }
        return intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE);
    }

    @Override
    protected void onDestroy() {
        if (state != null && isFinishing() && !isChangingConfigurations()) {
            state.clearSession();
        }
        super.onDestroy();
    }

    public static final class AuthState extends ViewModel {
        SecretStore.DecryptionSession session;
        boolean promptStarted;
        boolean terminal;

        void clearSession() {
            if (session != null) {
                session.wipe();
                session = null;
            }
        }

        @Override
        protected void onCleared() {
            clearSession();
        }
    }
}
