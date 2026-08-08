package hu.bordasm.excelfingerprintunlock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.autofill.AutofillManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.biometric.BiometricManager;

import java.security.GeneralSecurityException;

/** Configuration screen. It never handles or displays the saved secret itself. */
public final class MainActivity extends Activity {
    private static final int COLOR_HEADING = Color.rgb(20, 50, 80);
    private static final int COLOR_OK = Color.rgb(20, 105, 55);
    private static final int COLOR_WARNING = Color.rgb(165, 75, 10);
    private static final int COLOR_ERROR = Color.rgb(165, 30, 35);

    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppSecurity.secureWindow(getWindow());
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (content != null) {
            populateUi();
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(24), dp(22), dp(32));
        content.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(dp(22) + bars.left, dp(24) + bars.top,
                    dp(22) + bars.right, dp(32) + bars.bottom);
            return windowInsets;
        });
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        populateUi();
    }

    private void populateUi() {
        content.removeAllViews();

        TextView title = text("Excel jelszó – ujjlenyomat", 25, COLOR_HEADING);
        title.setGravity(Gravity.START);
        content.addView(title, fullWidth());

        content.addView(text(
                "A mentett XLSX-megnyitási jelszót kizárólag a hiteles Microsoft "
                        + "Excel jelszómezőjébe tölti ki, minden alkalommal erős "
                        + "biometrikus azonosítás után.",
                16, Color.DKGRAY), spaced());

        boolean releaseBuild = AppSecurity.isNonDebuggable(this);
        boolean excelTrusted = ExcelIdentity.isTrustedInstalledExcel(this);
        int biometricStatus = BiometricManager.from(this).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG);
        boolean strongBiometricReady = biometricStatus == BiometricManager.BIOMETRIC_SUCCESS;
        AutofillManager autofillManager = getSystemService(AutofillManager.class);
        boolean autofillSupported = autofillManager != null
                && autofillManager.isAutofillSupported();
        boolean serviceEnabled = autofillManager != null
                && autofillManager.hasEnabledAutofillServices();
        SecretStore.Inspection inspection = SecretStore.inspect(this);

        addSection("Biztonsági állapot");
        if (releaseBuild) {
            addStatus("Kiadási build: a titokkezelés engedélyezett.", COLOR_OK);
        } else {
            addStatus(
                    "FEJLESZTŐI BUILD: jelszó megadása és mentése letiltva. "
                            + "Valódi jelszóval csak a külön kiadási kulccsal aláírt APK használható.",
                    COLOR_ERROR);
        }

        addStatus(ExcelIdentity.describe(this), excelTrusted ? COLOR_OK : COLOR_ERROR);
        addStatus(describeBiometric(biometricStatus),
                strongBiometricReady ? COLOR_OK : COLOR_ERROR);
        addStatus(describeVault(inspection),
                inspection.state == SecretStore.State.READY ? COLOR_OK
                        : inspection.state == SecretStore.State.EMPTY ? COLOR_WARNING : COLOR_ERROR);

        addSection("Autofill szolgáltatás");
        addStatus(
                !autofillSupported
                        ? "Az Android Autofill ezen a készüléken nem támogatott."
                        : serviceEnabled
                        ? "Ez az Autofill szolgáltatás aktív."
                        : "Az Autofill szolgáltatás még nincs kiválasztva.",
                !autofillSupported ? COLOR_ERROR : serviceEnabled ? COLOR_OK : COLOR_WARNING);

        Button autofillButton = button("Autofill szolgáltatás kiválasztása",
                view -> requestAutofillService());
        autofillButton.setEnabled(autofillSupported);
        content.addView(autofillButton, buttonLayout());

        Button enrollButton = button(
                inspection.state == SecretStore.State.READY
                        ? "Mentett XLSX-jelszó módosítása"
                        : inspection.state == SecretStore.State.INVALID
                        ? "Előbb törölje a használhatatlan tárolót"
                        : "XLSX-jelszó biztonságos beállítása",
                view -> startActivity(new Intent(this, EnrollmentActivity.class)));
        enrollButton.setEnabled(releaseBuild && strongBiometricReady
                && inspection.state != SecretStore.State.INVALID);
        content.addView(enrollButton, buttonLayout());

        if (inspection.state != SecretStore.State.EMPTY) {
            Button resetButton = button("Mentett jelszó és kulcs törlése",
                    view -> confirmReset());
            resetButton.setEnabled(releaseBuild);
            content.addView(resetButton, buttonLayout());
        }

        content.addView(text(
                "Az alkalmazás nem használ internetet, vágólapot, tárhely-hozzáférést "
                        + "vagy Accessibility szolgáltatást. Az XLSX-jelszó titkosítva, "
                        + "a készülék Android Keystore kulcsával védve marad.",
                14, Color.GRAY), spaced());
    }

    private void requestAutofillService() {
        Intent intent = new Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
                .setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (RuntimeException error) {
            Toast.makeText(this,
                    "Az Autofill-beállítás nem nyitható meg ezen a készüléken.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void confirmReset() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Mentett jelszó törlése")
                .setMessage("A titkosított jelszó és a hozzá tartozó Keystore-kulcs "
                        + "végleg törlődik erről a készülékről.")
                .setNegativeButton("Mégse", null)
                .setPositiveButton("Törlés", (ignored, which) -> resetSecret())
                .create();
        dialog.setOnShowListener(ignored -> {
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null) {
                AppSecurity.protectTap(positive);
                positive.setTextColor(COLOR_ERROR);
            }
            Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (negative != null) {
                AppSecurity.protectTap(negative);
            }
        });
        dialog.show();
    }

    private void resetSecret() {
        if (!AppSecurity.isNonDebuggable(this)) {
            Toast.makeText(this, "Fejlesztői buildben a titokkezelés le van tiltva.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            SecretStore.reset(this);
            Toast.makeText(this, "A mentett jelszó és kulcs törölve.",
                    Toast.LENGTH_LONG).show();
        } catch (GeneralSecurityException | RuntimeException error) {
            Toast.makeText(this, "A biztonságos törlés nem sikerült.",
                    Toast.LENGTH_LONG).show();
        }
        populateUi();
    }

    private void addSection(String value) {
        TextView heading = text(value, 18, COLOR_HEADING);
        heading.setPadding(0, dp(15), 0, dp(5));
        content.addView(heading, fullWidth());
    }

    private void addStatus(String value, int color) {
        TextView status = text(value, 15, color);
        status.setPadding(0, dp(4), 0, dp(4));
        content.addView(status, fullWidth());
    }

    private Button button(String label, View.OnClickListener listener) {
        Button result = new Button(this);
        result.setAllCaps(false);
        result.setText(label);
        result.setTextSize(16);
        result.setOnClickListener(listener);
        return AppSecurity.protectTap(result);
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView result = new TextView(this);
        result.setText(value);
        result.setTextSize(sizeSp);
        result.setTextColor(color);
        result.setLineSpacing(0, 1.12f);
        return result;
    }

    private String describeBiometric(int status) {
        switch (status) {
            case BiometricManager.BIOMETRIC_SUCCESS:
                return "Erős biometrikus azonosítás: használatra kész.";
            case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED:
                return "Nincs erős biometrikus azonosító regisztrálva.";
            case BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE:
                return "A készüléken nincs megfelelő biometrikus hardver.";
            case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                return "A biometrikus hardver jelenleg nem érhető el.";
            case BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED:
                return "A biometria használatához biztonsági frissítés szükséges.";
            default:
                return "Az erős biometrikus azonosítás nem használható (kód: "
                        + status + ").";
        }
    }

    private String describeVault(SecretStore.Inspection inspection) {
        switch (inspection.state) {
            case READY:
                return "Mentett jelszó: használatra kész; kulcsvédelem: "
                        + inspection.hardware + ".";
            case EMPTY:
                return "Mentett jelszó: még nincs beállítva.";
            case INVALID:
            default:
                return "Mentett jelszó: nem használható (" + inspection.hardware
                        + ")." + (inspection.safeCode.isEmpty() ? "" : " Hibakód: "
                        + inspection.safeCode + ".")
                        + " Előbb törölje a mentett jelszót és kulcsot, majd állítsa be újra.";
        }
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams params = fullWidth();
        params.topMargin = dp(10);
        params.bottomMargin = dp(8);
        return params;
    }

    private LinearLayout.LayoutParams buttonLayout() {
        LinearLayout.LayoutParams params = fullWidth();
        params.topMargin = dp(6);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
