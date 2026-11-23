package com.dosecompanion.app;

import android.content.Context;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LocalModelIntegrationTest {
    @Test public void invalidImportPreservesInstalledWeightsAndCleansTemporaryFile() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(context.getPackageName().endsWith(".remindertest"));
        File model = LocalModel.file(context);
        long size = model.length(), modified = model.lastModified();
        File invalid = new File(context.getCacheDir(), "synthetic-invalid-model.txt");
        try {
            try (FileOutputStream output = new FileOutputStream(invalid)) {
                output.write("not a model".getBytes(StandardCharsets.UTF_8));
            }
            try { LocalModel.importFile(context, Uri.fromFile(invalid)); fail("Truncated model imported"); }
            catch (IOException expected) { /* A rejected import must preserve working weights. */ }
            assertEquals(size, model.length()); assertEquals(modified, model.lastModified());
            assertFalse(new File(context.getNoBackupFilesDir(), "label-reader.download").exists());
        } finally { assertTrue(invalid.delete()); }
    }
}
