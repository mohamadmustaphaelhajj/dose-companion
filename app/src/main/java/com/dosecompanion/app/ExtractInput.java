package com.dosecompanion.app;

import android.graphics.Bitmap;

/** Bound vision compute before JPEG encoding; keep the original selected photo for human review/OCR. */
final class ExtractInput {
    static final int MAX_EDGE = 1920;
    static byte[] jpeg(Bitmap source) {
        int edge = Math.max(source.getWidth(), source.getHeight());
        double factor = Math.min(1.0, Math.min((double) MAX_EDGE / edge, Math.sqrt(1800000.0 / ((long) source.getWidth() * source.getHeight()))));
        if (factor >= 1.0) return CameraImages.jpeg(source);
        Bitmap scaled = Bitmap.createScaledBitmap(source, Math.max(1, (int) (source.getWidth() * factor)),
                Math.max(1, (int) (source.getHeight() * factor)), true);
        try { return CameraImages.jpeg(scaled); } finally { scaled.recycle(); }
    }
}
