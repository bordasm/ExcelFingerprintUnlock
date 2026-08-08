package hu.bordasm.excelautofillprobe;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.autofill.AutofillManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public final class MainActivity extends Activity {
    private TextView statusView;
    private TextView reportView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(getString(R.string.app_name));
        setContentView(buildContent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private ScrollView buildContent() {
        int padding = dp(20);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);

        TextView title = text("Excel–Autofill kompatibilitási próba", 24, Color.rgb(20, 50, 80));
        content.addView(title, matchWrap());

        TextView intro = text(
                "Ez az alkalmazás nem kér és nem tárol XLSX-jelszót. Csak azt vizsgálja, "
                        + "hogy az Excel fájljelszómezője elérhető-e az Android Autofill számára. "
                        + "Mezőtartalmat és fájlnevet nem olvas.",
                16, Color.DKGRAY);
        intro.setPadding(0, dp(10), 0, dp(14));
        content.addView(intro, matchWrap());

        statusView = text("", 16, Color.BLACK);
        statusView.setPadding(dp(12), dp(12), dp(12), dp(12));
        content.addView(statusView, matchWrap());

        content.addView(button("1. Autofill szolgáltatás kiválasztása", view -> requestAutofillService()),
                matchWrapWithTopMargin());
        content.addView(button("2. Próba egyszeri élesítése (2 perc)", view -> armProbe()),
                matchWrapWithTopMargin());
        content.addView(button("3. Microsoft Excel megnyitása", view -> openExcel()),
                matchWrapWithTopMargin());

        TextView steps = text(
                "A jelszavas fájl megnyitásakor érintse meg a jelszómezőt. Ha megjelenik az "
                        + "„Excel-próba” javaslat, koppintson rá. Sikernél a mezőbe ez kerül: "
                        + "XLSX_AUTOFILL_PROBE_7F3A. Az Excel Megnyitás/OK gombját ne nyomja meg; "
                        + "ez nem valódi jelszó.",
                15, Color.DKGRAY);
        steps.setPadding(0, dp(16), 0, dp(8));
        content.addView(steps, matchWrap());

        content.addView(button("Állapot és jelentés frissítése", view -> refresh()),
                matchWrapWithTopMargin());
        content.addView(button("Diagnosztika törlése", view -> clearDiagnostics()),
                matchWrapWithTopMargin());

        TextView shareNotice = text(
                "A megosztható jelentés nem tartalmaz jelszót vagy mezőtartalmat, de tartalmazza "
                        + "a készülék modelljét, az Android- és Excel-verziót, valamint az Excel "
                        + "aláírólenyomatát.",
                13, Color.DKGRAY);
        shareNotice.setPadding(0, dp(10), 0, 0);
        content.addView(shareNotice, matchWrap());
        content.addView(button("Sanitizált jelentés megosztása", view -> shareReport()),
                matchWrapWithTopMargin());

        TextView reportTitle = text("Helyi diagnosztikai jelentés", 18, Color.rgb(20, 50, 80));
        reportTitle.setPadding(0, dp(20), 0, dp(6));
        content.addView(reportTitle, matchWrap());

        reportView = text("", 12, Color.rgb(35, 35, 35));
        reportView.setTypeface(android.graphics.Typeface.MONOSPACE);
        reportView.setTextIsSelectable(true);
        reportView.setMovementMethod(new ScrollingMovementMethod());
        reportView.setPadding(dp(10), dp(10), dp(10), dp(10));
        reportView.setBackgroundColor(Color.rgb(245, 247, 249));
        content.addView(reportView, matchWrap());

        TextView restore = text(
                "A teszt végén állítsa vissza a korábban használt automatikus kitöltési "
                        + "szolgáltatást (például Samsung Pass), mert Androidon egyszerre egy "
                        + "elsődleges Autofill szolgáltató aktív.",
                14, Color.DKGRAY);
        restore.setPadding(0, dp(16), 0, dp(24));
        content.addView(restore, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scroll.setClipToPadding(false);
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets systemBars =
                        insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(0, systemBars.top, 0, systemBars.bottom);
                return insets;
            });
        }
        return scroll;
    }

    private void requestAutofillService() {
        Intent request = new Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE);
        request.setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(request);
        } catch (ActivityNotFoundException error) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (ActivityNotFoundException secondError) {
                Toast.makeText(this, "Az Autofill-beállítás nem nyitható meg ezen a készüléken.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void armProbe() {
        if (!isServiceEnabled()) {
            Toast.makeText(this, "Előbb válassza ki ezt az Autofill szolgáltatást.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        ProbeState.arm(this);
        Toast.makeText(this, "A próba 2 percre élesítve.", Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void openExcel() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(ExcelInfo.EXCEL_PACKAGE);
        if (launch == null) {
            Toast.makeText(this, "A Microsoft Excel nincs telepítve.", Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(launch);
    }

    private void clearDiagnostics() {
        ProbeState.disarm(this);
        ProbeLog.clear(this);
        refresh();
        Toast.makeText(this, "A helyi diagnosztika törölve.", Toast.LENGTH_SHORT).show();
    }

    private void shareReport() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Excel Autofill próba – jelentés");
        send.putExtra(Intent.EXTRA_TEXT, buildReport());
        startActivity(Intent.createChooser(send, "Jelentés megosztása"));
    }

    private void refresh() {
        if (statusView == null || reportView == null) {
            return;
        }
        AutofillManager manager = getSystemService(AutofillManager.class);
        boolean supported = manager != null && manager.isAutofillSupported();
        boolean enabled = isServiceEnabled();
        long remaining = ProbeState.remainingMillis(this);

        String status = String.format(Locale.ROOT,
                "Autofill támogatás: %s\nPróbaszolgáltatás: %s\nEgyszeri próba: %s",
                supported ? "igen" : "nem",
                enabled ? "AKTÍV" : "nincs kiválasztva",
                remaining > 0 ? "ÉLESÍTVE (még kb. " + ((remaining + 999) / 1000) + " mp)"
                        : "nincs élesítve");
        statusView.setText(status);
        statusView.setBackgroundColor(enabled
                ? Color.rgb(226, 244, 232)
                : Color.rgb(255, 243, 205));
        reportView.setText(buildReport());
    }

    private boolean isServiceEnabled() {
        AutofillManager manager = getSystemService(AutofillManager.class);
        return manager != null && manager.hasEnabledAutofillServices();
    }

    private String buildReport() {
        return String.format(Locale.ROOT,
                "APP: Excel Autofill próba 0.1\n"
                        + "ANDROID: %s (API %d)\n"
                        + "DEVICE: %s %s\n"
                        + "%s\n\n"
                        + "ESEMÉNYEK (nincs mezőtartalom/jelszó/fájlnév):\n%s",
                Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
                Build.MANUFACTURER, Build.MODEL,
                ExcelInfo.describe(this), ProbeLog.read(this));
    }

    private Button button(String label, android.view.View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setOnClickListener(listener);
        return button;
    }

    private TextView text(String value, int sp, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        text.setLineSpacing(0, 1.12f);
        return text;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapWithTopMargin() {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(6);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
