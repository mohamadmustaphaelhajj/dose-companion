package com.dosecompanion.app;

import android.graphics.*;
import java.io.IOException;
import java.util.*;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.*;
import org.opencv.features2d.*;
import org.opencv.imgproc.Imgproc;

/** Worker-owned rotating-object panorama. Keeps strips and tracking features, never a video file. */
final class BottlePanorama implements AutoCloseable {
    private final ArrayDeque<Strip> strips = new ArrayDeque<>();
    private Mat descriptors;
    private KeyPoint[] points;
    private int width, height, frameWidth, band, direction, offsetY;
    private boolean closed;
    private static final class Strip {
        final Bitmap bitmap; final int y;
        Strip(Bitmap bitmap, int y) { this.bitmap = bitmap; this.y = y; }
    }
    String accept(Bitmap frame) throws IOException {
        if (closed) throw new IOException("Capture ended.");
        if (!OpenCVLoader.initLocal()) throw new IOException("Panorama alignment is unavailable.");
        Mat gray = new Mat(), mask = new Mat(), next = new Mat(); MatOfKeyPoint keys = new MatOfKeyPoint();
        ORB detector = ORB.create(1800);
        try {
            Utils.bitmapToMat(frame, gray); Imgproc.cvtColor(gray, gray, Imgproc.COLOR_RGBA2GRAY);
            mask.create(gray.rows(), gray.cols(), CvType.CV_8UC1); mask.setTo(new Scalar(0));
            // Track label text near the bottle's front; ignore the stationary surrounding scene.
            Imgproc.rectangle(mask, new org.opencv.core.Point(frame.getWidth() * .28, frame.getHeight() * .08),
                    new org.opencv.core.Point(frame.getWidth() * .72, frame.getHeight() * .92), new Scalar(255), -1);
            detector.detectAndCompute(gray, mask, keys, next); KeyPoint[] found = keys.toArray();
            if (next.empty() || found.length < 24) return "Center sharp label text in the guide. Pause, tap to focus, or rotate back slightly.";
            if (descriptors == null) {
                frameWidth = frame.getWidth(); height = frame.getHeight(); band = Math.max(40, Math.round(frameWidth * .16f));
                Bitmap first = Bitmap.createBitmap(frame, (frameWidth - band) / 2, 0, band, height);
                // Always own an independent strip, even if a future input happens to equal the band size.
                if (first == frame) first = frame.copy(Bitmap.Config.ARGB_8888, false);
                strips.add(new Strip(first, 0)); width = band;
                descriptors = next; next = null; points = found;
                return "Start captured. Keep the phone and bottle in place; slowly turn only the bottle.";
            }
            if (frame.getWidth() != frameWidth || frame.getHeight() != height) throw new IOException("Camera size changed. Start a new panorama.");
            double[] movement = movement(next, found);
            if (movement == null) return "Tracking lost. Pause or rotate back slightly until the same printed text is visible.";
            int dx = (int) Math.round(movement[0]), dy = (int) Math.round(movement[1]);
            if (Math.abs(dx) < (direction == 0 ? 8 : 4)) return "Rotate the bottle slowly so new text passes through the narrow center strip.";
            if (Math.abs(dx) > band * .7 || Math.abs(dy) > height * .025)
                return "Turn more slowly and keep the bottle level. Rotate back slightly to recover overlap.";
            int step = dx > 0 ? 1 : -1;
            if (direction != 0 && step != direction) return "Keep turning in the original direction. Rotate back to the last captured text.";
            if (Math.abs(offsetY + dy) > height * .06) return "The bottle moved vertically. Keep it centered, or start again.";
            if (width + Math.abs(dx) > Math.min(4096, 4_000_000 / height))
                return "Panorama is full. Tap Finish panorama to inspect it.";
            direction = step; offsetY += dy;
            int count = Math.abs(dx), left = (frameWidth - band) / 2;
            // Add only newly revealed pixels near the front tangent, avoiding curved outer label edges.
            int sourceX = step > 0 ? left + band - count : left;
            Bitmap piece = Bitmap.createBitmap(frame, sourceX, 0, count, height);
            Strip strip = new Strip(piece, offsetY);
            if (step > 0) strips.addLast(strip); else strips.addFirst(strip);
            width += count; descriptors.release(); descriptors = next; next = null; points = found;
            return "Capturing label · " + width + " pixels. Continue slowly; tap Finish panorama after all printed instructions pass the center.";
        } catch (CvException unavailable) { throw new IOException("Could not track this frame. Keep the bottle centered and try again."); }
        finally { gray.release(); mask.release(); keys.release(); if (next != null) next.release(); detector.clear(); }
    }
    private double[] movement(Mat next, KeyPoint[] found) {
        BFMatcher matcher = BFMatcher.create(Core.NORM_HAMMING, false); List<MatOfDMatch> neighbors = new ArrayList<>();
        MatOfPoint2f from = new MatOfPoint2f(), to = new MatOfPoint2f(); Mat inliers = new Mat(), affine = null;
        try {
            matcher.knnMatch(next, descriptors, neighbors, 2);
            ArrayList<org.opencv.core.Point> source = new ArrayList<>(), target = new ArrayList<>(); HashSet<Integer> used = new HashSet<>();
            for (MatOfDMatch pair : neighbors) {
                DMatch[] matches = pair.toArray();
                if (matches.length == 2 && matches[0].distance < matches[1].distance * .7 && used.add(matches[0].trainIdx)) {
                    org.opencv.core.Point current = found[matches[0].queryIdx].pt, prior = points[matches[0].trainIdx].pt;
                    if (Math.abs(prior.x - current.x) > 2 && Math.abs(prior.y - current.y) < height * .05) { source.add(current); target.add(prior); }
                }
            }
            if (source.size() < 14) return null;
            from.fromList(source); to.fromList(target);
            affine = Calib3d.estimateAffinePartial2D(from, to, inliers, Calib3d.RANSAC, 2.5, 1000, .99, 10);
            if (affine.empty() || Core.countNonZero(inliers) < Math.max(12, source.size() * .45)) return null;
            double a = affine.get(0,0)[0], b = affine.get(0,1)[0], c = affine.get(1,0)[0], d = affine.get(1,1)[0];
            if (Math.hypot(a,c) < .96 || Math.hypot(a,c) > 1.04 || Math.abs(Math.atan2(c,a)) > Math.toRadians(3)) return null;
            double x = frameWidth / 2.0, y = height / 2.0;
            return new double[]{a*x + b*y + affine.get(0,2)[0] - x, c*x + d*y + affine.get(1,2)[0] - y};
        } finally { for (MatOfDMatch pair : neighbors) pair.release(); matcher.clear(); from.release(); to.release(); inliers.release(); if (affine != null) affine.release(); }
    }
    Bitmap finish() throws IOException {
        if (closed || strips.size() < 4 || width < Math.max(250, band * 2)) throw new IOException("Too little label was captured. Start again and rotate more slowly until all label text has passed the center.");
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result); canvas.drawColor(Color.WHITE); int x = 0;
        for (Strip strip : strips) { canvas.drawBitmap(strip.bitmap, x, strip.y, null); x += strip.bitmap.getWidth(); }
        return result;
    }
    @Override public void close() {
        closed = true; if (descriptors != null) { descriptors.release(); descriptors = null; }
        points = null; for (Strip strip : strips) strip.bitmap.recycle(); strips.clear();
    }
}
