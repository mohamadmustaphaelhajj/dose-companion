package com.dosecompanion.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import com.dosecompanion.core.ProductLabel;
import com.dosecompanion.core.TargetLabelReader;
import com.google.mlkit.vision.text.Text;
import java.util.ArrayList;
import java.util.List;

/** Boxes describe current OCR observations, never ingestion or prescribed-dose verification. */
final class LabelOverlay extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Box> boxes = new ArrayList<>();
    private final Runnable expire = this::clear;
    private static final class Box {
        RectF bounds; String caption; int color;
        Box(RectF bounds, String caption, int color) { this.bounds = bounds; this.caption = caption; this.color = color; }
    }
    LabelOverlay(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
    void update(Text text, int width, int height, ProductLabel profile) {
        update(text, width, height, profile, null);
    }
    void freeze() { removeCallbacks(expire); }
    void update(Text text, int width, int height, ProductLabel profile, com.dosecompanion.core.ScanSequence.Step step) {
        boxes.clear(); removeCallbacks(expire);
        for (Text.TextBlock block : text.getTextBlocks()) for (Text.Line line : block.getLines()) {
            if (line.getBoundingBox() == null) continue;
            ProductLabel value = profile == null ? CameraImages.readLine(line)
                    : TargetLabelReader.read(java.util.List.of(new com.dosecompanion.core.OcrLabelReader.Line(line.getText(), 1)), profile);
            if (step == com.dosecompanion.core.ScanSequence.Step.STRENGTH && value.strength.isEmpty()) value = CameraImages.readLine(line);
            String caption = null; int color = Color.CYAN;
            if (profile != null && TargetLabelReader.containsName(line.getText(), profile)) {
                caption = "Name match"; color = Color.GREEN;
            } else if (profile == null && !value.name.isEmpty()) caption = "Name candidate";
            if (!value.strength.isEmpty()) {
                boolean match = profile != null && ProductLabel.strengthKey(value.strength).equals(ProductLabel.strengthKey(profile.strength));
                caption = (caption == null ? "" : caption + " / ") + (match ? "Strength match" : "Strength read");
                color = match ? Color.GREEN : Color.YELLOW;
            }
            if (caption == null && TargetLabelReader.directions(line.getText())) caption = "Directions / quantity read";
            if (step == com.dosecompanion.core.ScanSequence.Step.NAME && caption == null && !CameraImages.readLine(line).name.isEmpty()) caption = "Name read — review";
            if (step == com.dosecompanion.core.ScanSequence.Step.NAME && (caption == null || !caption.contains("Name"))) continue;
            if (step == com.dosecompanion.core.ScanSequence.Step.STRENGTH && value.strength.isEmpty()) continue;
            if (step == com.dosecompanion.core.ScanSequence.Step.DIRECTIONS) { caption = "Directions text — review"; color = Color.CYAN; }
            if (caption != null) {
                RectF r = new RectF(line.getBoundingBox());
                r.left /= width; r.right /= width; r.top /= height; r.bottom /= height;
                boxes.add(new Box(r, caption, color));
            }
        }
        invalidate(); postDelayed(expire, 900);
    }
    void clear() { removeCallbacks(expire); boxes.clear(); invalidate(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2 * getResources().getDisplayMetrics().density);
        paint.setColor(Color.WHITE);
        canvas.drawRect(getWidth() * .08f, getHeight() * .14f, getWidth() * .92f, getHeight() * .86f, paint);
        for (Box box : boxes) {
            RectF r = map(box.bounds, getWidth(), getHeight());
            paint.setStyle(Paint.Style.STROKE); paint.setColor(box.color); canvas.drawRect(r, paint);
            paint.setStyle(Paint.Style.FILL); paint.setTextSize(12 * getResources().getDisplayMetrics().density);
            float y = Math.max(paint.getTextSize(), r.top - 4);
            float w = paint.measureText(box.caption);
            paint.setColor(Color.BLACK); canvas.drawRect(r.left, y - paint.getTextSize(), Math.min(getWidth(), r.left + w + 6), y + 3, paint);
            paint.setColor(box.color); canvas.drawText(box.caption, r.left + 2, y, paint);
        }
    }
    static RectF map(RectF normalized, int width, int height) {
        return new RectF((.08f + .84f * normalized.left) * width, (.14f + .72f * normalized.top) * height,
                (.08f + .84f * normalized.right) * width, (.14f + .72f * normalized.bottom) * height);
    }
}
