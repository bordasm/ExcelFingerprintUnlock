package hu.bordasm.excelfingerprintunlock;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

final class AppSecurity {
    private AppSecurity() {
    }

    static boolean isNonDebuggable(Context context) {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0;
    }

    static void secureWindow(Window window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    static <T extends View> T protectTap(T view) {
        view.setFilterTouchesWhenObscured(true);
        return view;
    }
}
