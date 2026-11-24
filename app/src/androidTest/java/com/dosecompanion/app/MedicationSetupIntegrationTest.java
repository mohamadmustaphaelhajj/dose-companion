package com.dosecompanion.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MedicationSetupIntegrationTest {
    @Test public void returnedScanSuggestionPreservesDoseAndRequiresExplicitSave() throws Exception {
        returnedSuggestion(false);
    }
    @Test public void returnedCloudDraftPreservesDoseAndRequiresExplicitSave() throws Exception {
        returnedSuggestion(true);
    }
    private void returnedSuggestion(boolean local) throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        assertTrue(context.getPackageName().endsWith(".remindertest") || context.getPackageName().endsWith(".localexperiment"));
        int before;
        try (DoseStore store = new DoseStore(context)) { before = store.medications().size(); }
        var monitor = instrumentation.addMonitor(local ? CloudLabelActivity.class.getName() : LabelCameraActivity.class.getName(), null, false);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    Method add = MainActivity.class.getDeclaredMethod("addMedication"); add.setAccessible(true); add.invoke(activity);
                    AlertDialog dialog = (AlertDialog) field("medicationDialog").get(activity);
                    EditText quantity = (EditText) find(dialog.getWindow().getDecorView(), "For example, 1 tablet", true);
                    assertNotNull(quantity); quantity.setText("1 tablet");
                    ((EditText) field("scanName").get(activity)).setText("Synthetic product");
                    ((EditText) field("scanStrength").get(activity)).setText("2.5 mg");
                    View scan = find(dialog.getWindow().getDecorView(), local ? "Read label from photos (cloud)" : "Scan name, strength and directions", false);
                    assertNotNull(scan); scan.performClick();
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            Activity scanner = instrumentation.waitForMonitorWithTimeout(monitor, 5000);
            assertNotNull("The form should launch the real scanner activity", scanner);
            instrumentation.runOnMainSync(() -> {
                scanner.setResult(Activity.RESULT_OK, new Intent().putExtra("product_name", "Synthetic product")
                        .putExtra("strength", "2.5 mg").putExtra("confirmed_directions", "Take 1 tablet daily")
                        .putExtra(local ? "cloud_draft_reviewed" : "sequence_confirmed", true)); scanner.finish();
            });
            instrumentation.waitForIdleSync();
            scenario.onActivity(activity -> {
                try {
                    AlertDialog dialog = (AlertDialog) field("medicationDialog").get(activity);
                    assertTrue(dialog.isShowing());
                    assertEquals("Synthetic product", ((EditText) field("scanName").get(activity)).getText().toString());
                    assertEquals("2.5 mg", ((EditText) field("scanStrength").get(activity)).getText().toString());
                    assertTrue(((TextView) field("scanReview").get(activity)).getText().toString().contains(local ? "cloud reader" : "each scanned field"));
                    assertEquals("Take 1 tablet daily", ((EditText) field("scanDirections").get(activity)).getText().toString());
                    assertEquals("1 tablet", ((EditText) find(dialog.getWindow().getDecorView(), "For example, 1 tablet", true)).getText().toString());
                    assertEquals("Confirm details and save", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
                    dialog.dismiss();
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
        } finally { instrumentation.removeMonitor(monitor); }
        try (DoseStore store = new DoseStore(context)) { assertEquals("A scan must not save medication automatically", before, store.medications().size()); }
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static View find(View view, String value, boolean hint) {
        if (hint && view instanceof EditText && value.contentEquals(((EditText) view).getHint())) return view;
        if (!hint && view instanceof TextView && value.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = find(((ViewGroup) view).getChildAt(i), value, hint); if (found != null) return found;
        }
        return null;
    }
}
