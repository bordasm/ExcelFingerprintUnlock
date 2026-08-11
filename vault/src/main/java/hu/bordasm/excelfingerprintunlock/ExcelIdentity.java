package hu.bordasm.excelfingerprintunlock;

import android.app.assist.AssistStructure;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.util.Locale;

final class ExcelIdentity {
    static final String PACKAGE_NAME = "com.microsoft.office.excel";

    private static final byte[] MICROSOFT_SIGNER_SHA256 = new byte[] {
            (byte) 0x1F, (byte) 0xB4, (byte) 0xDE, (byte) 0x76,
            (byte) 0x0F, (byte) 0x40, (byte) 0xF3, (byte) 0x0E,
            (byte) 0x66, (byte) 0xD3, (byte) 0x08, (byte) 0x51,
            (byte) 0x8A, (byte) 0x1B, (byte) 0xD9, (byte) 0xD1,
            (byte) 0x4E, (byte) 0xF1, (byte) 0x41, (byte) 0x1B,
            (byte) 0xFF, (byte) 0xA9, (byte) 0xD6, (byte) 0x76,
            (byte) 0x28, (byte) 0x76, (byte) 0xB6, (byte) 0x02,
            (byte) 0x66, (byte) 0x1D, (byte) 0x54, (byte) 0xED
    };

    private ExcelIdentity() {
    }

    static boolean isTrustedInstalledExcel(Context context) {
        try {
            PackageManager packageManager = context.getPackageManager();
            packageManager.getPackageInfo(PACKAGE_NAME, 0);
            return packageManager.hasSigningCertificate(
                    PACKAGE_NAME,
                    MICROSOFT_SIGNER_SHA256,
                    PackageManager.CERT_INPUT_SHA256);
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            return false;
        }
    }

    static boolean isTrustedExcelStructure(Context context, AssistStructure structure) {
        if (structure == null) {
            return false;
        }
        ComponentName activity = structure.getActivityComponent();
        return activity != null
                && PACKAGE_NAME.equals(activity.getPackageName())
                && isTrustedInstalledExcel(context);
    }

    static String describe(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(PACKAGE_NAME, 0);
            long code = info.getLongVersionCode();
            return String.format(Locale.ROOT, "Microsoft Excel: %s (%d) – %s",
                    info.versionName, code,
                    isTrustedInstalledExcel(context)
                            ? "Microsoft-aláírás hiteles; verzió nincs korlátozva"
                            : "ALÁÍRÁS NEM ELFOGADOTT");
        } catch (PackageManager.NameNotFoundException error) {
            return "Microsoft Excel: nincs telepítve";
        }
    }
}
