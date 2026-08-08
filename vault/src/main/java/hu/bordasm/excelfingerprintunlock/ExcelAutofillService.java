package hu.bordasm.excelfingerprintunlock;

import android.app.PendingIntent;
import android.app.assist.AssistStructure;
import android.content.Intent;
import android.net.Uri;
import android.os.CancellationSignal;
import android.service.autofill.AutofillService;
import android.service.autofill.Dataset;
import android.service.autofill.FillCallback;
import android.service.autofill.FillContext;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveRequest;
import android.view.autofill.AutofillId;
import android.widget.RemoteViews;

import java.util.List;
import java.util.UUID;

public final class ExcelAutofillService extends AutofillService {
    @Override
    public void onFillRequest(FillRequest request, CancellationSignal cancellationSignal,
                              FillCallback callback) {
        if (!AppSecurity.isNonDebuggable(this) || cancellationSignal.isCanceled()) {
            callback.onSuccess(null);
            return;
        }

        List<FillContext> contexts = request.getFillContexts();
        if (contexts == null || contexts.isEmpty()) {
            callback.onSuccess(null);
            return;
        }

        AssistStructure structure = contexts.get(contexts.size() - 1).getStructure();
        if (!ExcelIdentity.isTrustedExcelStructure(this, structure)) {
            callback.onSuccess(null);
            return;
        }

        SecretStore.Inspection inspection = SecretStore.inspect(this);
        if (inspection.state != SecretStore.State.READY) {
            callback.onSuccess(null);
            return;
        }

        AutofillId target = ExcelStructure.findSingleEligibleField(structure);
        if (target == null || cancellationSignal.isCanceled()) {
            callback.onSuccess(null);
            return;
        }

        RemoteViews presentation = new RemoteViews(getPackageName(), R.layout.autofill_item);
        presentation.setTextViewText(R.id.autofill_text,
                getString(R.string.autofill_locked_label));

        Intent authenticate = new Intent(this, AutofillAuthActivity.class)
                .setAction(AutofillAuthActivity.ACTION_AUTHENTICATE)
                .setData(new Uri.Builder()
                        .scheme("excel-fingerprint")
                        .authority("authenticate")
                        .appendPath(UUID.randomUUID().toString())
                        .build());
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                authenticate,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE);

        Dataset locked = new Dataset.Builder(presentation)
                .setValue(target, null)
                .setAuthentication(pendingIntent.getIntentSender())
                .setId("xlsx_password_locked_v1")
                .build();

        if (cancellationSignal.isCanceled()) {
            callback.onSuccess(null);
            return;
        }
        callback.onSuccess(new FillResponse.Builder().addDataset(locked).build());
    }

    @Override
    public void onSaveRequest(SaveRequest request, SaveCallback callback) {
        callback.onSuccess();
    }
}
