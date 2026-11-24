package com.dosecompanion.app;

import android.content.Context;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.dosecompanion.core.LabelDraft;
import java.io.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ExtractionContractTest {
    @Test public void cancellingBeforeReadDiscardsRatherThanInventingAnAnswer() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ExtractRuntime runtime = new ExtractRuntime(context)) {
            runtime.cancel();
            try { runtime.answer(new byte[]{1, 2, 3}); fail("Cancelled reading accepted"); }
            catch (IOException expected) { /* No model or placeholder output is used. */ }
        }
    }
    @Test public void modelInputIsBoundedWithoutRecyclingReviewSource() {
        android.graphics.Bitmap source = android.graphics.Bitmap.createBitmap(2304, 1536, android.graphics.Bitmap.Config.ARGB_8888);
        byte[] jpeg = ExtractInput.jpeg(source);
        android.graphics.Bitmap decoded = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        try {
            assertTrue(decoded.getWidth() <= 1920 && decoded.getHeight() <= 1920 && (long) decoded.getWidth() * decoded.getHeight() <= 1800000);
            assertFalse("The source must remain available for human review", source.isRecycled());
            assertEquals(2304, source.getWidth());
        } finally { decoded.recycle(); source.recycle(); java.util.Arrays.fill(jpeg, (byte) 0); }
    }
    @Test public void acceptsOnlyCompleteStrictFlatJsonWithoutInventedFields() throws Exception {
        LabelDraft draft = ExtractJson.parse("{\"product_name\":\"Loniten\",\"strength\":\"2.5 mg\",\"directions\":\"Prendre 1 comprimé par jour.\"}");
        assertEquals("2.5 mg", draft.strength); assertEquals("Prendre 1 comprimé par jour.", draft.directions);
        assertEquals("", ExtractJson.parse("{\"product_name\":null,\"strength\":null,\"directions\":null}").name);
        String[] invalid = {"", "null", "[]", "{}", "{\"product_name\":\"X\"}",
            "{\"product_name\":\"X\",\"strength\":\"2.5 mg\",\"directions\":null,\"directions\":\"daily\"}",
            "{\"product_name\":\"X\",\"strength\":2.5,\"directions\":null}",
            "{\"product_name\":\"X\",\"strength\":\"2.5 mg\",\"directions\":{}}",
            "{\"product_name\":\"X\",\"strength\":null,\"directions\":null,\"patient\":\"X\"}",
            "{\"product_name\":\"X\",\"strength\":null,\"directions\":null} true",
            "```json\n{\"product_name\":null,\"strength\":null,\"directions\":null}\n```",
            "{product_name:null,strength:null,directions:null}", "{".repeat(8193),
            "{\"product_name\":\"" + "x".repeat(121) + "\",\"strength\":null,\"directions\":null}"};
        for (String output : invalid) {
            try { ExtractJson.parse(output); fail("Invalid JSON accepted"); }
            catch (IOException expected) { /* fail closed */ }
        }
        assertFalse("The model must not receive target prescription answers", ExtractRuntime.PROMPT.contains("Loniten"));
        assertFalse(ExtractRuntime.PROMPT.contains("2.5"));
    }
    @Test public void invalidImportDoesNotReplaceInstalledWeightsOrLeavePartialFiles() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Use an isolated package", context.getPackageName().endsWith(".localexperiment") || context.getPackageName().endsWith(".remindertest"));
        File invalid = new File(context.getCacheDir(), "invalid-model-test.txt");
        long before = ExtractModels.file(context, 0).length();
        try {
            try (FileOutputStream out = new FileOutputStream(invalid)) { out.write(new byte[]{1, 2, 3}); }
            try { ExtractModels.importFile(context, Uri.fromFile(invalid), 0); fail("Wrong model imported"); }
            catch (IOException expected) { /* exact hash contract */ }
            assertEquals(before, ExtractModels.file(context, 0).length());
            assertFalse(new File(context.getNoBackupFilesDir(), "extract-import.partial").exists());
        } finally { assertTrue(invalid.delete()); }
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
                context.checkSelfPermission("android.permission.INTERNET"));
    }
}
