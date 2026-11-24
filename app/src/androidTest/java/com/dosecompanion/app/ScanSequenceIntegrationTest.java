package com.dosecompanion.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.dosecompanion.core.ScanSequence;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Synthetic OCR inputs exercise real dialogs and confirmation transitions; OCR itself is tested separately. */
@RunWith(AndroidJUnit4.class)
public class ScanSequenceIntegrationTest {
    @Test public void confirmsEachFieldAndReviewsJoinedDirectionsWithoutLoggingTaken() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        assertTrue(context.getPackageName().endsWith(".remindertest"));
        instrumentation.getUiAutomation().revokeRuntimePermission(context.getPackageName(), Manifest.permission.CAMERA);
        int before;
        try (DoseStore store = new DoseStore(context)) { before = store.history().size(); }
        Intent intent = new Intent(context, LabelCameraActivity.class).putExtra(LabelCameraActivity.ENROLL, true)
                .putExtra("expected_name", "Loniten").putExtra("expected_strength", "2.5 mg");
        try (ActivityScenario<LabelCameraActivity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                try {
                    ScanSequence sequence = (ScanSequence) field("sequence").get(activity);
                    assertEquals(ScanSequence.Step.NAME, sequence.step());
                    field("candidate").set(activity, "Loniten"); invoke(activity, "reviewField");
                    assertEquals("Review must not advance automatically", ScanSequence.Step.NAME, sequence.step());
                    dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                    assertEquals(ScanSequence.Step.STRENGTH, sequence.step());
                    field("candidate").set(activity, "25 mg"); invoke(activity, "reviewField");
                    dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                    assertEquals("Wrong observed strength cannot be confirmed", ScanSequence.Step.STRENGTH, sequence.step());
                    EditText edit = findEdit(dialog(activity).getWindow().getDecorView());
                    assertNotNull(edit.getError()); edit.setText("2.5 mg");
                    dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                    assertEquals(ScanSequence.Step.DIRECTIONS, sequence.step());
                    field("candidate").set(activity, "Take 1 tablet daily"); invoke(activity, "addSection");
                    field("candidate").set(activity, "tablet daily with food"); invoke(activity, "reviewField");
                    assertEquals("Take 1 tablet daily with food", findEdit(dialog(activity).getWindow().getDecorView()).getText().toString());
                    dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                    assertEquals(ScanSequence.Step.COMPLETE, sequence.step());
                    assertEquals("Take 1 tablet daily with food", sequence.directions());
                    dialog(activity).dismiss();
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
        }
        try (DoseStore store = new DoseStore(context)) { assertEquals(before, store.history().size()); }
    }
    private static AlertDialog dialog(LabelCameraActivity activity) throws ReflectiveOperationException { return (AlertDialog) field("reviewDialog").get(activity); }
    private static Field field(String name) throws NoSuchFieldException { Field f = LabelCameraActivity.class.getDeclaredField(name); f.setAccessible(true); return f; }
    private static void invoke(LabelCameraActivity a, String name) throws ReflectiveOperationException { Method m = LabelCameraActivity.class.getDeclaredMethod(name); m.setAccessible(true); m.invoke(a); }
    private static EditText findEdit(android.view.View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) view).getChildCount(); i++) {
            EditText found = findEdit(((android.view.ViewGroup) view).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
}
