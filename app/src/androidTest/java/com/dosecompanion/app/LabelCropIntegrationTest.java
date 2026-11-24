package com.dosecompanion.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.dosecompanion.core.LabelAgreement;
import com.dosecompanion.core.ProductLabel;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LabelCropIntegrationTest {
    @Test public void centralGuideExcludesBackgroundTextBeforeOcr() throws Exception {
        Bitmap source = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(source); canvas.drawColor(Color.LTGRAY);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.GRAY);
        for (int y = 0; y < 768; y += 15) canvas.drawLine(0, y, 768, y + 60, paint);
        paint.setColor(Color.BLACK); paint.setTextSize(50); canvas.drawText("OtherDrug 25 mg", 80, 75, paint);
        paint.setColor(Color.WHITE); canvas.drawRect(65, 120, 703, 650, paint);
        paint.setColor(Color.BLACK); paint.setTextSize(70); canvas.drawText("Loniten", 140, 310, paint);
        paint.setTextSize(58); canvas.drawText("2.5 mg", 140, 430, paint);
        Bitmap crop = CameraImages.centerCrop(source, 768, 768);
        try (TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)) {
            assertTrue(CameraImages.hasContrast(crop));
            var text = Tasks.await(recognizer.process(InputImage.fromBitmap(crop, 0)), 15, TimeUnit.SECONDS);
            assertFalse("Background decoy must be outside both readers' crop", text.getText().contains("OtherDrug"));
            ProductLabel reading = CameraImages.read(text);
            assertEquals(LabelAgreement.Result.MATCH, LabelAgreement.compare(reading, new ProductLabel("Loniten", "2.5 mg"), null));
        } finally { crop.recycle(); }
        Bitmap blank = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
        blank.eraseColor(Color.WHITE);
        try { assertFalse(CameraImages.hasContrast(blank)); } finally { blank.recycle(); }
    }
}
