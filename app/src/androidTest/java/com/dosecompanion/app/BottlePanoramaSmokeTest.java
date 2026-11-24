package com.dosecompanion.app;

import android.graphics.Bitmap;
import org.junit.Test;
import static org.junit.Assert.*;

/** Quick synthetic motion smoke check; no camera capture or model benchmark. */
public class BottlePanoramaSmokeTest {
    @Test public void accumulatesMovingLabelStripsIntoOnePanorama() throws Exception {
        Bitmap source = LabelStitcherTest.label(); Bitmap result = null;
        try (BottlePanorama capture = new BottlePanorama()) {
            for (int offset = 0; offset <= 500; offset += 20) {
                Bitmap frame = Bitmap.createBitmap(source, offset, 0, 850, 640);
                try { capture.accept(frame); } finally { frame.recycle(); }
            }
            result = capture.finish();
            assertTrue("New moving text should extend the panorama", result.getWidth() >= 450);
            assertEquals(640, result.getHeight());
        } finally { if (result != null) result.recycle(); source.recycle(); }
    }
}
