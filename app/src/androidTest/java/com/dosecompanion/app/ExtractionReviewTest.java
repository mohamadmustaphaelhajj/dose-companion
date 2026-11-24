package com.dosecompanion.app;

import android.Manifest;
import android.content.Context;
import android.graphics.Bitmap;
import android.view.*;
import android.widget.*;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.dosecompanion.core.LabelDraft;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ExtractionReviewTest {
    @Test public void combinedReadingRequiresReviewAndBackgroundDiscardsAllUnconfirmedPixelsAndDrafts() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        assertTrue(context.getPackageName().endsWith(".localexperiment"));
        boolean hadPermission = context.checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (hadPermission) instrumentation.getUiAutomation().revokeRuntimePermission(context.getPackageName(), Manifest.permission.CAMERA);
        Bitmap first = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Bitmap second = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        int before;
        try (DoseStore store = new DoseStore(context)) { before = store.medications().size(); }
        try (ActivityScenario<ExtractionLabActivity> scenario = ActivityScenario.launch(ExtractionLabActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    assertEquals(Lifecycle.State.RESUMED, activity.getLifecycle().getCurrentState());
                    images(activity).addAll(List.of(first, second));
                    drafts(activity).add(new LabelDraft("Loniten", "2.5 mg", "Take 1 tablet daily."));
                    Field joined = ExtractionLabActivity.class.getDeclaredField("assembled"); joined.setAccessible(true); joined.set(activity, first.copy(Bitmap.Config.ARGB_8888, false));
                    Method assembly = ExtractionLabActivity.class.getDeclaredMethod("showAssembly"); assembly.setAccessible(true); assembly.invoke(activity);
                    Button read = (Button) text(activity.getWindow().getDecorView(), "Read this combined image locally");
                    assertNotNull(read); assertFalse("Model reading must wait for panorama inspection", read.isEnabled());
                    CheckBox joinedReview = (CheckBox) text(activity.getWindow().getDecorView(), "The joined text is complete and the seams are readable.");
                    joinedReview.setChecked(true); assertEquals(ExtractModels.installed(activity), read.isEnabled());
                    Method review = ExtractionLabActivity.class.getDeclaredMethod("showReview", List.class); review.setAccessible(true);
                    review.invoke(activity, List.of("Synthetic timing/OCR", "Synthetic timing/OCR"));
                    View root = activity.getWindow().getDecorView();
                    EditText strength = (EditText) described(root, "Printed strength (preserve decimals and units)"); assertNotNull(strength);
                    assertEquals("2.5 mg", strength.getText().toString());
                    Button use = (Button) text(root, "Use reviewed details"); assertFalse(use.isEnabled());
                    strength.setText("2.5 mg"); CheckBox confirmation = (CheckBox) text(root, "I checked the name, strength and complete directions against the source label.");
                    confirmation.setChecked(true); assertTrue(use.isEnabled());
                    strength.setText("25 mg"); assertFalse("Edits revoke previous confirmation", confirmation.isChecked()); assertFalse(use.isEnabled());
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            scenario.moveToState(Lifecycle.State.CREATED);
            assertTrue("First source photo must be recycled on background", first.isRecycled()); assertTrue(second.isRecycled());
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(activity -> {
                try { assertTrue(images(activity).isEmpty()); assertTrue(drafts(activity).isEmpty()); }
                catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                assertNull("Review fields must not return after background", described(activity.getWindow().getDecorView(), "Product name"));
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                try { assertTrue(images(activity).isEmpty()); assertTrue(drafts(activity).isEmpty()); }
                catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
        } finally {
            if (!first.isRecycled()) first.recycle(); if (!second.isRecycled()) second.recycle();
            if (hadPermission) instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.CAMERA);
        }
        try (DoseStore store = new DoseStore(context)) { assertEquals("Experiment must not save a medication automatically", before, store.medications().size()); }
    }
    @SuppressWarnings("unchecked") private static ArrayList<Bitmap> images(ExtractionLabActivity activity) throws ReflectiveOperationException {
        Field field = ExtractionLabActivity.class.getDeclaredField("images"); field.setAccessible(true); return (ArrayList<Bitmap>) field.get(activity);
    }
    @SuppressWarnings("unchecked") private static ArrayList<LabelDraft> drafts(ExtractionLabActivity activity) throws ReflectiveOperationException {
        Field field = ExtractionLabActivity.class.getDeclaredField("drafts"); field.setAccessible(true); return (ArrayList<LabelDraft>) field.get(activity);
    }
    private static View described(View root, String value) {
        if (root.getContentDescription() != null && value.contentEquals(root.getContentDescription())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = described(((ViewGroup) root).getChildAt(i), value); if (found != null) return found;
        }
        return null;
    }
    private static View text(View root, String value) {
        if (root instanceof TextView && value.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = text(((ViewGroup) root).getChildAt(i), value); if (found != null) return found;
        }
        return null;
    }
}
