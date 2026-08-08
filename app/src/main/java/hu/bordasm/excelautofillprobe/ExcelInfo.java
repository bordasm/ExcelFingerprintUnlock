package hu.bordasm.excelautofillprobe;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.security.MessageDigest;
import java.util.Locale;

final class ExcelInfo {
    static final String EXCEL_PACKAGE = "com.microsoft.office.excel";

    private ExcelInfo() {
    }

    static String describe(Context context) {
        PackageManager manager = context.getPackageManager();
        try {
            PackageInfo info;
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info = manager.getPackageInfo(EXCEL_PACKAGE,
                        PackageManager.GET_SIGNING_CERTIFICATES);
                signatures = info.signingInfo == null
                        ? new Signature[0]
                        : info.signingInfo.getApkContentsSigners();
            } else {
                //noinspection deprecation
                info = manager.getPackageInfo(EXCEL_PACKAGE, PackageManager.GET_SIGNATURES);
                //noinspection deprecation
                signatures = info.signatures == null ? new Signature[0] : info.signatures;
            }

            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode()
                    //noinspection deprecation
                    : info.versionCode;
            String signer = signatures.length == 0
                    ? "ismeretlen"
                    : sha256(signatures[0].toByteArray());
            return String.format(Locale.ROOT,
                    "Excel: %s (%d)%nCsomag: %s%nAláíró SHA-256: %s",
                    info.versionName, versionCode, EXCEL_PACKAGE, signer);
        } catch (PackageManager.NameNotFoundException error) {
            return "Excel: nincs telepítve\nCsomag: " + EXCEL_PACKAGE;
        } catch (Exception error) {
            return "Excel: telepítve, de a verzióadat nem olvasható";
        }
    }

    private static String sha256(byte[] input) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
        StringBuilder text = new StringBuilder(digest.length * 3);
        for (int index = 0; index < digest.length; index++) {
            if (index > 0) {
                text.append(':');
            }
            text.append(String.format(Locale.ROOT, "%02X", digest[index]));
        }
        return text.toString();
    }
}
