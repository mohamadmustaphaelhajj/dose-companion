package com.dosecompanion.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.net.Uri;
import android.view.WindowManager;
import android.view.MotionEvent;
import android.util.Size;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Camera;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.core.content.ContextCompat;
import com.dosecompanion.core.DoseSlot;
import com.dosecompanion.core.DoseRules;
import com.dosecompanion.core.LabelMatcher;
import com.dosecompanion.core.LabelStability;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Frames stay in memory. No image capture, file output, OCR logging or journal writes. */
public final class LabelCameraActivity extends ComponentActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LabelStability stability = new LabelStability();
    private TextRecognizer recognizer;
    private ProcessCameraProvider camera;
    private Camera activeCamera;
    private ImageAnalysis analysis;
    private PreviewView preview;
    private TextView status;
    private TextView progress;
    private Button permission;
    private DoseSlot slot;
    private LabelMatcher matcher;
    private boolean resumed, processing, destroyed, binding, finishedCheck;
    private int generation;
    private long lastFrame;
    private final Runnable expiry = new Runnable() {
        @Override public void run() {
            if (!finishedCheck) renderProgress();
            handler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        finishedCheck = state != null && state.getBoolean("label_matched", false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(16));
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(0xFFF5F6F0);
        setContentView(root);
        TextView title = label("Show the bottle label", 26);
        root.addView(title);
        TextView expected = label("Loading the scheduled medication…", 16);
        root.addView(expected);
        root.addView(label("Check the product name and printed strength. Bottle count and directions "
                + "are not required. Keep one bottle in view and rotate it slowly.", 14));
        preview = new PreviewView(this);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setContentDescription("Live bottle label view. Tap to focus.");
        preview.setOnClickListener(view -> {});
        preview.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP && activeCamera != null) {
                view.performClick();
                try {
                    var point = preview.getMeteringPointFactory().createPoint(event.getX(), event.getY());
                    activeCamera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(point,
                            FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                            .setAutoCancelDuration(3, TimeUnit.SECONDS).build());
                } catch (RuntimeException unavailable) { /* The camera may have closed during the tap. */ }
            }
            return true;
        });
        root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
        status = label("Allow camera access to read the label.", 17);
        status.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status);
        permission = new Button(this);
        permission.setText("Allow camera");
        permission.setOnClickListener(view -> requestPermissions(new String[]{Manifest.permission.CAMERA}, 52));
        root.addView(permission);
        progress = label("Product name: scanning\nPrinted strength: scanning", 16);
        root.addView(progress);
        Button restart = new Button(this);
        restart.setText("Restart · use a different bottle");
        restart.setOnClickListener(view -> restartScan());
        root.addView(restart);
        Button back = new Button(this);
        back.setText("Return without checking");
        back.setOnClickListener(view -> finish());
        root.addView(back);
        root.addView(label("Live processing on this device. Images and label text are not saved.", 12));
        worker.execute(() -> {
            try (DoseStore store = new DoseStore(this)) {
                DoseSlot selected = ReminderEngine.slotFromIntent(store, getIntent());
                boolean valid = selected != null && !selected.medication.archived
                        && store.status(selected) != DoseRules.Status.TAKEN;
                runOnUiThread(() -> {
                    if (destroyed) return;
                    if (!valid) { status.setText("This scheduled dose is unavailable or already confirmed."); return; }
                    slot = selected;
                    matcher = new LabelMatcher(slot.medication.name, slot.medication.strength);
                    expected.setText(slot.medication.name + " · " + slot.medication.strength);
                    if (finishedCheck) completeCheck();
                    else startCamera();
                });
            }
        });
    }

    @Override public void onResume() {
        super.onResume(); resumed = true;
        handler.post(expiry);
        startCamera();
    }

    @Override public void onPause() {
        resumed = false;
        handler.removeCallbacks(expiry);
        stopCamera();
        super.onPause();
    }

    @Override public void onSaveInstanceState(Bundle state) {
        state.putBoolean("label_matched", finishedCheck);
        super.onSaveInstanceState(state);
    }

    @Override public void onDestroy() {
        destroyed = true;
        stopCamera();
        if (!processing) recognizer.close();
        worker.shutdown();
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 52 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else if (code == 52) {
            status.setText("Camera access was denied. You can continue using the journal manually.");
            permission.setText("Camera permission settings");
            permission.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()))));
        }
    }

    private void startCamera() {
        if (!resumed || finishedCheck || slot == null || camera != null || binding) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        permission.setVisibility(android.view.View.GONE);
        binding = true;
        var future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            binding = false;
            if (!resumed || destroyed) return;
            try {
                camera = future.get();
                if (!camera.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    camera = null; status.setText("A rear camera is required for this check."); return;
                }
                Preview feed = new Preview.Builder().build();
                feed.setSurfaceProvider(preview.getSurfaceProvider());
                analysis = new ImageAnalysis.Builder()
                        .setResolutionSelector(new ResolutionSelector.Builder().setResolutionStrategy(
                                new ResolutionStrategy(new Size(1280, 960),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(ContextCompat.getMainExecutor(this), this::analyze);
                activeCamera = camera.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, feed, analysis);
                status.setText("Show the name, then rotate slowly to show its strength. Tap the preview to focus.");
            } catch (Exception failure) {
                stopCamera();
                status.setText("Camera unavailable. Return to the journal and try again.");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
    private void analyze(ImageProxy frame) {
        long now = SystemClock.elapsedRealtime();
        if (!resumed || processing || now - lastFrame < 300 || frame.getImage() == null) { frame.close(); return; }
        processing = true;
        final int session = generation;
        lastFrame = now;
        try {
            InputImage image = InputImage.fromMediaImage(frame.getImage(), frame.getImageInfo().getRotationDegrees());
            recognizer.process(image).addOnSuccessListener(result -> {
                if (!resumed || destroyed || session != generation) return;
                LabelMatcher.Reading reading = matcher.read(result.getText());
                if (stability.observe(reading, SystemClock.elapsedRealtime())) { completeCheck(); return; }
                renderProgress();
                if (!reading.supported) guidance("This strength format is not supported yet. Use the journal manually.");
                else if (reading.strength == LabelMatcher.Strength.CONFLICT) {
                    guidance("A conflicting product strength was read. Scan restarted; check the bottle and saved strength.");
                } else if (stability.nameReady(SystemClock.elapsedRealtime())) {
                    guidance("Product name captured. Slowly rotate to show the printed strength.");
                } else if (stability.strengthReady(SystemClock.elapsedRealtime())) {
                    guidance("Strength captured. Slowly rotate to show the product name.");
                } else guidance("Hold steady over each field, then rotate slowly. Small reading gaps are OK.");
            }).addOnFailureListener(failure -> {
                if (destroyed || session != generation) return;
                if (resumed) {
                    stability.observe(matcher.read(""), SystemClock.elapsedRealtime());
                    renderProgress();
                    guidance("Unable to read this frame. Improve lighting and keep rotating slowly.");
                }
            }).addOnCompleteListener(task -> {
                processing = false;
                frame.close();
                if (destroyed) recognizer.close();
            });
        } catch (RuntimeException failure) { processing = false; frame.close(); stability.reset(); }
    }

    private void stopCamera() {
        generation++;
        stability.reset();
        if (analysis != null) analysis.clearAnalyzer();
        if (camera != null) camera.unbindAll();
        camera = null;
        activeCamera = null;
    }

    private void renderProgress() {
        if (progress == null) return;
        long now = SystemClock.elapsedRealtime();
        String value = "Product name: " + (stability.nameReady(now) ? "✓ captured" : "scanning")
                + "\nPrinted strength: " + (stability.strengthReady(now) ? "✓ captured" : "scanning");
        if (!value.contentEquals(progress.getText())) progress.setText(value);
    }

    private void guidance(String value) {
        if (!value.contentEquals(status.getText())) status.setText(value);
    }

    private void restartScan() {
        finishedCheck = false;
        stopCamera();
        renderProgress();
        guidance("New scan. Keep one bottle in view; show its name and printed strength.");
        startCamera();
    }

    private void completeCheck() {
        finishedCheck = true;
        stopCamera();
        progress.setText("✓ Product name captured\n✓ Printed strength captured");
        status.setTextColor(0xFF176650);
        guidance("Label matched automatically. Camera stopped.");
        new android.app.AlertDialog.Builder(this).setTitle("Bottle label matched")
                .setMessage("The product name and printed strength matched your saved details. "
                        + "The label check completed automatically; no button press was needed. "
                        + "This has not recorded a dose as taken.")
                .setPositiveButton("Return to journal", (dialog, which) -> finish())
                .setNeutralButton("Scan again", (dialog, which) -> restartScan())
                .setOnCancelListener(dialog -> finish()).show();
    }

    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(Color.rgb(25, 52, 45));
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
