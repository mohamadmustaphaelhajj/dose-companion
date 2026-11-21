package com.dosecompanion.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.dosecompanion.core.LabelMatcher;
import com.dosecompanion.core.LabelStability;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

/** Synthetic bitmap stays in memory; this exercises actual bundled OCR, not mocked OCR text. */
@RunWith(AndroidJUnit4.class)
public class LabelOcrIntegrationTest {
    @Test public void readsFrenchSupplementWithMarketingAndCount() throws Exception {
        String text = readLines("COEUR ET CERVEAU", "Triple concentration", "Oméga-3", "900 mg",
                "Une par jour", "120 gélules", "PRIME 20 % DE PLUS");
        assertEquals(LabelMatcher.Result.MATCH, new LabelMatcher("Omega 3", "900 mg").compare(text));
        assertEquals(LabelMatcher.Result.CONFLICT, new LabelMatcher("Omega 3", "90 mg").compare(text));
    }

    @Test public void combinesSeparateOcrViewsAndLatchesCompletion() throws Exception {
        LabelMatcher matcher = new LabelMatcher("Omega 3", "900 mg");
        LabelMatcher.Reading name = matcher.read(readLines("Triple concentration", "Oméga-3"));
        LabelMatcher.Reading strength = matcher.read(readLines("900 mg", "120 gélules", "20 % DE PLUS"));
        assertTrue(name.nameSeen);
        assertEquals(LabelMatcher.Strength.MATCH, strength.strength);
        LabelStability session = new LabelStability();
        for (int i = 0; i < 3; i++) assertFalse(session.observe(name, i * 300));
        assertFalse(session.observe(strength, 1000));
        assertFalse(session.observe(strength, 1300));
        assertTrue(session.observe(strength, 1600));
        assertTrue(session.observe(matcher.read(""), 1700));
    }

    private String readLines(String... lines) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(1600, 140 + lines.length * 110, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(70);
        for (int i = 0; i < lines.length; i++) canvas.drawText(lines[i], 70, 120 + i * 110, paint);
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        try {
            return Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                    30, TimeUnit.SECONDS).getText();
        } finally { recognizer.close(); bitmap.recycle(); }
    }

    @Test public void readsSyntheticLabelAndRejectsWrongStrength() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(1400, 600, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(90);
        canvas.drawText("EXAMPLE MEDICINE", 80, 180, paint);
        canvas.drawText("500 mg", 80, 330, paint);
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        try {
            String text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                    30, TimeUnit.SECONDS).getText();
            assertEquals(LabelMatcher.Result.MATCH, new LabelMatcher("Example Medicine", "500 mg").compare(text));
            assertEquals(LabelMatcher.Result.CONFLICT, new LabelMatcher("Example Medicine", "50 mg").compare(text));
        } finally { recognizer.close(); bitmap.recycle(); }
    }
}
