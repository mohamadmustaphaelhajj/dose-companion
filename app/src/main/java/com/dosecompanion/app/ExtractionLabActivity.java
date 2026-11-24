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

/** Local, single assembled-image transcription experiment. Pixels and unreviewed drafts live only while foregrounded. */
public final class ExtractionLabActivity extends ComponentActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object imagesLock = new Object();
    private final ArrayList<Bitmap> images = new ArrayList<>();
    private final ArrayList<LabelDraft> drafts = new ArrayList<>();
    private PreviewView preview;
    private ProcessCameraProvider provider;
    private androidx.camera.core.Camera camera;
    private ImageAnalysis analysis;
    private BottlePanorama panorama;
    private boolean panoramaCapturing, framePending;
    private long lastPanoramaFrame, panoramaStarted;
    private LinearLayout root, thumbnails;
    private TextView status;
    private Button take, read, permission, prepare, remove;
    private volatile boolean resumed;
    private boolean busy, capturing, binding, reviewing;
    private volatile int generation;
    private volatile ExtractRuntime runtime;
    private AlertDialog enlarged;
    private Bitmap assembled;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showCapture("Hold the phone still. Center the bottle, then tap Start panorama and slowly rotate only the bottle.");
    }
    private void screen(String title) {
        ScrollView scroll = new ScrollView(this); root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(16), dp(14), dp(16), dp(16));
        root.setFitsSystemWindows(true); root.setBackgroundColor(0xFFF5F6F0); scroll.addView(root); setContentView(scroll);
        text(root, title, 24);
    }
    private void showCapture(String message) {
        reviewing = false; screen("Rotate-bottle panorama");
        text(root, "First move close enough for the label to fill the large frame. Then keep the phone still and the bottle upright in the same position. Turn the bottle slowly in one direction, so every printed line passes the narrow center strip. Start just before the medicine name and finish after the full directions.", 15);
        FrameLayout frame = new FrameLayout(this);
        preview = new PreviewView(this); preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER); frame.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        View guide = new View(this) {
            @Override protected void onDraw(Canvas canvas) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(Color.WHITE); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(2));
                canvas.drawRect(getWidth() * .08f, getHeight() * .14f, getWidth() * .92f, getHeight() * .86f, p);
                p.setColor(0xFF61E4BD); canvas.drawRect(getWidth() * .433f, getHeight() * .14f, getWidth() * .567f, getHeight() * .86f, p);
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
        take = button(root, "Start panorama", () -> { if (panoramaCapturing) finishPanorama(); else beginPanorama(); });
        remove = button(root, "Discard panorama and restart", this::retake);
        read = button(root, "", () -> {}); read.setVisibility(View.GONE);
        button(root, "Return without saving", this::finish);
        text(root, "Photos and drafts are discarded when you leave this screen or switch apps. Scanning never logs a dose as taken.", 12);
        updateCapture();
    }
    private void updateCapture() {
        if (reviewing || take == null) return;
        int count; synchronized (imagesLock) { count = images.size(); }
        take.setText(panoramaCapturing ? "Finish panorama" : "Start panorama");
        take.setEnabled(!busy && !capturing && analysis != null);
        remove.setEnabled(!busy); read.setVisibility(View.GONE);
        prepare.setText(ExtractModels.installed(this) ? "Model files ready · manage" : "Import experiment model files");
        prepare.setVisibility(!panoramaCapturing && (count == 0 || !ExtractModels.installed(this)) ? View.VISIBLE : View.GONE);
        prepare.setEnabled(!busy && !capturing && !panoramaCapturing);
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
            enlarged.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
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
                ImageAnalysis frames = new ImageAnalysis.Builder().setResolutionSelector(new ResolutionSelector.Builder()
                        .setResolutionStrategy(new ResolutionStrategy(new Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                frames.setAnalyzer(ContextCompat.getMainExecutor(this), this::panoramaFrame);
                var viewport = preview.getViewPort();
                if (viewport == null) { preview.post(this::startCamera); return; }
                camera = ready.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        new UseCaseGroup.Builder().setViewPort(viewport).addUseCase(feed).addUseCase(frames).build());
                provider = ready; analysis = frames; updateCapture();
            } catch (Exception unavailable) { status.setText("Camera unavailable. Return and reopen the experiment, or check camera permission in Android settings."); }
        }, ContextCompat.getMainExecutor(this));
    }
    private void stopCamera() {
        if (provider != null) provider.unbindAll(); provider = null; camera = null; if (analysis != null) analysis.clearAnalyzer(); analysis = null;
    }
    private void beginPanorama() {
        if (!resumed || busy || analysis == null) return;
        releasePanorama(); generation++; panorama = new BottlePanorama(); panoramaCapturing = true; framePending = false;
        panoramaStarted = SystemClock.elapsedRealtime(); lastPanoramaFrame = 0;
        status.setText("Keep the phone still. Slowly rotate the bottle in place so text moves across the green center strip."); updateCapture();
    }
    private void releasePanorama() {
        panoramaCapturing = false; BottlePanorama old = panorama; panorama = null;
        if (old != null) worker.execute(old::close);
    }
    private void panoramaFrame(ImageProxy frame) {
        long now = SystemClock.elapsedRealtime();
        if (!resumed || !panoramaCapturing || framePending || now - lastPanoramaFrame < 160) { frame.close(); return; }
        if (now - panoramaStarted > 90_000) { frame.close(); finishPanorama(); return; }
        final int session = generation; final BottlePanorama target = panorama; Bitmap bitmap;
        try {
            bitmap = CameraImages.crop(frame, preview.getWidth(), preview.getHeight());
            int edge = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (edge > 1024) { Bitmap small = Bitmap.createScaledBitmap(bitmap, Math.max(1,bitmap.getWidth()*1024/edge), Math.max(1,bitmap.getHeight()*1024/edge),true); if (small != bitmap) bitmap.recycle(); bitmap = small; }
        } catch (RuntimeException unavailable) { frame.close(); return; }
        frame.close(); framePending = true; lastPanoramaFrame = now; final Bitmap pixels = bitmap;
        worker.execute(() -> {
            String message;
            try { message = target.accept(pixels); }
            catch (Exception | LinkageError failure) { message = "Tracking unavailable for this frame. Pause, tap to focus, and rotate back slightly."; }
            finally { pixels.recycle(); }
            final String feedback = message;
            main.post(() -> { if (current(session)) { framePending = false; if (panoramaCapturing) status.setText(feedback); } });
        });
    }
    private void finishPanorama() {
        if (!panoramaCapturing || panorama == null || busy) return;
        panoramaCapturing = false; busy = true; final int session = generation;
        final BottlePanorama target = panorama; panorama = null; stopCamera(); updateCapture();
        status.setText("Joining the captured center strips into one label panorama…");
        worker.execute(() -> {
            Bitmap result = null; String error = null;
            try { result = target.finish(); } catch (Exception failure) { error = failure.getMessage(); } finally { target.close(); }
            final Bitmap joined = result; final String message = error;
            main.post(() -> {
                if (!current(session)) { if (joined != null) joined.recycle(); return; }
                busy = false;
                if (joined == null) { status.setText(message == null ? "Panorama could not be assembled. Try again." : message); startCamera(); updateCapture(); return; }
                assembled = joined; showAssembly();
            });
        });
    }
    private void showAssembly() {
        reviewing = true; screen("Check the combined label");
        text(root, "Inspect every seam and decimal point. Confirm that the full directions are present once, in the correct order. Retake if anything is cut, duplicated or distorted.", 16);
        // Native-size scrollable image: users can inspect small print without a forced fit-to-screen reduction.
        HorizontalScrollView horizontal = new HorizontalScrollView(this); ImageView image = new ImageView(this);
        image.setImageBitmap(assembled); image.setAdjustViewBounds(false); image.setScaleType(ImageView.ScaleType.CENTER);
        image.setContentDescription("Combined label image at original resolution");
        horizontal.addView(image, new ViewGroup.LayoutParams(assembled.getWidth(), assembled.getHeight())); root.addView(horizontal);
        CheckBox checked = new CheckBox(this); checked.setText("The joined text is complete and the seams are readable."); root.addView(checked);
        Button run = button(root, "Read this combined image locally", this::readViews); run.setEnabled(false);
        checked.setOnCheckedChangeListener((view, value) -> run.setEnabled(value && !busy && ExtractModels.installed(this)));
        if (!ExtractModels.installed(this)) button(root, "Import model files (discards photos)", () -> startActivity(new Intent(this, ExtractModelActivity.class)));
        button(root, "Discard and retake", this::retake);
        button(root, "Return without saving", this::finish);
        for (int i = 0; i < images.size(); i++) { final int index = i; button(root, "Inspect original view " + (i + 1), () -> enlarge(index)); }
        status = text(root, "One combined image will be sent to the local model once. Larger images can take several minutes on this phone.", 14);
    }
    private void retake() { generation++; releasePanorama(); clearImages(); drafts.clear(); busy = false; showCapture("Hold the phone still, then start a new rotating-bottle panorama."); startCamera(); }
    private void readViews() {
        if (!resumed || busy || assembled == null || !ExtractModels.installed(this)) return;
        busy = true; final int session = ++generation;
        // Remove the activation control immediately, preventing repeated submissions of the same image.
        screen("Reading the combined label"); status = text(root, "Reading one combined image locally. Keep this screen open; this may take several minutes.", 16);
        button(root, "Cancel reading", () -> cancelReading("Reading cancelled."));
        byte[] jpeg = ExtractInput.jpeg(assembled); Bitmap ocrCopy = assembled.copy(Bitmap.Config.ARGB_8888, false);
        worker.execute(() -> {
            LabelDraft draft; String report; boolean ocrStarted = false;
            Runnable deadline = () -> { if (current(session)) cancelReading("The combined reading exceeded five minutes. Try closer, sharper sections."); };
            main.postDelayed(deadline, 300_000); long started = SystemClock.elapsedRealtime();
            try (ExtractRuntime engine = new ExtractRuntime(this); TextRecognizer ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)) {
                runtime = engine;
                if (!current(session)) { engine.cancel(); return; }
                var task = ocr.process(InputImage.fromBitmap(ocrCopy, 0)); ocrStarted = true;
                task.addOnCompleteListener(done -> { if (!ocrCopy.isRecycled()) ocrCopy.recycle(); });
                String raw = "OCR unavailable.";
                try { raw = Tasks.await(task, 20, TimeUnit.SECONDS).getText(); } catch (Exception ignored) { }
                draft = engine.read(jpeg); // Exactly one call, using pixels of the reviewed assembled label only.
                report = "Combined-image reading: " + (SystemClock.elapsedRealtime() - started) / 1000 + " seconds\nIndependent OCR of the same combined image:\n" + raw;
            } catch (Exception | LinkageError failure) {
                main.post(() -> { if (current(session)) { busy = false; showAssembly(); status.setText("Local reading failed or returned invalid JSON. No fields accepted. Try again or retake."); } }); return;
            } finally { runtime = null; Arrays.fill(jpeg, (byte) 0); main.removeCallbacks(deadline); if (!ocrStarted && !ocrCopy.isRecycled()) ocrCopy.recycle(); }
            final LabelDraft answer = draft; final String outcome = report;
            main.post(() -> { if (current(session)) { busy = false; drafts.clear(); drafts.add(answer); showReview(List.of(outcome)); } });
        });
    }
    private boolean current(int session) { return resumed && !isDestroyed() && session == generation; }
    private void cancelReading(String message) {
        if (!busy) return;
        generation++; ExtractRuntime engine = runtime; if (engine != null) engine.cancel();
        busy = false; if (assembled != null) showAssembly(); else { updateCapture(); startCamera(); } status.setText(message);
    }
    private void showReview(List<String> reports) {
        reviewing = true; screen("Review local drafts");
        text(root, "Compare every field with the source photos. The model can mistake a pharmacy name or bottle count for a missing field. Check the joined image, including every seam. Missing words are never filled from your saved prescription.", 15);
        text(root, "One reading of the combined label", 20);
        ImageView combined = new ImageView(this); combined.setImageBitmap(assembled); combined.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(combined, new LinearLayout.LayoutParams(-1, dp(250)));
        TextView report = text(root, reports.get(0), 13); report.setVisibility(View.GONE);
        button(root, "Show / hide combined-image OCR and timing", () -> report.setVisibility(report.getVisibility() == View.GONE ? View.VISIBLE : View.GONE));
        text(root, "Your reviewed transcription", 21);
        EditText name = field("Product name", LabelDraft.unique(drafts, 0), 120);
        EditText strength = field("Printed strength (preserve decimals and units)", LabelDraft.unique(drafts, 1), 80);
        EditText directions = field("Full printed directions from the combined label", LabelDraft.unique(drafts, 2), 2000);
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
        button(root, "Discard and take new photos", () -> { clearImages(); drafts.clear(); showCapture("Start a new rotating-bottle panorama."); startCamera(); });
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
        if (assembled != null) { assembled.recycle(); assembled = null; }
        synchronized (imagesLock) { for (Bitmap bitmap : images) bitmap.recycle(); images.clear(); }
    }
    @Override public void onResume() { super.onResume(); resumed = true; updateCapture(); startCamera(); }
    @Override public void onPause() {
        resumed = false; generation++; ExtractRuntime engine = runtime; if (engine != null) engine.cancel();
        busy = false; capturing = false; releasePanorama(); stopCamera(); clearImages(); drafts.clear();
        showCapture("Panorama and drafts were cleared when this screen lost focus. Start a new panorama to continue."); super.onPause();
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
