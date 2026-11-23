package com.dosecompanion.app;

import android.graphics.Bitmap;

/** Bound vision compute before JPEG encoding; keep the original selected photo for human review/OCR. */
final class ExtractInput {
    static final int MAX_EDGE = 640;
    static byte[] jpeg(Bitmap source) {
        int edge = Math.max(source.getWidth(), source.getHeight());
        if (edge <= MAX_EDGE) return CameraImages.jpeg(source);
        Bitmap scaled = Bitmap.createScaledBitmap(source, Math.max(1, source.getWidth() * MAX_EDGE / edge),
                Math.max(1, source.getHeight() * MAX_EDGE / edge), true);
        try { return CameraImages.jpeg(scaled); } finally { scaled.recycle(); }
    }
}
