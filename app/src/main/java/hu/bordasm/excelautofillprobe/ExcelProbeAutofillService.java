package hu.bordasm.excelautofillprobe;

import android.app.assist.AssistStructure;
import android.content.ComponentName;
import android.os.Build;
import android.os.CancellationSignal;
import android.service.autofill.AutofillService;
import android.service.autofill.Dataset;
import android.service.autofill.FillCallback;
import android.service.autofill.FillContext;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveRequest;
import android.view.View;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.widget.RemoteViews;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

public final class ExcelProbeAutofillService extends AutofillService {
    private static final int MAX_NODES = 10_000;
    private static final String SENTINEL = "XLSX_AUTOFILL_PROBE_7F3A";

    @Override
    public void onConnected() {
        super.onConnected();
        ProbeLog.append(this, "SERVICE_CONNECTED");
    }

    @Override
    public void onDisconnected() {
        ProbeLog.append(this, "SERVICE_DISCONNECTED");
        super.onDisconnected();
    }

    @Override
    public void onFillRequest(FillRequest request, CancellationSignal cancellationSignal,
                              FillCallback callback) {
        List<FillContext> contexts = request.getFillContexts();
        if (contexts.isEmpty() || cancellationSignal.isCanceled()) {
            callback.onSuccess(null);
            return;
        }

        AssistStructure structure = contexts.get(contexts.size() - 1).getStructure();
        ComponentName activity = structure.getActivityComponent();
        if (activity == null || !ExcelInfo.EXCEL_PACKAGE.equals(activity.getPackageName())) {
            // Más alkalmazás struktúráját még diagnosztikai célból sem járjuk be.
            callback.onSuccess(null);
            return;
        }

        boolean compatibilityMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && (request.getFlags() & FillRequest.FLAG_COMPATIBILITY_MODE_REQUEST) != 0;
        ProbeLog.append(this, String.format(Locale.ROOT,
                "REQUEST_RECEIVED mode=%s contexts=%d",
                compatibilityMode ? "COMPATIBILITY" : "NATIVE", contexts.size()));

        if (!ProbeState.isArmed(this)) {
            ProbeLog.append(this, "IGNORED_NOT_ARMED");
            callback.onSuccess(null);
            return;
        }

        ScanResult scan = scanStructure(structure);
        ProbeLog.append(this, String.format(Locale.ROOT,
                "STRUCTURE nodes=%d autofillIds=%d textTypes=%d focused=%d eligible=%d limitHit=%s",
                scan.nodes, scan.autofillIds, scan.textTypes, scan.focused,
                scan.eligible, scan.limitHit));

        if (scan.target == null || cancellationSignal.isCanceled()) {
            ProbeLog.append(this, "NO_ELIGIBLE_FIELD");
            callback.onSuccess(null);
            return;
        }

        RemoteViews presentation = new RemoteViews(getPackageName(),
                R.layout.autofill_probe_item);
        presentation.setTextViewText(R.id.autofill_probe_text,
                getString(R.string.autofill_probe_label));

        Dataset dataset = new Dataset.Builder(presentation)
                .setValue(scan.target, AutofillValue.forText(SENTINEL))
                .build();
        FillResponse response = new FillResponse.Builder()
                .addDataset(dataset)
                .build();

        ProbeState.disarm(this);
        ProbeLog.append(this, "PROBE_RESPONSE_SENT sentinelLength=" + SENTINEL.length());
        callback.onSuccess(response);
    }

    @Override
    public void onSaveRequest(SaveRequest request, SaveCallback callback) {
        // Nincs SaveInfo, ezért normál működésben ez nem fut le. A kérés tartalmát nem olvassuk.
        callback.onSuccess();
    }

    private ScanResult scanStructure(AssistStructure structure) {
        ScanResult result = new ScanResult();
        Deque<AssistStructure.ViewNode> pending = new ArrayDeque<>();
        for (int windowIndex = 0; windowIndex < structure.getWindowNodeCount(); windowIndex++) {
            AssistStructure.ViewNode root = structure.getWindowNodeAt(windowIndex).getRootViewNode();
            if (root != null) {
                pending.addLast(root);
            }
        }

        while (!pending.isEmpty() && result.nodes < MAX_NODES) {
            AssistStructure.ViewNode node = pending.removeFirst();
            result.nodes++;

            AutofillId id = node.getAutofillId();
            int type = node.getAutofillType();
            boolean focused = node.isFocused();
            if (id != null) {
                result.autofillIds++;
            }
            if (type == View.AUTOFILL_TYPE_TEXT) {
                result.textTypes++;
            }
            if (focused) {
                result.focused++;
            }
            if (focused && id != null && type == View.AUTOFILL_TYPE_TEXT) {
                result.eligible++;
                if (result.target == null) {
                    result.target = id;
                }
            }

            for (int childIndex = 0; childIndex < node.getChildCount(); childIndex++) {
                AssistStructure.ViewNode child = node.getChildAt(childIndex);
                if (child != null) {
                    pending.addLast(child);
                }
            }
        }
        result.limitHit = !pending.isEmpty();
        return result;
    }

    private static final class ScanResult {
        int nodes;
        int autofillIds;
        int textTypes;
        int focused;
        int eligible;
        boolean limitHit;
        AutofillId target;
    }
}
