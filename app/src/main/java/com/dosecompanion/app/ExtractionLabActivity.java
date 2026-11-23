package com.dosecompanion.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.os.*;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Size;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.camera.core.*;
import androidx.camera.core.resolutionselector.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.dosecompanion.core.LabelDraft;
import com.dosecompanion.core.ProductLabel;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.*;
import java.util.concurrent.*;

/** Local, per-view transcription experiment. Pixels and unreviewed drafts live only while foregrounded. */
public final class ExtractionLabActivity extends ComponentActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object imagesLock = new Object();
    private final ArrayList<Bitmap> images = new ArrayList<>();
    private final ArrayList<LabelDraft> drafts = new ArrayList<>();
    private PreviewView preview;
    private ProcessCameraProvider provider;
    private androidx.camera.core.Camera camera;
    private ImageCapture capture;
    private LinearLayout root, thumbnails;
    private TextView status;
    private Button take, read, permission, prepare, remove;
    private volatile boolean resumed;
    private boolean busy, capturing, binding, reviewing;
    private volatile int generation;
    private volatile ExtractRuntime runtime;
    private AlertDialog enlarged;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showCapture("Take 2–3 overlapping views. Keep the label central, tap to focus, and rotate slowly between photos.");
    }
    private void screen(String title) {
        ScrollView scroll = new ScrollView(this); root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(16), dp(14), dp(16), dp(16));
        root.setFitsSystemWindows(true); root.setBackgroundColor(0xFFF5F6F0); scroll.addView(root); setContentView(scroll);
        text(root, title, 24);
    }
    private void showCapture(String message) {
        reviewing = false; screen("Local label experiment");
        text(root, "Photograph the name and strength, then the directions. Overlap the photos so you can compare them during review.", 15);
        FrameLayout frame = new FrameLayout(this);
        preview = new PreviewView(this); preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER); frame.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        View guide = new View(this) {
            @Override protected void onDraw(Canvas canvas) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(Color.WHITE); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(2));
                canvas.drawRect(getWidth() * .08f, getHeight() * .14f, getWidth() * .92f, getHeight() * .86f, p);
            }
        }; guide.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); frame.addView(guide, new FrameLayout.LayoutParams(-1, -1));
        preview.setOnClickListener(v -> {});
        preview.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP && camera != null) {
                v.performClick();
                try { camera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(
                    preview.getMeteringPointFactory().createPoint(event.getX(), event.getY())).setAutoCancelDuration(3, TimeUnit.SECONDS).build()); }
                catch (RuntimeException closed) { /* Lifecycle closed the camera. */ }
            } return true;
        });
        root.addView(frame, new LinearLayout.LayoutParams(-1, dp(300)));
        status = text(root, message, 15); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        thumbnails = new LinearLayout(this); root.addView(thumbnails);
        permission = button(root, "Allow camera", () -> requestPermissions(new String[]{Manifest.permission.CAMERA}, 93));
        prepare = button(root, "Import experiment model files", () -> startActivity(new Intent(this, ExtractModelActivity.class)));
        take = button(root, "Capture view", this::takePhoto);
        remove = button(root, "Remove last view", () -> {
            synchronized (imagesLock) { if (!images.isEmpty()) images.remove(images.size() - 1).recycle(); }
            updateCapture();
        });
        read = button(root, "Read selected views locally", this::readViews);
        button(root, "Cancel reading", () -> cancelReading("Reading cancelled. Your photos remain available for another attempt."));
        button(root, "Return without saving", this::finish);
        text(root, "Photos and drafts are discarded when you leave this screen or switch apps. Scanning never logs a dose as taken.", 12);
        updateCapture();
    }
    private void updateCapture() {
        if (reviewing || take == null) return;
        int count; synchronized (imagesLock) { count = images.size(); }
        take.setText(count == 3 ? "3 views captured" : "Capture view " + (count + 1) + " (up to 3)"); take.setEnabled(!busy && !capturing && count < 3 && capture != null);
        remove.setEnabled(!busy && !capturing && count > 0);
        read.setEnabled(!busy && !capturing && count >= 2 && ExtractModels.installed(this));
        prepare.setText(ExtractModels.installed(this) ? "Model files ready · manage" : "Import experiment model files");
        prepare.setVisibility(count == 0 || !ExtractModels.installed(this) ? View.VISIBLE : View.GONE);
        prepare.setEnabled(!busy && !capturing);
        permission.setVisibility(checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ? View.GONE : View.VISIBLE);
        thumbnails.removeAllViews();
        synchronized (imagesLock) { for (int i = 0; i < images.size(); i++) {
            final int index = i; ImageView image = new ImageView(this); image.setImageBitmap(images.get(i));
            image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setContentDescription("Captured view " + (i + 1) + "; tap to inspect");
            image.setOnClickListener(v -> enlarge(index)); thumbnails.addView(image, new LinearLayout.LayoutParams(0, dp(100), 1));
        } }
    }
    private void enlarge(int index) {
        synchronized (imagesLock) {
            if (index >= images.size()) return;
            ImageView image = new ImageView(this); image.setImageBitmap(images.get(index)); image.setAdjustViewBounds(true);
            enlarged = new AlertDialog.Builder(this).setTitle("Source view " + (index + 1)).setView(image)
                    .setPositiveButton("Return", null).show();
        }
    }
    private void startCamera() {
        if (!resumed || reviewing || binding || provider != null || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        if (preview.getWidth() == 0) { preview.post(this::startCamera); return; }
        binding = true; final int session = generation;
        var future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            binding = false;
            if (!resumed || reviewing || session != generation) { if (resumed && !reviewing) startCamera(); return; }
            try {
                ProcessCameraProvider ready = future.get();
                Preview feed = new Preview.Builder().build(); feed.setSurfaceProvider(preview.getSurfaceProvider());
                ImageCapture photo = new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setResolutionSelector(new ResolutionSelector.Builder().setResolutionStrategy(new ResolutionStrategy(
                                new Size(2048, 1536), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()).build();
                var viewport = preview.getViewPort();
                if (viewport == null) { preview.post(this::startCamera); return; }
                camera = ready.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        new UseCaseGroup.Builder().setViewPort(viewport).addUseCase(feed).addUseCase(photo).build());
                provider = ready; capture = photo; updateCapture();
            } catch (Exception unavailable) { status.setText("Camera unavailable. Return and reopen the experiment, or check camera permission in Android settings."); }
        }, ContextCompat.getMainExecutor(this));
    }
    private void stopCamera() {
        if (provider != null) provider.unbindAll(); provider = null; camera = null; capture = null;
    }
    private void takePhoto() {
        if (!resumed || busy || capturing || capture == null || images.size() >= 3) return;
        final int session = generation; final int width = preview.getWidth(), height = preview.getHeight();
        capturing = true; updateCapture(); status.setText("Hold still…");
        capture.takePicture(ContextCompat.getMainExecutor(this), new ImageCapture.OnImageCapturedCallback() {
            @Override public void onCaptureSuccess(ImageProxy image) {
                try {
                    if (!resumed || session != generation) return;
                    Bitmap bitmap = CameraImages.crop(image, width, height);
                    int edge = Math.max(bitmap.getWidth(), bitmap.getHeight());
                    if (edge > 1536) {
                        Bitmap small = Bitmap.createScaledBitmap(bitmap, Math.max(1, bitmap.getWidth() * 1536 / edge), Math.max(1, bitmap.getHeight() * 1536 / edge), true);
                        if (small != bitmap) bitmap.recycle(); bitmap = small;
                    }
                    if (!CameraImages.hasContrast(bitmap)) { bitmap.recycle(); status.setText("The view is too dark or blank. Improve lighting and capture again."); }
                    else {
                        synchronized (imagesLock) { images.add(bitmap); }
                        status.setText("View " + images.size() + " captured. Inspect its thumbnail for readable text, then rotate to the next overlapping section.");
                    }
                } catch (RuntimeException failure) { status.setText("Could not capture this view. Try again."); }
                finally { image.close(); if (session == generation) { capturing = false; updateCapture(); } }
            }
            @Override public void onError(ImageCaptureException failure) {
                if (session == generation && resumed) { capturing = false; status.setText("Capture failed. Try again."); updateCapture(); }
            }
        });
    }
    private void readViews() {
        if (!resumed || busy || images.size() < 2 || !ExtractModels.installed(this)) return;
        busy = true; final int session = ++generation; updateCapture();
        status.setText("Loading the local model. Keep this screen open; the first reading can take a while.");
        final int count = images.size();
        worker.execute(() -> {
            ArrayList<LabelDraft> readings = new ArrayList<>(); ArrayList<String> reports = new ArrayList<>();
            try (ExtractRuntime engine = new ExtractRuntime(this); TextRecognizer ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)) {
                runtime = engine;
                for (int index = 0; index < count; index++) {
                    final int view = index; byte[] jpeg; Bitmap ocrBitmap;
                    synchronized (imagesLock) {
                        if (session != generation || !resumed || index >= images.size()) { engine.cancel(); break; }
                        jpeg = ExtractInput.jpeg(images.get(index)); ocrBitmap = images.get(index).copy(Bitmap.Config.ARGB_8888, false);
                    }
                    main.post(() -> { if (current(session)) status.setText("Reading view " + (view + 1) + " of " + count + " locally…"); });
                    Runnable deadline = () -> { if (current(session)) cancelReading("Reading exceeded three minutes for one view. Try a clearer crop or return to manual setup."); };
                    main.postDelayed(deadline, 180_000); long started = SystemClock.elapsedRealtime();
                    String rawOcr = "OCR unavailable."; LabelDraft draft = new LabelDraft("", "", ""); String outcome;
                    var ocrTask = ocr.process(InputImage.fromBitmap(ocrBitmap, 0));
                    // A timed-out task still owns its pixels until ML Kit reports completion.
                    ocrTask.addOnCompleteListener(workerResult -> ocrBitmap.recycle());
                    try {
                        // Independent baseline; neither these words nor expected prescription details go to the VLM.
                        rawOcr = Tasks.await(ocrTask, 20, TimeUnit.SECONDS).getText();
                    } catch (Exception ignored) { /* An OCR failure does not become a model answer. */ }
                    try { draft = engine.read(jpeg); outcome = "Local draft (" + (SystemClock.elapsedRealtime() - started) / 1000 + " seconds)"; }
                    catch (Exception | LinkageError failure) { outcome = "Local reading failed or returned invalid JSON. No fields accepted for this view."; }
                    finally { Arrays.fill(jpeg, (byte) 0); main.removeCallbacks(deadline); }
                    readings.add(draft); reports.add(outcome + "\n\nIndependent OCR text:\n" + rawOcr);
                    if (!current(session)) break;
                }
            } catch (Exception | LinkageError unavailable) {
                main.post(() -> { if (current(session)) { busy = false; status.setText("Local reader unavailable. Check both model imports and free memory."); updateCapture(); } });
                return;
            } finally { runtime = null; }
            main.post(() -> {
                if (!current(session)) return;
                busy = false; drafts.clear(); drafts.addAll(readings); stopCamera(); showReview(reports);
            });
        });
    }
    private boolean current(int session) { return resumed && !isDestroyed() && session == generation; }
    private void cancelReading(String message) {
        if (!busy) return;
        generation++; ExtractRuntime engine = runtime; if (engine != null) engine.cancel();
        busy = false; status.setText(message); updateCapture();
    }
    private void showReview(List<String> reports) {
        reviewing = true; screen("Review local drafts");
        text(root, "Compare every field with the source photos. The model can mistake a pharmacy name or bottle count for a missing field. Different readings leave a field blank. Missing words are never filled from your saved prescription.", 15);
        for (int i = 0; i < drafts.size(); i++) {
            LabelDraft draft = drafts.get(i); final int index = i;
            text(root, "Source view " + (i + 1), 20);
            ImageView image = new ImageView(this); synchronized (imagesLock) { image.setImageBitmap(images.get(i)); }
            image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setContentDescription("Source view " + (i + 1) + "; tap to inspect");
            image.setOnClickListener(v -> enlarge(index)); root.addView(image, new LinearLayout.LayoutParams(-1, dp(210)));
            text(root, "Name: " + visible(draft.name) + "\nStrength: " + visible(draft.strength) + "\nDirections: " + visible(draft.directions), 16);
            TextView report = text(root, reports.get(i), 13); report.setVisibility(View.GONE);
            button(root, "Show / hide timing and OCR for view " + (i + 1), () -> report.setVisibility(report.getVisibility() == View.GONE ? View.VISIBLE : View.GONE));
        }
        text(root, "Your reviewed transcription", 21);
        EditText name = field("Product name", LabelDraft.unique(drafts, 0), 120);
        EditText strength = field("Printed strength (preserve decimals and units)", LabelDraft.unique(drafts, 1), 80);
        EditText directions = field("Full printed directions — combine visible sections yourself", LabelDraft.unique(drafts, 2), 2000);
        directions.setSingleLine(false); directions.setMinLines(3);
        TextView error = text(root, "", 14);
        CheckBox confirm = new CheckBox(this); confirm.setText("I checked the name, strength and complete directions against the source label."); root.addView(confirm);
        Button use = button(root, "Use reviewed details", () -> {
            if (!confirm.isChecked()) return;
            ProductLabel product = new ProductLabel(name.getText().toString(), strength.getText().toString());
            if (!product.complete() || directions.getText().toString().trim().isEmpty()) {
                error.setText("Enter the printed name, a strength with units, and complete directions before confirming."); return;
            }
            Intent result = new Intent().putExtra("product_name", name.getText().toString().trim())
                    .putExtra("strength", strength.getText().toString().trim()).putExtra("confirmed_directions", directions.getText().toString().trim())
                    .putExtra("local_draft_reviewed", true);
            setResult(RESULT_OK, result); finish();
        });
        use.setEnabled(false); confirm.setOnCheckedChangeListener((v, checked) -> use.setEnabled(checked));
        TextWatcher edits = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { confirm.setChecked(false); }
            @Override public void afterTextChanged(Editable s) {}
        };
        name.addTextChangedListener(edits); strength.addTextChangedListener(edits); directions.addTextChangedListener(edits);
        button(root, "Discard and take new photos", () -> { clearImages(); drafts.clear(); showCapture("Start again with two or three overlapping views."); startCamera(); });
        button(root, "Return without saving", this::finish);
        text(root, "Only these reviewed text fields return to the medication form. Set quantity and reminder times yourself, then explicitly save. No dose has been logged.", 12);
    }
    private static String visible(String value) { return value.isEmpty() ? "Not read" : value; }
    private EditText field(String title, String value, int limit) {
        text(root, title, 15); EditText field = new EditText(this); field.setText(value);
        field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(limit)});
        field.setSaveEnabled(false); field.setSingleLine(true); field.setContentDescription(title); root.addView(field); return field;
    }
    private TextView text(LinearLayout parent, String value, int size) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(Color.DKGRAY);
        text.setPadding(0, dp(5), 0, dp(5)); text.setSaveEnabled(false); parent.addView(text); return text;
    }
    private Button button(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setOnClickListener(v -> action.run()); parent.addView(button); return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void clearImages() {
        if (enlarged != null) { enlarged.dismiss(); enlarged = null; }
        // Detach every ImageView before recycling its backing bitmap.
        if (root != null) root.removeAllViews();
        synchronized (imagesLock) { for (Bitmap bitmap : images) bitmap.recycle(); images.clear(); }
    }
    @Override public void onResume() { super.onResume(); resumed = true; updateCapture(); startCamera(); }
    @Override public void onPause() {
        resumed = false; generation++; ExtractRuntime engine = runtime; if (engine != null) engine.cancel();
        busy = false; capturing = false; stopCamera(); clearImages(); drafts.clear();
        showCapture("Photos and drafts were cleared when this screen lost focus. Capture new views to continue."); super.onPause();
    }
    @Override public void onDestroy() { main.removeCallbacksAndMessages(null); worker.shutdown(); super.onDestroy(); }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 93) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startCamera();
            else status.setText("Camera permission is needed for this experiment. You can still enter details manually.");
            updateCapture();
        }
    }
}
