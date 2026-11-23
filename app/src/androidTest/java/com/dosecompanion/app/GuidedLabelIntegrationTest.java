package com.dosecompanion.app;
import android.graphics.Bitmap;
import android.graphics.RectF;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.dosecompanion.core.ProductLabel;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class GuidedLabelIntegrationTest {
    @Test public void searchesActualOcrAndPreservesWrongStrength() throws Exception {
        ProductLabel profile = new ProductLabel("Loniten", "2.5 mg");
        try (var reader = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)) {
            for (String amount : new String[]{"2.5 mg", "25 mg"}) {
                Bitmap image = LabelVisionIntegrationTest.fixture("Loniten", amount, false);
                try {
                    var text = Tasks.await(reader.process(InputImage.fromBitmap(image, 0)), 15, TimeUnit.SECONDS);
                    ProductLabel result = CameraImages.read(text, profile);
                    assertEquals("loniten", result.name); assertEquals(amount, result.strength);
                    assertTrue(text.getTextBlocks().stream().flatMap(b -> b.getLines().stream()).anyMatch(l -> l.getBoundingBox() != null));
                } finally { image.recycle(); }
            }
        }
    }
    @Test public void mapsAnalysisCropToPreviewGuide() {
        RectF full = LabelOverlay.map(new RectF(0, 0, 1, 1), 1000, 1000);
        assertEquals(80, full.left, .01); assertEquals(140, full.top, .01);
        assertEquals(920, full.right, .01); assertEquals(860, full.bottom, .01);
        RectF center = LabelOverlay.map(new RectF(.5f, .5f, .5f, .5f), 400, 800);
        assertEquals(200, center.left, .01); assertEquals(400, center.top, .01);
    }
}
