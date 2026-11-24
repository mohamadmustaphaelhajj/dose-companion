package com.dosecompanion.app;

import android.graphics.*;
import java.io.IOException;
import java.util.*;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.*;
import org.opencv.features2d.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.calib3d.Calib3d;

/** Register actual overlapping pixels. Never synthesize text or silently concatenate unrelated views. */
final class LabelStitcher {
    static Bitmap assemble(List<Bitmap> views) throws IOException {
        if (views.size() < 2 || views.size() > 3) throw new IOException("Capture two or three overlapping sections.");
        if (!OpenCVLoader.initLocal()) throw new IOException("Image alignment is unavailable on this device.");
        ArrayList<Matrix> transforms = new ArrayList<>(); ArrayList<RectF> bounds = new ArrayList<>();
        transforms.add(new Matrix()); bounds.add(new RectF(0, 0, views.get(0).getWidth(), views.get(0).getHeight()));
        int direction = 0;
        for (int i = 1; i < views.size(); i++) {
            Matrix local = align(views.get(i - 1), views.get(i));
            Matrix global = new Matrix(); global.setConcat(transforms.get(i - 1), local);
            RectF box = new RectF(0, 0, views.get(i).getWidth(), views.get(i).getHeight()); global.mapRect(box);
            RectF prior = bounds.get(i - 1); float delta = box.centerX() - prior.centerX();
            int step = delta > 0 ? 1 : -1;
            float overlap = Math.min(box.right, prior.right) - Math.max(box.left, prior.left);
            float vertical = Math.min(box.bottom, prior.bottom) - Math.max(box.top, prior.top);
            if (Math.abs(delta) < prior.width() * .15f || overlap < Math.min(box.width(), prior.width()) * .2f
                    || vertical < Math.min(box.height(), prior.height()) * .7f || (direction != 0 && direction != step))
                throw new IOException("Views need 25–60% shared label text, in one horizontal direction. Keep the bottle level and the camera at the same distance.");
            direction = step; transforms.add(global); bounds.add(box);
        }
        RectF union = new RectF(bounds.get(0)); for (RectF box : bounds) union.union(box);
        int width = (int) Math.ceil(union.width()), height = (int) Math.ceil(union.height());
        if (width < 1 || height < 1 || width > 4096 || height > 2048 || (long) width * height > 5_000_000)
            throw new IOException("The assembled label is too large. Retake closer, overlapping views.");
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result); canvas.drawColor(Color.WHITE); canvas.translate(-union.left, -union.top);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        for (int i = 0; i < views.size(); i++) {
            canvas.save();
            if (i > 0) {
                RectF a = bounds.get(i - 1), b = bounds.get(i);
                float seam = (Math.max(a.left, b.left) + Math.min(a.right, b.right)) / 2;
                if (direction > 0) canvas.clipRect(seam, union.top, union.right, union.bottom);
                else canvas.clipRect(union.left, union.top, seam, union.bottom);
            }
            canvas.drawBitmap(views.get(i), transforms.get(i), paint); canvas.restore();
        }
        return result;
    }
    private static Matrix align(Bitmap prior, Bitmap next) throws IOException {
        ArrayList<Mat> allocations = new ArrayList<>(); ORB detector = ORB.create(3000);
        BFMatcher matcher = BFMatcher.create(Core.NORM_HAMMING, false);
        List<MatOfDMatch> neighbors = new ArrayList<>();
        try {
            Mat a = new Mat(), b = new Mat(), da = new Mat(), db = new Mat(), mask = new Mat();
            MatOfKeyPoint ka = new MatOfKeyPoint(), kb = new MatOfKeyPoint();
            Collections.addAll(allocations, a, b, da, db, mask, ka, kb);
            Utils.bitmapToMat(prior, a); Utils.bitmapToMat(next, b);
            Imgproc.cvtColor(a, a, Imgproc.COLOR_RGBA2GRAY); Imgproc.cvtColor(b, b, Imgproc.COLOR_RGBA2GRAY);
            detector.detectAndCompute(a, mask, ka, da); detector.detectAndCompute(b, mask, kb, db);
            if (da.empty() || db.empty()) throw new IOException("Not enough sharp shared text. Improve lighting and overlap the photos.");
            matcher.knnMatch(db, da, neighbors, 2);
            KeyPoint[] aa = ka.toArray(), bb = kb.toArray(); ArrayList<org.opencv.core.Point> from = new ArrayList<>(), to = new ArrayList<>();
            HashSet<Integer> used = new HashSet<>();
            for (MatOfDMatch pair : neighbors) {
                DMatch[] candidates = pair.toArray();
                if (candidates.length == 2 && candidates[0].distance < .7f * candidates[1].distance && used.add(candidates[0].trainIdx)) {
                    from.add(bb[candidates[0].queryIdx].pt); to.add(aa[candidates[0].trainIdx].pt);
                }
            }
            if (from.size() < 18) throw new IOException("No reliable overlap found. Include more repeated label text between adjacent views.");
            MatOfPoint2f src = new MatOfPoint2f(), dst = new MatOfPoint2f(); Mat inliers = new Mat();
            Collections.addAll(allocations, src, dst, inliers); src.fromList(from); dst.fromList(to);
            Mat affine = Calib3d.estimateAffinePartial2D(src, dst, inliers, Calib3d.RANSAC, 3, 3000, .995, 10); allocations.add(affine);
            if (affine.empty() || Core.countNonZero(inliers) < Math.max(18, from.size() * .45)) throw new IOException("Shared text could not be aligned confidently. Retake with more overlap.");
            double ax = affine.get(0, 0)[0], ay = affine.get(1, 0)[0], scale = Math.hypot(ax, ay);
            if (scale < .9 || scale > 1.1 || Math.abs(Math.atan2(ay, ax)) > Math.toRadians(8))
                throw new IOException("Keep the camera level and at the same distance while rotating the bottle.");
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -1, maxY = -1;
            for (int i = 0; i < from.size(); i++) if (inliers.get(i, 0)[0] != 0) {
                org.opencv.core.Point p = from.get(i); minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x); minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
            }
            if (maxX - minX < 35 || maxY - minY < 60) throw new IOException("Overlap is too concentrated in one word. Include several shared lines.");
            Matrix result = new Matrix(); result.setValues(new float[]{(float) ax, (float) affine.get(0, 1)[0], (float) affine.get(0, 2)[0],
                    (float) ay, (float) affine.get(1, 1)[0], (float) affine.get(1, 2)[0], 0, 0, 1}); return result;
        } catch (org.opencv.core.CvException failure) { throw new IOException("Image alignment failed. Retake sharp overlapping sections."); }
        finally { for (MatOfDMatch pair : neighbors) pair.release(); for (Mat mat : allocations) mat.release(); detector.clear(); matcher.clear(); }
    }
}
