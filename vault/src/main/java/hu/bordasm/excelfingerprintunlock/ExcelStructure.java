package hu.bordasm.excelfingerprintunlock;

import android.app.assist.AssistStructure;
import android.text.InputType;
import android.view.View;
import android.view.autofill.AutofillId;

import java.util.ArrayDeque;
import java.util.Deque;

final class ExcelStructure {
    // A két, ugyanazon A16/Excel builden végzett natív próba 89 és 90 node-ot,
    // valamint pontosan egy text Autofill mezőt adott. A szűk profil szándékosan
    // fail-closed: más Excel-képernyőn nem ajánlhatja fel a fájljelszót.
    private static final int MIN_PROFILE_NODES = 80;
    private static final int MAX_PROFILE_NODES = 110;

    private ExcelStructure() {
    }

    static AutofillId findSingleEligibleField(AssistStructure structure) {
        if (structure == null) {
            return null;
        }
        Deque<AssistStructure.ViewNode> pending = new ArrayDeque<>();
        for (int windowIndex = 0; windowIndex < structure.getWindowNodeCount(); windowIndex++) {
            AssistStructure.ViewNode root = structure.getWindowNodeAt(windowIndex).getRootViewNode();
            if (root != null) {
                pending.addLast(root);
            }
        }

        AutofillId result = null;
        int eligibleCount = 0;
        int textFieldCount = 0;
        int visited = 0;
        while (!pending.isEmpty() && visited <= MAX_PROFILE_NODES) {
            AssistStructure.ViewNode node = pending.removeFirst();
            visited++;

            AutofillId id = node.getAutofillId();
            if (id != null && node.getAutofillType() == View.AUTOFILL_TYPE_TEXT) {
                textFieldCount++;
            }
            if (node.isFocused() && id != null
                    && node.getAutofillType() == View.AUTOFILL_TYPE_TEXT
                    && node.getWebDomain() == null
                    && isPasswordInputType(node.getInputType())) {
                eligibleCount++;
                result = id;
            }

            for (int childIndex = 0; childIndex < node.getChildCount(); childIndex++) {
                AssistStructure.ViewNode child = node.getChildAt(childIndex);
                if (child != null) {
                    pending.addLast(child);
                }
            }
        }
        if (!pending.isEmpty()
                || visited < MIN_PROFILE_NODES
                || visited > MAX_PROFILE_NODES
                || textFieldCount != 1
                || eligibleCount != 1) {
            return null;
        }
        return result;
    }

    private static boolean isPasswordInputType(int inputType) {
        int inputClass = inputType & InputType.TYPE_MASK_CLASS;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        if (inputClass == InputType.TYPE_CLASS_TEXT) {
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
        }
        return inputClass == InputType.TYPE_CLASS_NUMBER
                && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }
}
