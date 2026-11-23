package com.dosecompanion.app;

import android.content.Context;
import android.graphics.*;
import android.os.Debug;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.dosecompanion.core.LabelDraft;
import java.util.Arrays;
import java.util.concurrent.*;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real CPU inference on generated fictional labels. Never reads a user's photos or prescription. */
@RunWith(AndroidJUnit4.class)
public class ExtractionBenchmarkTest {
    @Test public void measureActualPerImageExtractionAndMissingFields() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(context.getPackageName().endsWith(".localexperiment"));
        Assume.assumeTrue("Import both pinned weights to benchmark", ExtractModels.installed(context));
        String[][] cases = {
            {"LONITEN", "2.5 mg", "Take 1 tablet once daily."},
            {"LONITEN", "25 mg", "Take 1 tablet once daily."},
            {"LONITEN", "2,5 mg", "Prendre 1 comprimé par jour."},
            {"", "", "Take 1 tablet once daily."},
            {"", "", ""}
        };
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        int exactNames = 0, exactStrengths = 0, exactDirections = 0, matchingWords = 0, validOutputs = 0;
        int limit = Math.max(1, Math.min(cases.length, Integer.parseInt(InstrumentationRegistry.getArguments().getString("cases", "5"))));
        try (ExtractRuntime reader = new ExtractRuntime(context)) {
            for (int i = 0; i < limit; i++) {
                Bitmap bitmap = fixture(cases[i]); byte[] jpeg;
                try { jpeg = ExtractInput.jpeg(bitmap); } finally { bitmap.recycle(); }
                long started = SystemClock.elapsedRealtime();
                var deadline = timer.schedule(reader::cancel, 180, TimeUnit.SECONDS);
                try {
                    String answer = reader.answer(jpeg); LabelDraft draft;
                    try { draft = ExtractJson.parse(answer); validOutputs++; }
                    catch (java.io.IOException invalid) {
                        System.out.println("EXTRACT_BENCH case=" + i + " invalid_json=true synthetic_output=" + answer.replace('\n', ' '));
                        continue;
                    }
                    boolean n = draft.name.equalsIgnoreCase(cases[i][0]);
                    boolean s = cases[i][1].isEmpty() ? draft.strength.isEmpty() :
                            com.dosecompanion.core.ProductLabel.strengthKey(draft.strength).equals(com.dosecompanion.core.ProductLabel.strengthKey(cases[i][1]));
                    boolean d = draft.directions.equals(cases[i][2]);
                    boolean words = draft.directions.trim().replaceAll("\\s+", " ").equalsIgnoreCase(cases[i][2]);
                    if (words) matchingWords++;
                    if (n) exactNames++; if (s) exactStrengths++; if (d) exactDirections++;
                    Debug.MemoryInfo memory = new Debug.MemoryInfo(); Debug.getMemoryInfo(memory);
                    System.out.println("EXTRACT_BENCH case=" + i + " ms=" + (SystemClock.elapsedRealtime() - started)
                            + " pss_kb=" + memory.getTotalPss() + " name=" + n + " strength=" + s + " directions_verbatim=" + d + " directions_words=" + words
                            + " synthetic_output=" + answer.replace('\n', ' '));
                    // Record model accuracy separately from safety contracts. Human review is always required.
                } finally { deadline.cancel(false); Arrays.fill(jpeg, (byte) 0); }
            }
        } finally { timer.shutdownNow(); }
        System.out.println("EXTRACT_BENCH summary valid_json=" + validOutputs + "/" + limit + " names=" + exactNames + "/" + limit
                + " strengths=" + exactStrengths + "/" + limit + " directions_verbatim=" + exactDirections + "/" + limit + " directions_words=" + matchingWords + "/" + limit);
        assertTrue("The local model must produce at least one usable structured draft", validOutputs > 0);
    }
    private static Bitmap fixture(String[] values) {
        Bitmap image = Bitmap.createBitmap(1000, 660, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image); canvas.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(Color.BLACK); p.setTextSize(32);
        canvas.drawText("DEMO PHARMACY — TEST LABEL", 35, 65, p);
        canvas.drawText("123 Example Street, Montreal QC", 35, 115, p);
        p.setTypeface(Typeface.DEFAULT_BOLD); p.setTextSize(72); canvas.drawText(values[0], 35, 235, p);
        p.setTextSize(62); canvas.drawText(values[1], 35, 325, p);
        p.setTypeface(Typeface.DEFAULT); p.setTextSize(45); canvas.drawText(values[2], 35, 435, p);
        p.setTextSize(30); canvas.drawText("30 tablets     Ref: DEMO-000", 35, 585, p);
        return image;
    }
}
