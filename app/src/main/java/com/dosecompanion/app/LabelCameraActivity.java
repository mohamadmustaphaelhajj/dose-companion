package com.dosecompanion.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Size;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.camera.core.*;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.dosecompanion.core.*;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Three explicit review steps; no image files, automatic dosage inference or taken-dose writes. */
public final class LabelCameraActivity extends ComponentActivity {
    static final String ENROLL = "enroll";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextRecognizer recognizer;
    private ProcessCameraProvider camera;
    private Camera activeCamera;
    private ImageAnalysis analysis;
    private PreviewView preview;
    private LabelOverlay overlay;
    private View frozen;
    private Bitmap latestBitmap;
    private TextView title, status, progress, reading;
    private Button permission, review, section;
    private ScanSequence sequence;
    private ProductLabel profile;
    private String medicationId, candidate = "", directionsParts = "";
    private boolean enrollment, ready, resumed, processing, destroyed, binding, reviewing, finishedCheck;
    private int generation;
    private long lastFrame, scanSince;
    private AlertDialog reviewDialog;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        enrollment = getIntent().getBooleanExtra(ENROLL, false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(10)); root.setFitsSystemWindows(true);
        root.setBackgroundColor(0xFFF5F6F0); setContentView(root);
        title = label("Loading prescription…", 23); root.addView(title);
        status = label("Each field needs your review and confirmation.", 14); root.addView(status);
        FrameLayout cameraBox = new FrameLayout(this);
        preview = new PreviewView(this); preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        preview.setContentDescription("Live label view; tap the current field to focus.");
        preview.setOnClickListener(v -> {});
        preview.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP && activeCamera != null) {
                v.performClick();
                try { activeCamera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(
                    preview.getMeteringPointFactory().createPoint(e.getX(), e.getY()),
                    FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE).setAutoCancelDuration(3, TimeUnit.SECONDS).build()); }
                catch (RuntimeException closing) { /* Camera closed during the tap. */ }
            }
            return true;
        });
        cameraBox.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        frozen = new View(this) {
            @Override protected void onDraw(Canvas canvas) {
                if (latestBitmap != null && !latestBitmap.isRecycled()) {
                    canvas.drawColor(Color.BLACK);
                    canvas.drawBitmap(latestBitmap, null, LabelOverlay.map(new RectF(0, 0, 1, 1), getWidth(), getHeight()), null);
                }
            }
        };
        frozen.setVisibility(View.GONE); cameraBox.addView(frozen, new FrameLayout.LayoutParams(-1, -1));
        overlay = new LabelOverlay(this); cameraBox.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        root.addView(cameraBox, new LinearLayout.LayoutParams(-1, 0, 1));
        reading = label("Read: waiting for text", 16); reading.setMaxLines(3); root.addView(reading);
        progress = label("", 13); progress.setMaxLines(3); root.addView(progress);
        permission = button(root, "Allow camera", () -> requestPermissions(new String[]{Manifest.permission.CAMERA}, 52));
        review = button(root, "Read and review this field", this::reviewField); review.setEnabled(false);
        section = button(root, "Add visible directions section", this::addSection); section.setVisibility(View.GONE);
        button(root, "Rescan this step", this::retryStep);
        button(root, "Start again with a different bottle", () -> {
            sequence = new ScanSequence(profile); finishedCheck = false; retryStep();
        });
        button(root, "Return without saving", this::finish);
        root.addView(label("Images stay in memory. Scanning never records a dose as taken.", 11));
        if (enrollment) {
            ProductLabel entered = new ProductLabel(getIntent().getStringExtra("expected_name"), getIntent().getStringExtra("expected_strength"));
            if (entered.complete()) profile = entered;
            initializeSequence(state);
        } else worker.execute(() -> {
            try (DoseStore store = new DoseStore(this)) {
                DoseSlot selected = ReminderEngine.slotFromIntent(store, getIntent());
                boolean valid = selected != null && !selected.medication.archived && store.status(selected) != DoseRules.Status.TAKEN;
                runOnUiThread(() -> {
                    if (destroyed) return;
                    if (!valid) { status.setText("This dose is unavailable or already confirmed."); return; }
                    medicationId = selected.medication.id;
                    profile = new ProductLabel(selected.medication.name, selected.medication.strength);
                    initializeSequence(state);
                });
            }
        });
    }
    private void initializeSequence(Bundle state) {
        sequence = new ScanSequence(profile);
        if (state != null) {
            try {
                for (String key : new String[]{"confirmed_name", "confirmed_strength", "confirmed_directions"}) {
                    String value = state.getString(key, ""); if (value.isEmpty()) break; sequence.confirm(value);
                }
            } catch (IllegalArgumentException changedProfile) { sequence = new ScanSequence(profile); }
        }
        ready = true; renderStep();
        if (sequence.step() == ScanSequence.Step.COMPLETE) showSummary(); else startCamera();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        if (sequence != null) {
            state.putString("confirmed_name", sequence.name()); state.putString("confirmed_strength", sequence.strength());
            state.putString("confirmed_directions", sequence.directions());
        }
        super.onSaveInstanceState(state);
    }
    @Override public void onResume() {
        super.onResume(); resumed = true;
        if (ready && sequence.step() == ScanSequence.Step.COMPLETE && reviewDialog == null) showSummary();
        else { if (ready) renderStep(); startCamera(); }
    }
    @Override public void onPause() {
        resumed = false; generation++; reviewing = false;
        if (reviewDialog != null) { reviewDialog.dismiss(); reviewDialog = null; }
        stopCamera(); clearPending(); super.onPause();
    }
    @Override public void onDestroy() {
        destroyed = true; if (!processing) recognizer.close(); worker.shutdown(); super.onDestroy();
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 52 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else if (code == 52) {
            status.setText("Camera access was denied. The journal remains available.");
            permission.setText("Camera permission settings");
            permission.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()))));
        }
    }

    private void startCamera() {
        if (!resumed || destroyed || finishedCheck || reviewing || !ready || camera != null || binding) return;
        if (preview.getWidth() == 0 || preview.getHeight() == 0) { preview.post(this::startCamera); return; }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        permission.setVisibility(View.GONE); binding = true;
        final int session = generation;
        var future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            binding = false;
            if (!resumed || destroyed) return;
            if (session != generation) { startCamera(); return; }
            try {
                camera = future.get();
                if (!camera.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    camera = null; status.setText("A rear camera is required for this check."); return;
                }
                Preview feed = new Preview.Builder().build(); feed.setSurfaceProvider(preview.getSurfaceProvider());
                analysis = new ImageAnalysis.Builder().setResolutionSelector(new ResolutionSelector.Builder()
                        .setResolutionStrategy(new ResolutionStrategy(new Size(1920, 1440),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(ContextCompat.getMainExecutor(this), this::analyze);
                var viewport = preview.getViewPort();
                if (viewport == null) { camera = null; preview.post(this::startCamera); return; }
                activeCamera = camera.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        new UseCaseGroup.Builder().setViewPort(viewport).addUseCase(feed).addUseCase(analysis).build());
                scanSince = SystemClock.elapsedRealtime();
                renderStep();
            } catch (Exception failure) { stopCamera(); guidance("Camera unavailable. Return to the journal and try again."); }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyze(ImageProxy frame) {
        long now = SystemClock.elapsedRealtime();
        if (!resumed || reviewing || finishedCheck || processing || now - lastFrame < 350) { frame.close(); return; }
        if (now - scanSince > 90_000) { frame.close(); stopCamera(); clearPending(); status.setText("This step timed out. Tap Rescan this step; confirmed fields are kept."); return; }
        processing = true; lastFrame = now; final int session = generation;
        Bitmap bitmap;
        try { bitmap = CameraImages.crop(frame, preview.getWidth(), preview.getHeight()); }
        catch (RuntimeException unavailable) { frame.close(); processing = false; return; }
        frame.close(); final boolean[] retained = {false};
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnSuccessListener(text -> {
                if (!resumed || destroyed || session != generation || reviewing) return;
                ScanSequence.Step step = sequence.step();
                overlay.update(text, bitmap.getWidth(), bitmap.getHeight(), profile, step);
                ProductLabel value = CameraImages.read(text, profile);
                String observed;
                if (step == ScanSequence.Step.NAME) {
                    observed = value.name;
                    if (observed.isEmpty()) observed = CameraImages.read(text).name;
                } else if (step == ScanSequence.Step.STRENGTH) {
                    ProductLabel strengthView = value.strength.isEmpty() && !value.conflict ? CameraImages.read(text) : value;
                    observed = strengthView.conflict ? "" : strengthView.strength;
                }
                else observed = text.getText().trim();
                candidate = observed;
                reading.setText(observed.isEmpty() ? "Read: unclear — center this field and tap to focus" : "Read: " + observed);
                review.setEnabled(!observed.isEmpty() || !directionsParts.isEmpty());
                section.setEnabled(!observed.isEmpty());
                if (latestBitmap != null) latestBitmap.recycle(); latestBitmap = bitmap; retained[0] = true;
            }).addOnFailureListener(error -> {
                if (resumed && session == generation) { candidate = ""; review.setEnabled(!directionsParts.isEmpty()); reading.setText("Couldn't read this view. Check focus and lighting."); }
            }).addOnCompleteListener(task -> {
                if (!retained[0]) bitmap.recycle(); processing = false; if (destroyed) recognizer.close();
            });
        } catch (RuntimeException unavailable) { bitmap.recycle(); processing = false; }
    }
    private void addSection() {
        if (sequence == null || sequence.step() != ScanSequence.Step.DIRECTIONS || candidate.isEmpty()) return;
        String joined = ScanSequence.appendSection(directionsParts, candidate);
        if (joined.length() > 2000) { status.setText("Review these sections before adding more; the limit is 2,000 characters."); return; }
        directionsParts = joined; review.setEnabled(true);
        scanSince = SystemClock.elapsedRealtime();
        progress.setText("Collected directions (not confirmed): " + directionsParts);
        status.setText("Section added. Rotate to the next part, or review and confirm the full wording.");
    }
    private void reviewField() {
        if (sequence == null || reviewing) return;
        String observed = sequence.step() == ScanSequence.Step.DIRECTIONS ? ScanSequence.appendSection(directionsParts, candidate) : candidate;
        if (observed.isEmpty()) return;
        reviewing = true; generation++; overlay.freeze(); stopCamera();
        frozen.setVisibility(View.VISIBLE); frozen.invalidate();
        EditText edit = new EditText(this); edit.setText(observed); edit.setTextSize(18);
        edit.setSelectAllOnFocus(false); edit.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        edit.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(sequence.step() == ScanSequence.Step.DIRECTIONS ? 2000 : 120)});
        edit.setMaxLines(5);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Review " + fieldName())
                .setMessage(sequence.step() == ScanSequence.Step.DIRECTIONS
                    ? "Review the joined OCR sections below. Keep only dosing instructions; correct missing or repeated words against the bottle. Quantity and schedule are set separately."
                    : "OCR read:\n" + observed + "\n\nCompare with the bottle. Correct only what you can read yourself. Confirmation is your review, not automatic model verification.")
                .setView(edit).setNegativeButton("Scan this step again", (d, w) -> retryStep())
                .setPositiveButton("Confirm and next", null).setOnCancelListener(d -> retryStep()).create();
        reviewDialog = dialog;
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                sequence.confirm(edit.getText().toString()); dialog.dismiss(); reviewDialog = null;
                reviewing = false; clearPending(); renderStep();
                if (sequence.step() == ScanSequence.Step.COMPLETE) showSummary(); else startCamera();
            } catch (IllegalArgumentException invalid) { edit.setError(invalid.getMessage()); }
        });
    }
    private void showSummary() {
        finishedCheck = true; stopCamera(); clearPending();
        reviewDialog = new AlertDialog.Builder(this).setTitle("Three fields reviewed")
                .setMessage(sequence.name() + " · " + sequence.strength() + "\n\nDirections you confirmed:\n" + sequence.directions()
                    + "\n\nQuantity and reminder times are set separately. This has not recorded a dose as taken.")
                .setNegativeButton("Start again", (d, w) -> { sequence = new ScanSequence(profile); finishedCheck = false; retryStep(); })
                .setPositiveButton(enrollment ? "Return details to form" : "Save reviewed directions", (d, w) -> {
                    if (enrollment) {
                        setResult(RESULT_OK, new Intent().putExtra("product_name", sequence.name()).putExtra("strength", sequence.strength())
                            .putExtra("confirmed_directions", sequence.directions()).putExtra("sequence_confirmed", true)); finish();
                    } else {
                        final String confirmedDirections = sequence.directions();
                        status.setText("Saving reviewed directions…");
                        worker.execute(() -> {
                            try (DoseStore store = new DoseStore(this)) {
                                store.saveDirections(medicationId, confirmedDirections);
                                runOnUiThread(() -> { if (!destroyed) finish(); });
                            } catch (RuntimeException failed) {
                                runOnUiThread(() -> { if (!destroyed) { status.setText("Couldn't save the directions. Please try again."); showSummary(); } });
                            }
                        });
                    }
                }).setOnCancelListener(d -> finish()).show();
    }
    private String fieldName() {
        if (sequence == null) return "field";
        switch (sequence.step()) { case NAME: return "product name"; case STRENGTH: return "printed strength"; default: return "dosage directions"; }
    }
    private void renderStep() {
        if (sequence == null) return;
        title.setText("Step " + Math.min(3, sequence.step().ordinal() + 1) + " of 3 · " + fieldName());
        status.setText(sequence.step() == ScanSequence.Step.DIRECTIONS
            ? "Point at the instructions. Add visible sections as you rotate, then review the complete wording. Do not infer missing words."
            : "Point at the " + fieldName() + ". Tap to focus. Read and review when it is visible.");
        section.setVisibility(sequence.step() == ScanSequence.Step.DIRECTIONS ? View.VISIBLE : View.GONE);
        progress.setText("Name: " + (sequence.name().isEmpty() ? "pending" : "confirmed")
            + " · Strength: " + (sequence.strength().isEmpty() ? "pending" : "confirmed")
            + " · Directions: " + (sequence.directions().isEmpty() ? "pending" : "confirmed"));
    }
    private void stopCamera() {
        if (overlay != null && !reviewing) overlay.clear();
        if (analysis != null) analysis.clearAnalyzer(); if (camera != null) camera.unbindAll();
        camera = null; activeCamera = null;
    }
    private void clearPending() {
        candidate = ""; directionsParts = "";
        if (latestBitmap != null) { latestBitmap.recycle(); latestBitmap = null; }
        if (frozen != null) frozen.setVisibility(View.GONE);
        if (overlay != null) overlay.clear();
        if (review != null) review.setEnabled(false);
        if (reading != null) reading.setText("Read: waiting for text");
    }
    private void retryStep() {
        generation++; reviewing = false; stopCamera(); clearPending(); renderStep(); startCamera();
    }
    private void guidance(String value) { status.setText(value); }
    private Button button(LinearLayout root, String text, Runnable click) {
        Button button = new Button(this); button.setText(text); button.setOnClickListener(v -> click.run()); root.addView(button); return button;
    }
    private TextView label(String text, int size) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(Color.rgb(25, 52, 45));
        view.setPadding(0, dp(4), 0, dp(4)); return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
