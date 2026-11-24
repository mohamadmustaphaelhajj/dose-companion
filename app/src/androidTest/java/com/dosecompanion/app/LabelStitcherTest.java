package com.dosecompanion.app;

import android.graphics.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LabelStitcherTest {
    static Bitmap label() {
        Bitmap image = Bitmap.createBitmap(1800, 640, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(image); c.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(Color.BLACK); p.setTextSize(42);
        String[] lines = {"DEMO PHARMACY   123 Example Street   Montreal QC", "LONITEN    2.5 mg    Fictional prescription", "Take 1 tablet once daily with water.", "Prendre 1 comprimé par jour.  Label demonstration only.", "Keep in original packaging.    Reference DEMO-83517", "This synthetic label has no patient information.", "Quantity: 30 tablets.    Printed example 2026."};
        for (int i = 0; i < lines.length; i++) c.drawText(lines[i], 40, 75 + i * 75, p);
        return image;
    }
    @Test public void alignsOverlappingSectionsIntoOneImageWithoutDuplicateColumns() throws Exception {
        Bitmap full = label(); Bitmap a = Bitmap.createBitmap(full, 0, 0, 850, 640), b = Bitmap.createBitmap(full, 450, 0, 850, 640), c = Bitmap.createBitmap(full, 950, 0, 850, 640);
        Bitmap joined = null;
        try {
            joined = LabelStitcher.assemble(List.of(a,b,c));
            assertEquals(1800, joined.getWidth(), 8); assertEquals(640, joined.getHeight(), 8);
            // Check actual pixel placement on both sides of the seams, including decimal-bearing text.
            long difference = 0; int count = 0;
            for (int y = 20; y < 610; y += 5) for (int x = 20; x < 1750; x += 5) {
                difference += Math.abs(Color.red(full.getPixel(x,y))-Color.red(joined.getPixel(x,y))); count++;
            }
            assertTrue("Registration must preserve source pixels rather than concatenate duplicate text", difference / (double) count < 15);
            assertFalse(a.isRecycled());
        } finally { if(joined != null) joined.recycle(); a.recycle(); b.recycle(); c.recycle(); full.recycle(); }
    }
    @Test public void actualOcrReadsDecimalAndCompleteSentenceAcrossJoinedViews() throws Exception {
        Bitmap full = label(); Bitmap a = Bitmap.createBitmap(full,0,0,850,640), b = Bitmap.createBitmap(full,450,0,850,640), c = Bitmap.createBitmap(full,950,0,850,640);
        Bitmap joined = null;
        try (var reader = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)) {
            joined = LabelStitcher.assemble(List.of(a,b,c));
            String text = com.google.android.gms.tasks.Tasks.await(reader.process(com.google.mlkit.vision.common.InputImage.fromBitmap(joined,0)),20,java.util.concurrent.TimeUnit.SECONDS).getText().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
            assertTrue("The image must retain the decimal strength", text.contains("2.5 mg"));
            assertTrue("The complete instruction must survive the seam",text.contains("take 1 tablet once daily with water."));
        } finally { if(joined!=null)joined.recycle(); a.recycle(); b.recycle(); c.recycle(); full.recycle(); }
    }
    @Test public void refusesMissingOverlapAndChangingSweepDirection() throws Exception {
        Bitmap full = label(); Bitmap a = Bitmap.createBitmap(full,0,0,850,640), b = Bitmap.createBitmap(full,450,0,850,640), remote = Bitmap.createBitmap(full,1100,0,700,640);
        try {
            for (List<Bitmap> views : List.of(List.of(a,remote),List.of(a,b,a))) {
                try { Bitmap result = LabelStitcher.assemble(views); result.recycle(); fail("Invalid sweep accepted"); }
                catch(IOException expected) { }
            }
        } finally { a.recycle(); b.recycle(); remote.recycle(); full.recycle(); }
    }
    @Test public void refusesBlankAndDuplicateViewsInsteadOfInventingMissingLabel() throws Exception {
        Bitmap blank = Bitmap.createBitmap(850,640,Bitmap.Config.ARGB_8888); blank.eraseColor(Color.WHITE);
        Bitmap full = label(); Bitmap crop = Bitmap.createBitmap(full,0,0,850,640);
        try {
            for (List<Bitmap> views : List.of(List.of(blank,blank),List.of(crop,crop))) {
                try { Bitmap result = LabelStitcher.assemble(views); result.recycle(); fail("Unusable overlap accepted"); }
                catch(IOException expected) { }
            }
        } finally { blank.recycle(); crop.recycle(); full.recycle(); }
    }
}
