package com.dosecompanion.app;

import android.content.Context;
import android.graphics.Bitmap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LabelCancellationTest {
    @Test public void cancellationDiscardsActiveNativeReadAndClosesOnWorker() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Assume.assumeTrue(LocalModel.installed(context));
        Bitmap image = LabelVisionIntegrationTest.fixture("Synthetic", "2.5 mg", false);
        byte[] bytes;
        try { bytes = CameraImages.jpeg(image); } finally { image.recycle(); }
        ExecutorService worker = Executors.newSingleThreadExecutor();
        LocalVisionReader reader = new LocalVisionReader(context);
        Future<Boolean> result = worker.submit(() -> {
            try (LocalVisionReader ignored = reader) { reader.answer(bytes); return false; }
            catch (IOException | RuntimeException cancelled) { return true; }
            finally { Arrays.fill(bytes, (byte) 0); }
        });
        try {
            Field active = LocalVisionReader.class.getDeclaredField("active"); active.setAccessible(true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            while (active.get(reader) == null && !result.isDone() && System.nanoTime() < deadline) Thread.sleep(20);
            assertNotNull("The test must reach a real native conversation", active.get(reader));
            reader.cancel();
            assertTrue("An in-flight cancelled read must not return accepted fields", result.get(20, TimeUnit.SECONDS));
            assertArrayEquals(new byte[bytes.length], bytes);
        } finally { reader.cancel(); worker.shutdown(); assertTrue(worker.awaitTermination(20, TimeUnit.SECONDS)); }
    }
}
