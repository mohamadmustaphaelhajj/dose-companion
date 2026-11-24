package com.dosecompanion.app;

import android.graphics.Bitmap;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import com.dosecompanion.core.LabelDraft;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import static org.junit.Assert.*;

/** One real inference on an image reconstructed from overlapping, partial fictional label views. */
public class CombinedExtractionTest {
    @Test public void benchmarkOneReadingOfAssembledLabel() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(context.getPackageName().endsWith(".localexperiment"));
        Assume.assumeTrue(ExtractModels.installed(context));
        Bitmap source = LabelStitcherTest.label();
        List<Bitmap> views = List.of(Bitmap.createBitmap(source,0,0,850,640), Bitmap.createBitmap(source,450,0,850,640), Bitmap.createBitmap(source,950,0,850,640));
        Bitmap joined = null; byte[] jpeg = null;
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        try (ExtractRuntime reader = new ExtractRuntime(context)) {
            joined = LabelStitcher.assemble(views); jpeg = ExtractInput.jpeg(joined);
            long start = SystemClock.elapsedRealtime(); var deadline = timer.schedule(reader::cancel,300,TimeUnit.SECONDS);
            try {
                LabelDraft draft = reader.read(jpeg);
                String report = "COMBINED_BENCH ms=" + (SystemClock.elapsedRealtime()-start) + " joined=" + joined.getWidth()+"x"+joined.getHeight()
                        + " name="+draft.name+" strength="+draft.strength+" directions="+draft.directions;
                android.os.Bundle status = new android.os.Bundle(); status.putString("stream",report+"\n");
                InstrumentationRegistry.getInstrumentation().sendStatus(0,status);
                assertFalse("The assembled label must yield at least one model field",draft.name.isEmpty() && draft.strength.isEmpty() && draft.directions.isEmpty());
            } finally { deadline.cancel(false); }
        } finally {
            timer.shutdownNow(); if (jpeg!=null) Arrays.fill(jpeg,(byte)0); if (joined!=null) joined.recycle(); for(Bitmap image:views) image.recycle(); source.recycle();
        }
    }
}
