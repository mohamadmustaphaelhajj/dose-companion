package com.dosecompanion.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Debug;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.dosecompanion.core.LabelAgreement;
import com.dosecompanion.core.ProductLabel;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real inference on synthetic labels only. No real journal or personal images are read or changed. */
@RunWith(AndroidJUnit4.class)
public class LabelVisionIntegrationTest {
    @Test public void strictOutputContractRejectsAmbiguousOrMalformedAnswers() {
        ProductLabel expected = new ProductLabel("Loniten", "2.5 mg");
        ProductLabel valid = VlmOutput.parse("{\"product_name\":\"Loniten\",\"strength\":\"2.50 mg\"}");
        assertEquals(LabelAgreement.Result.MATCH, LabelAgreement.compare(valid, expected, expected));
        assertEquals("Loniten", VlmOutput.parse("{\"product_name\":\"Loniten 2.5 mg\",\"strength\":\"2.50 mg\"}").name);
        assertEquals("Loniten 25 mg", VlmOutput.parse("{\"product_name\":\"Loniten 25 mg\",\"strength\":\"2.5 mg\"}").name);
        assertEquals("", VlmOutput.parse("{\"product_name\":\"2.5 mg\",\"strength\":\"2.5 mg\"}").name);
        String[] invalid = {
            "Here is the answer: {\"product_name\":\"Loniten\",\"strength\":\"2.5 mg\"}",
            "```json\n{\"product_name\":\"Loniten\",\"strength\":\"2.5 mg\"}\n```",
            "{\"product_name\":\"Loniten\",\"strength\":\"25 mg\",\"strength\":\"2.5 mg\"}",
            "{\"product_name\":\"Loniten\",\"strength\":2.5}",
            "{\"product_name\":\"Loniten\",\"strength\":\"2.5 mg\",\"instructions\":\"take more\"}",
            "{\"product_name\":\"Loniten\",\"strength\":\"2.5 mg\"} true",
            "{product_name:'Loniten',strength:'2.5 mg'}",
            "{\"product_name\":\"Loniten\"}", "[]", "null", "", "{".repeat(3000),
            "{\"product_name\":\"Loniten\",\"strength\":\"2.5 mg/5 ml\"}"
        };
        for (String answer : invalid) assertFalse("Invalid output accepted", VlmOutput.parse(answer).complete());
        assertFalse(VlmOutput.parse("{\"product_name\":null,\"strength\":\"2.5 mg\"}").complete());
        assertEquals(LabelAgreement.Result.DISAGREEMENT, LabelAgreement.compare(expected,
                VlmOutput.parse("{\"product_name\":\"Loniten\",\"strength\":\"25 mg\"}"), expected));
    }

    @Test public void benchmarkActualOfflineReadersWithoutExpectedValuesInPrompt() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Assume.assumeTrue("Import pinned model to run real VLM benchmark", LocalModel.installed(context));
        boolean gpu = !"false".equals(InstrumentationRegistry.getArguments().getString("gpu", "true"));
        String[][] fixtures = {{"Loniten", "2.5 mg"}, {"Loniten", "25 mg"},
                {"Oméga-3", "900 mg"}, {"Metformin", "500 mg"}, {"", "2.5 mg"}, {"", ""}};
        int exact = 0, agreements = 0;
        long start = SystemClock.elapsedRealtime();
        try (LocalVisionReader reader = new LocalVisionReader(context, gpu);
             TextRecognizer ocrReader = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)) {
            reader.initialize();
            System.out.println("VLM_BENCH init_ms=" + (SystemClock.elapsedRealtime() - start) + " gpu=" + gpu);
            for (int i = 0; i < fixtures.length; i++) {
                Bitmap image = fixture(fixtures[i][0], fixtures[i][1], i == 2);
                try {
                    ProductLabel expected = new ProductLabel(fixtures[i][0], fixtures[i][1]);
                    var localized = Tasks.await(ocrReader.process(InputImage.fromBitmap(image, 0)), 15, TimeUnit.SECONDS);
                    byte[] selected = CameraImages.jpegTextCrop(image, localized);
                    Bitmap cropped = CameraImages.contactSheet(Arrays.asList(selected));
                    image.recycle(); image = cropped;
                    ProductLabel ocr = CameraImages.read(Tasks.await(ocrReader.process(InputImage.fromBitmap(image, 0)), 15, TimeUnit.SECONDS));
                    long before = SystemClock.elapsedRealtime();
                    String answer = reader.answer(CameraImages.jpeg(image));
                    ProductLabel vision = VlmOutput.parse(answer);
                    LabelAgreement.Result result = LabelAgreement.compare(ocr, vision, expected);
                    boolean correct = expected.complete() && LabelAgreement.compare(vision, expected, null) == LabelAgreement.Result.MATCH;
                    if (correct) exact++;
                    if (i <= 1) assertTrue("Candidate failed the Loniten decimal-strength acceptance check: " + answer, correct);
                    if (result == LabelAgreement.Result.MATCH) agreements++;
                    // Every accepted match must actually equal the synthetic ground truth.
                    if (result == LabelAgreement.Result.MATCH) assertTrue("False agreement on fixture " + i, correct);
                    if (i == 1) assertNotEquals("Decimal-shifted label matched saved 2.5 mg",
                            LabelAgreement.Result.MATCH, LabelAgreement.compare(ocr, vision, new ProductLabel("Loniten", "2.5 mg")));
                    Debug.MemoryInfo memory = new Debug.MemoryInfo(); Debug.getMemoryInfo(memory);
                    System.out.println("VLM_BENCH fixture=" + i + " ms=" + (SystemClock.elapsedRealtime() - before)
                            + " pss_kb=" + memory.getTotalPss() + " exact=" + correct + " gate=" + result
                            + " ocr=" + ocr.name + "/" + ocr.strength + " output=" + answer.replace('\n', ' '));
                } finally { image.recycle(); }
            }
        }
        System.out.println("VLM_BENCH summary exact=" + exact + "/4 agreements=" + agreements + "/4");
    }

    @Test public void readsSeparateViewsTogetherWithoutInventingMissingStrength() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Assume.assumeTrue(LocalModel.installed(context));
        ArrayList<byte[]> views = new ArrayList<>();
        try (TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
             LocalVisionReader reader = new LocalVisionReader(context)) {
            for (String[] fields : new String[][]{{"Loniten", ""}, {"", "2.5 mg"}}) {
                Bitmap image = fixture(fields[0], fields[1], false);
                try {
                    var text = Tasks.await(recognizer.process(InputImage.fromBitmap(image, 0)), 15, TimeUnit.SECONDS);
                    views.add(CameraImages.jpegTextCrop(image, text));
                } finally { image.recycle(); }
            }
            Bitmap sheet = CameraImages.contactSheet(views);
            try {
                ProductLabel ocr = CameraImages.read(Tasks.await(recognizer.process(InputImage.fromBitmap(sheet, 0)), 15, TimeUnit.SECONDS));
                String answer = reader.answer(CameraImages.jpeg(sheet));
                ProductLabel vision = VlmOutput.parse(answer);
                System.out.println("VLM_MULTI output=" + answer.replace('\n', ' ') + " gate="
                        + LabelAgreement.compare(ocr, vision, new ProductLabel("Loniten", "2.5 mg")));
                assertEquals("Readers need joint evidence from curved-label views", LabelAgreement.Result.MATCH,
                        LabelAgreement.compare(ocr, vision, new ProductLabel("Loniten", "2.5 mg")));
                assertNotEquals(LabelAgreement.Result.MATCH, LabelAgreement.compare(ocr, vision, new ProductLabel("Loniten", "25 mg")));
            } finally { sheet.recycle(); }
        } finally { for (byte[] bytes : views) Arrays.fill(bytes, (byte) 0); }
    }

    static Bitmap fixture(String name, String strength, boolean busy) {
        Bitmap bitmap = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.BLACK);
        if (busy) {
            paint.setColor(0xFF006C72); canvas.drawRect(0, 0, 768, 150, paint);
            paint.setColor(Color.WHITE); paint.setTextSize(24); canvas.drawText("COEUR ET CERVEAU", 90, 90, paint);
            paint.setColor(Color.BLACK);
        }
        paint.setTextSize(70); canvas.drawText(name, 80, 290, paint);
        paint.setTextSize(58); canvas.drawText(strength, 80, 410, paint);
        if (!name.isEmpty()) {
            paint.setTextSize(25); canvas.drawText(busy ? "Une par jour" : "Take 1 tablet daily", 80, 530, paint);
            canvas.drawText(busy ? "120 gelules / PRIME 20 % DE PLUS" : "30 tablets", 80, 590, paint);
        }
        return bitmap;
    }
}
