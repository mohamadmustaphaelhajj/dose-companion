package com.dosecompanion.app;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import androidx.camera.core.ImageProxy;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import com.dosecompanion.core.OcrLabelReader;
import com.dosecompanion.core.ProductLabel;
import com.google.mlkit.vision.text.Text;

/** The very same upright central crop is supplied independently to both readers. */
final class CameraImages {
    private CameraImages() {}

    static Bitmap crop(ImageProxy image, int previewWidth, int previewHeight) {
        Bitmap source = image.toBitmap();
        Rect viewport = image.getCropRect();
        Bitmap visible = Bitmap.createBitmap(source, viewport.left, viewport.top, viewport.width(), viewport.height());
        if (visible != source) source.recycle();
        source = visible;
        Matrix matrix = new Matrix(); matrix.postRotate(image.getImageInfo().getRotationDegrees());
        Bitmap upright = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, false);
        if (upright != source) source.recycle();
        return centerCrop(upright, previewWidth, previewHeight);
    }

    /** Takes ownership of the source; the returned bitmap is the caller's responsibility. */
    static Bitmap centerCrop(Bitmap upright, int previewWidth, int previewHeight) {
        float scale = Math.max((float) Math.max(1, previewWidth) / upright.getWidth(),
                (float) Math.max(1, previewHeight) / upright.getHeight());
        int width = Math.min(upright.getWidth(), Math.max(1, Math.round(previewWidth / scale * .84f)));
        int height = Math.min(upright.getHeight(), Math.max(1, Math.round(previewHeight / scale * .72f)));
        Bitmap result = Bitmap.createBitmap(upright, (upright.getWidth() - width) / 2,
                (upright.getHeight() - height) / 2, width, height);
        if (result != upright) upright.recycle();
        return result;
    }

    static ProductLabel read(Text text) {
        return read(text, null);
    }
    static ProductLabel readLine(Text.Line line) {
        return OcrLabelReader.read(java.util.List.of(new OcrLabelReader.Line(line.getText(), 1)));
    }
    static ProductLabel read(Text text, ProductLabel profile) {
        ArrayList<OcrLabelReader.Line> lines = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                lines.add(new OcrLabelReader.Line(line.getText(),
                        line.getBoundingBox() == null ? 1 : line.getBoundingBox().height()));
            }
        }
        return profile == null ? OcrLabelReader.read(lines) : com.dosecompanion.core.TargetLabelReader.read(lines, profile);
    }

    static byte[] jpeg(Bitmap bitmap) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, bytes);
        return bytes.toByteArray();
    }

    /** Generic text localization only: retain every detected text line, without looking at values. */
    static byte[] jpegTextCrop(Bitmap bitmap, Text text) {
        Rect bounds = new Rect();
        for (Text.TextBlock block : text.getTextBlocks()) for (Text.Line line : block.getLines()) {
            Rect box = line.getBoundingBox();
            if (box != null) bounds.union(box);
        }
        if (bounds.isEmpty()) return jpeg(bitmap);
        int pad = Math.max(16, Math.round(Math.max(bounds.width(), bounds.height()) * .06f));
        bounds.inset(-pad, -pad);
        if (!bounds.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight())) return jpeg(bitmap);
        Bitmap crop = Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height());
        try { return jpeg(crop); } finally { if (crop != bitmap) crop.recycle(); }
    }

    /** Combine selected views before either final reading; no OCR answers/expected fields are drawn. */
    static Bitmap contactSheet(List<byte[]> images) {
        if (images.isEmpty() || images.size() > 2) throw new IllegalArgumentException("Select one or two views.");
        ArrayList<Bitmap> decoded = new ArrayList<>();
        try {
            int width = 1; float aspectSum = 0;
            for (byte[] image : images) {
                Bitmap bitmap = BitmapFactory.decodeByteArray(image, 0, image.length);
                if (bitmap == null) throw new IllegalArgumentException("Unreadable view.");
                decoded.add(bitmap); width = Math.max(width, bitmap.getWidth());
                aspectSum += (float) bitmap.getHeight() / bitmap.getWidth();
            }
            width = Math.max(1, Math.min(Math.min(width, 1024), (int) (4000 / aspectSum)));
            int height = 16 * (decoded.size() - 1);
            for (Bitmap bitmap : decoded) height += Math.max(1, Math.round((float) bitmap.getHeight() * width / bitmap.getWidth()));
            Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result); canvas.drawColor(Color.WHITE);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            int y = 0;
            for (Bitmap bitmap : decoded) {
                int h = Math.max(1, Math.round((float) bitmap.getHeight() * width / bitmap.getWidth()));
                canvas.drawBitmap(bitmap, null, new Rect(0, y, width, y + h), paint); y += h + 16;
            }
            return result;
        } finally { for (Bitmap bitmap : decoded) bitmap.recycle(); }
    }

    static boolean hasContrast(Bitmap bitmap) {
        int min = 255, max = 0;
        for (int y = 0; y < bitmap.getHeight(); y += 12) {
            for (int x = 0; x < bitmap.getWidth(); x += 12) {
                int pixel = bitmap.getPixel(x, y);
                int grey = (((pixel >> 16) & 255) + ((pixel >> 8) & 255) + (pixel & 255)) / 3;
                min = Math.min(min, grey); max = Math.max(max, grey);
            }
        }
        return max - min >= 35 && max >= 55;
    }
}
