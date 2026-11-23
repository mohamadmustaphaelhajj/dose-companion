package com.dosecompanion.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.view.WindowManager;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.dosecompanion.core.ProductLabel;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LabelCameraLifecycleTest {
    @Test public void backgroundAndRotationDiscardScanWithoutChangingJournal() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Use the isolated test package", context.getPackageName().endsWith(".remindertest"));
        InstrumentationRegistry.getInstrumentation().getUiAutomation().revokeRuntimePermission(context.getPackageName(), Manifest.permission.CAMERA);
        int before;
        try (DoseStore store = new DoseStore(context)) { before = store.history().size(); }
        android.graphics.Bitmap syntheticView = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888);
        Intent intent = new Intent(context, LabelCameraActivity.class).putExtra(LabelCameraActivity.ENROLL, true)
                .putExtra("expected_name", "Loniten").putExtra("expected_strength", "2.5 mg")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try (ActivityScenario<LabelCameraActivity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                assertTrue("Unlock the device before lifecycle testing", activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED));
                assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                try {
                    ((com.dosecompanion.core.ScanSequence) field("sequence").get(activity)).confirm("Loniten");
                    field("candidate").set(activity, "Unconfirmed OCR");
                    field("directionsParts").set(activity, "Unconfirmed section");
                    field("latestBitmap").set(activity, syntheticView);
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            scenario.moveToState(Lifecycle.State.CREATED);
            assertTrue("Leaving the screen must recycle the retained frame", syntheticView.isRecycled());
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.recreate();
            scenario.onActivity(activity -> {
                try {
                    assertEquals("", field("candidate").get(activity)); assertEquals("", field("directionsParts").get(activity));
                    var sequence = (com.dosecompanion.core.ScanSequence) field("sequence").get(activity);
                    assertEquals("Loniten", sequence.name());
                    assertEquals(com.dosecompanion.core.ScanSequence.Step.STRENGTH, sequence.step());
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
        }
        try (DoseStore store = new DoseStore(context)) { assertEquals("Camera must never log a dose", before, store.history().size()); }
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field field = LabelCameraActivity.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
