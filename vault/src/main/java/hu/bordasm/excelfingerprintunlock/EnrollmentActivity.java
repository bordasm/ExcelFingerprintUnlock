package hu.bordasm.excelfingerprintunlock;

import android.graphics.Color;
import android.graphics.Insets;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import java.nio.charset.CharacterCodingException;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;

/** Collects a new password and encrypts it only after auth-per-use biometric approval. */
public final class EnrollmentActivity extends FragmentActivity {
    private static final int MAX_PASSWORD_CHARS = 2048;
    private static final int MAX_PASSWORD_BYTES = 4096;
    private static final int COLOR_HEADING = Color.rgb(20, 50, 80);
    private static final int COLOR_ERROR = Color.rgb(165, 30, 35);

    private EnrollmentState state;
    private BiometricPrompt biometricPrompt;
    private EditText passwordInput;
    private EditText confirmationInput;
    private Button saveButton;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppSecurity.secureWindow(getWindow());
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        state = new ViewModelProvider(this).get(EnrollmentState.class);
        buildUi();
        createBiometricPrompt();

        if (!AppSecurity.isNonDebuggable(this)) {
            disableForDebugBuild();
            return;
        }
        if (state.authenticationRunning) {
            setInputsEnabled(false);
            statusView.setText("Biometrikus azonosítás folyamatban…");
        }
    }

    @Override
    protected void onDestroy() {
        clearInputs();
        if (isFinishing() && state != null) {
            state.clearSecret();
        }
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setImportantForAutofill(
                View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(28));
        root.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(dp(22) + bars.left, dp(28) + bars.top,
                    dp(22) + bars.right, dp(28) + bars.bottom);
            return windowInsets;
        });

        TextView title = text("XLSX-jelszó biztonságos beállítása", 23, COLOR_HEADING);
        root.addView(title, fullWidth());

        TextView explanation = text(
                "A jelszó megadása után erős biometrikus azonosítás következik. "
                        + "Csak ezután készül el a Keystore-kulccsal védett, titkosított mentés.",
                15, Color.DKGRAY);
        root.addView(explanation, spaced());

        passwordInput = passwordField("XLSX megnyitási jelszó");
        root.addView(passwordInput, spaced());

        confirmationInput = passwordField("Jelszó még egyszer");
        confirmationInput.setImeOptions(
                EditorInfo.IME_ACTION_DONE
                        | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                        | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        root.addView(confirmationInput, spaced());

        statusView = text("", 14, COLOR_ERROR);
        statusView.setMinHeight(dp(42));
        root.addView(statusView, fullWidth());

        saveButton = button("Mentés biometrikus azonosítással",
                view -> beginEnrollment());
        root.addView(saveButton, buttonLayout());

        Button cancelButton = button("Mégse", view -> cancelAndFinish());
        root.addView(cancelButton, buttonLayout());

        TextView note = text(
                "A jelszó nem kerül a vágólapra, naplóba vagy internetre. "
                        + "A beviteli mezők tartalmát az alkalmazás a lehető leghamarabb törli a memóriából.",
                13, Color.GRAY);
        root.addView(note, spaced());

        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    private void createBiometricPrompt() {
        biometricPrompt = new BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result) {
                        completeEnrollment(result);
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        statusView.setText(
                                "Az ujjlenyomat nem egyezett. Próbálja meg újra.");
                    }

                    @Override
                    public void onAuthenticationError(
                            int errorCode, @NonNull CharSequence errString) {
                        state.clearSecret();
                        setInputsEnabled(true);
                        statusView.setText("A biometrikus azonosítás megszakadt.");
                    }
                });
    }

    private void beginEnrollment() {
        if (!AppSecurity.isNonDebuggable(this)) {
            disableForDebugBuild();
            return;
        }
        if (state.authenticationRunning) {
            return;
        }
        int biometricStatus = BiometricManager.from(this).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG);
        if (biometricStatus != BiometricManager.BIOMETRIC_SUCCESS) {
            statusView.setText("Az erős biometrikus azonosítás nem használható.");
            return;
        }

        char[] password = null;
        char[] confirmation = null;
        byte[] encoded = null;
        try {
            password = copyChars(passwordInput.getText());
            confirmation = copyChars(confirmationInput.getText());

            if (password.length == 0) {
                statusView.setText("Adja meg az XLSX megnyitási jelszavát.");
                return;
            }
            if (password.length > MAX_PASSWORD_CHARS) {
                statusView.setText("A jelszó túl hosszú.");
                return;
            }
            if (!constantTimeEquals(password, confirmation)) {
                statusView.setText("A két jelszó nem egyezik.");
                return;
            }

            encoded = Utf8Codec.encode(password);
            if (encoded.length > MAX_PASSWORD_BYTES) {
                statusView.setText("A jelszó UTF-8 kódolásban túl hosszú.");
                return;
            }
            SecretStore.EncryptionSession session =
                    SecretStore.createEncryptionSession(this);
            state.begin(session, encoded);
            encoded = null; // Ownership transferred to the ViewModel.

            clearInputs();
            setInputsEnabled(false);
            statusView.setText("Érintse meg az ujjlenyomat-olvasót.");

            try {
                BiometricPrompt.PromptInfo promptInfo =
                        new BiometricPrompt.PromptInfo.Builder()
                                .setTitle(getString(R.string.biometric_save_title))
                                .setSubtitle(getString(R.string.biometric_save_subtitle))
                                .setAllowedAuthenticators(
                                        BiometricManager.Authenticators.BIOMETRIC_STRONG)
                                .setNegativeButtonText(getString(R.string.cancel))
                                .build();

                biometricPrompt.authenticate(
                        promptInfo,
                        new BiometricPrompt.CryptoObject(session.cipher));
            } catch (RuntimeException error) {
                throw new SecretStore.SetupException("E-BP-01", error);
            }
        } catch (CharacterCodingException error) {
            statusView.setText("A jelszó érvénytelen karaktert tartalmaz.");
        } catch (SecretStore.SetupException error) {
            state.clearSecret();
            setInputsEnabled(true);
            statusView.setText("A biztonságos mentés nem indítható el.\nHibakód: "
                    + error.safeCode());
        } catch (GeneralSecurityException | java.io.IOException | RuntimeException error) {
            state.clearSecret();
            setInputsEnabled(true);
            statusView.setText(
                    "A biztonságos mentés nem indítható el.\nHibakód: E-UN-01");
        } finally {
            wipe(password);
            wipe(confirmation);
            wipe(encoded);
        }
    }

    private void completeEnrollment(BiometricPrompt.AuthenticationResult result) {
        byte[] plaintext = state.plaintext;
        SecretStore.EncryptionSession session = state.session;
        try {
            if (!AppSecurity.isNonDebuggable(this)
                    || plaintext == null || session == null) {
                throw new GeneralSecurityException("Enrollment state unavailable");
            }
            BiometricPrompt.CryptoObject crypto = result.getCryptoObject();
            Cipher cipher = crypto == null ? null : crypto.getCipher();
            if (cipher == null) {
                throw new GeneralSecurityException("Authenticated cipher unavailable");
            }

            SecretStore.completeEncryption(this, session, cipher, plaintext);
            Toast.makeText(this,
                    "Az XLSX-jelszó biztonságosan elmentve.",
                    Toast.LENGTH_LONG).show();
            state.clearSecret();
            setResult(RESULT_OK);
            finish();
        } catch (GeneralSecurityException | java.io.IOException | RuntimeException error) {
            state.clearSecret();
            setInputsEnabled(true);
            statusView.setText(
                    "A mentés nem sikerült. A jelszó nem került eltárolásra.");
        }
    }

    private void disableForDebugBuild() {
        clearInputs();
        setInputsEnabled(false);
        statusView.setText(
                "Fejlesztői buildben a valódi jelszó megadása és mentése le van tiltva.");
    }

    private void cancelAndFinish() {
        if (biometricPrompt != null && state.authenticationRunning) {
            biometricPrompt.cancelAuthentication();
        }
        state.clearSecret();
        clearInputs();
        setResult(RESULT_CANCELED);
        finish();
    }

    private void setInputsEnabled(boolean enabled) {
        passwordInput.setEnabled(enabled);
        confirmationInput.setEnabled(enabled);
        saveButton.setEnabled(enabled && AppSecurity.isNonDebuggable(this));
    }

    private void clearInputs() {
        if (passwordInput != null) {
            passwordInput.getText().clear();
        }
        if (confirmationInput != null) {
            confirmationInput.getText().clear();
        }
    }

    private EditText passwordField(String hint) {
        EditText result = new EditText(this);
        result.setHint(hint);
        result.setSingleLine(true);
        result.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        result.setImeOptions(EditorInfo.IME_ACTION_NEXT
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        result.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        result.setImportantForContentCapture(
                View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        result.setFilters(new InputFilter[] {
                new InputFilter.LengthFilter(MAX_PASSWORD_CHARS)
        });
        result.setSaveEnabled(false);
        result.setLongClickable(false);
        result.setTextIsSelectable(false);
        return AppSecurity.protectTap(result);
    }

    private Button button(String label, View.OnClickListener listener) {
        Button result = new Button(this);
        result.setText(label);
        result.setTextSize(16);
        result.setAllCaps(false);
        result.setOnClickListener(listener);
        return AppSecurity.protectTap(result);
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView result = new TextView(this);
        result.setText(value);
        result.setTextSize(sizeSp);
        result.setTextColor(color);
        result.setLineSpacing(0, 1.12f);
        result.setGravity(Gravity.START);
        return result;
    }

    private static char[] copyChars(Editable editable) {
        char[] result = new char[editable.length()];
        editable.getChars(0, editable.length(), result, 0);
        return result;
    }

    private static boolean constantTimeEquals(char[] left, char[] right) {
        int different = left.length ^ right.length;
        int maximum = Math.max(left.length, right.length);
        for (int index = 0; index < maximum; index++) {
            char leftValue = index < left.length ? left[index] : 0;
            char rightValue = index < right.length ? right[index] : 0;
            different |= leftValue ^ rightValue;
        }
        return different == 0;
    }

    private static void wipe(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams params = fullWidth();
        params.topMargin = dp(12);
        params.bottomMargin = dp(4);
        return params;
    }

    private LinearLayout.LayoutParams buttonLayout() {
        LinearLayout.LayoutParams params = fullWidth();
        params.topMargin = dp(8);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Holds plaintext only while the system biometric prompt is active. */
    public static final class EnrollmentState extends ViewModel {
        private SecretStore.EncryptionSession session;
        private byte[] plaintext;
        private boolean authenticationRunning;

        void begin(SecretStore.EncryptionSession newSession, byte[] newPlaintext) {
            clearSecret();
            session = newSession;
            plaintext = newPlaintext;
            authenticationRunning = true;
        }

        void clearSecret() {
            wipe(plaintext);
            plaintext = null;
            session = null;
            authenticationRunning = false;
        }

        @Override
        protected void onCleared() {
            clearSecret();
        }
    }
}
