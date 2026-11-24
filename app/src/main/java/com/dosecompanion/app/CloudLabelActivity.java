package com.dosecompanion.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.*;
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
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** A few separate photos, one explicit upload, one reviewed prescription draft. No image files. */
public final class CloudLabelActivity extends ComponentActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<Bitmap> photos = new ArrayList<>();
    private final Object photosLock = new Object();
    private PreviewView preview;
    private ProcessCameraProvider provider;
    private androidx.camera.core.Camera camera;
    private ImageCapture capture;
    private LinearLayout root, thumbnails;
    private TextView status, notice;
    private Button take, remove, send;
    private CheckBox consent;
    private AlertDialog enlarged;
    private volatile CloudReader reader;
    private volatile boolean resumed;
    private volatile int generation;
    private boolean busy, capturing, reviewing, binding, ready;
    private String retention = "Checking the configured provider’s retention policy…";
    private int captureWidth, captureHeight;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showCapture("Take a front view with name/strength, then the full printed directions. Extra views can complete curved or hidden text.");
    }
    private void screen(String title) {
        ScrollView scroll = new ScrollView(this); root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(12),dp(16),dp(16)); root.setFitsSystemWindows(true); root.setBackgroundColor(0xFFF5F6F0);
        scroll.addView(root); setContentView(scroll); text(root,title,24);
    }
    private void showCapture(String message) {
        reviewing = false; screen("Read prescription from photos");
        text(root,"Take 2–4 clear photos of the same bottle. Each can show a different section; all photos are read together. No panorama or matching alignment is needed. Keep wanted text inside the white frame and unrelated personal details outside it when possible.",15);
        preview = new PreviewView(this); preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER); preview.setContentDescription("Live label camera; tap to focus");
        preview.setOnClickListener(v -> {}); preview.setOnTouchListener((v,event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP && camera != null) {
                v.performClick(); try { camera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(
                    preview.getMeteringPointFactory().createPoint(event.getX(),event.getY())).setAutoCancelDuration(3,TimeUnit.SECONDS).build()); }
                catch(RuntimeException closed) { }
            } return true;
        });
        FrameLayout cameraBox = new FrameLayout(this); cameraBox.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        android.graphics.Paint guidePaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        guidePaint.setStyle(android.graphics.Paint.Style.STROKE);guidePaint.setStrokeWidth(dp(2));guidePaint.setColor(android.graphics.Color.WHITE);
        View guide = new View(this){@Override protected void onDraw(android.graphics.Canvas canvas){canvas.drawRect(getWidth()*.08f,getHeight()*.14f,getWidth()*.92f,getHeight()*.86f,guidePaint);}};
        guide.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);cameraBox.addView(guide,new FrameLayout.LayoutParams(-1,-1));
        root.addView(cameraBox,new LinearLayout.LayoutParams(-1,dp(280)));
        status = text(root,message,15); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        thumbnails = new LinearLayout(this); root.addView(thumbnails);
        button(root,"Allow camera",() -> requestPermissions(new String[]{Manifest.permission.CAMERA},94));
        take = button(root,"Capture photo",this::takePhoto);
        remove = button(root,"Remove last photo",() -> {
            if (photos.isEmpty()) return; thumbnails.removeAllViews(); photos.remove(photos.size()-1).recycle(); consent.setChecked(false); updateCapture();
        });
        notice = text(root,retention,13);
        consent = new CheckBox(this); consent.setText("I agree to send these selected prescription photos through my backend to OpenAI under the retention policy shown above.");
        consent.setSaveEnabled(false); root.addView(consent); consent.setOnCheckedChangeListener((v,checked) -> updateCapture());
        send = button(root,"Send selected photos to OpenAI",this::upload);
        button(root,"Check cloud connection",this::checkConnection);
        button(root,"Cloud connection settings (discards photos)",() -> startActivity(new Intent(this,CloudConnectionActivity.class)));
        button(root,"Return without saving",this::finish);
        text(root,"Photos are held in memory and cleared when you leave this screen. Upload happens only after you agree and press Send. Once sent, a request can finish at the provider even if you close this screen. Scanning never logs a dose as taken.",12);
        updateCapture();
    }
    private void updateCapture() {
        if (reviewing || take == null || consent == null) return;
        take.setText(photos.size() == 4 ? "4 photos selected" : "Capture photo " + (photos.size()+1));
        take.setEnabled(!busy && !capturing && photos.size()<4 && capture != null);
        remove.setEnabled(!busy && !capturing && !photos.isEmpty());
        if (send != null) send.setEnabled(ready && !busy && !capturing && photos.size()>=2 && consent.isChecked());
        consent.setEnabled(!busy && ready); notice.setText(retention); thumbnails.removeAllViews();
        for(int i=0;i<photos.size();i++) {
            final int index=i; ImageView image=new ImageView(this); image.setImageBitmap(photos.get(i)); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setContentDescription("Selected photo "+(i+1)+"; tap to inspect"); image.setOnClickListener(v -> inspect(index));
            thumbnails.addView(image,new LinearLayout.LayoutParams(0,dp(100),1));
        }
    }
    private void inspect(int index) {
        if(index>=photos.size())return;
        ImageView image=new ImageView(this); Bitmap photo=photos.get(index); image.setImageBitmap(photo); image.setScaleType(ImageView.ScaleType.CENTER);
        ScrollView vertical=new ScrollView(this); HorizontalScrollView horizontal=new HorizontalScrollView(this);
        horizontal.addView(image,new ViewGroup.LayoutParams(photo.getWidth(),photo.getHeight())); vertical.addView(horizontal);
        enlarged=new AlertDialog.Builder(this).setTitle("Selected photo "+(index+1)).setView(vertical).setPositiveButton("Return",null).show();
        enlarged.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void startCamera() {
        if(!resumed||reviewing||busy||binding||provider!=null||checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
        if(preview.getWidth()==0){preview.post(this::startCamera);return;}
        binding=true; final int session=generation; var future=ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            binding=false; if(!current(session)||reviewing||busy){if(resumed&&!reviewing&&!busy)startCamera();return;}
            try {
                ProcessCameraProvider available=future.get(); Preview feed=new Preview.Builder().build(); feed.setSurfaceProvider(preview.getSurfaceProvider());
                ImageCapture photo=new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(new ResolutionSelector.Builder().setResolutionStrategy(new ResolutionStrategy(new Size(2560,1920),ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()).build();
                var viewport=preview.getViewPort(); if(viewport==null){preview.post(this::startCamera);return;}
                camera=available.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,new UseCaseGroup.Builder().setViewPort(viewport).addUseCase(feed).addUseCase(photo).build());
                provider=available;capture=photo;updateCapture();
            }catch(Exception unavailable){status.setText("Camera unavailable. Check camera permission, then reopen this screen.");}
        },ContextCompat.getMainExecutor(this));
    }
    private void stopCamera(){if(provider!=null)provider.unbindAll();provider=null;camera=null;capture=null;}
    private void takePhoto(){
        if(capture==null||capturing||busy||photos.size()>=4)return;
        capturing=true; final int session=generation; captureWidth=preview.getWidth();captureHeight=preview.getHeight();updateCapture();status.setText("Hold still…");
        capture.takePicture(ContextCompat.getMainExecutor(this),new ImageCapture.OnImageCapturedCallback(){
            @Override public void onCaptureSuccess(ImageProxy frame){
                try{
                    if(!current(session))return;
                    Bitmap photo=CameraImages.crop(frame,captureWidth,captureHeight);int edge=Math.max(photo.getWidth(),photo.getHeight());
                    if(edge>2000){Bitmap smaller=Bitmap.createScaledBitmap(photo,photo.getWidth()*2000/edge,photo.getHeight()*2000/edge,true);if(smaller!=photo)photo.recycle();photo=smaller;}
                    if(!CameraImages.hasContrast(photo)){photo.recycle();status.setText("This photo is dark or blank. Improve lighting and try again.");}
                    else{photos.add(photo);consent.setChecked(false);status.setText("Photo "+photos.size()+" selected. Tap its thumbnail to inspect. Capture the remaining label sections.");}
                }catch(RuntimeException unavailable){status.setText("Capture failed. Try again.");}
                finally{frame.close();if(session==generation){capturing=false;updateCapture();}}
            }
            @Override public void onError(ImageCaptureException error){if(current(session)){capturing=false;status.setText("Capture failed. Try again.");updateCapture();}}
        });
    }
    private boolean current(int session){return resumed&&!isDestroyed()&&session==generation;}
    private void checkConnection(){
        if(!resumed||busy)return; ready=false;consent.setChecked(false);updateCapture();final int session=generation;
        worker.execute(() -> {
            String message;String policy="Cloud connection is not ready. No photos have been uploaded.";boolean connected=false;
            try(CloudReader connection=new CloudReader(this)){
                reader=connection;var config=connection.settings();
                policy=config.get("retention_notice").getAsString();
                if(policy.length()>2000)throw new IOException("Invalid connection metadata.");
                connected=config.get("ready").isJsonPrimitive()&&config.get("ready").getAsJsonPrimitive().isBoolean()&&config.get("ready").getAsBoolean()
                    &&"standard".equals(config.get("retention_policy").getAsString());
                message=connected?"Cloud reader configured. Capture photos, review the retention notice, then explicitly send.":"API setup is needed on the server. Photos can be captured, but uploading is disabled.";
            }catch(Exception unavailable){message="Cloud connection needs setup. Keep the USB bridge running on your Mac, or open Cloud connection settings.";}
            finally{reader=null;}
            final String outcome=message, description=policy;final boolean active=connected;
            runOnUiThread(() -> {if(current(session)&&!reviewing){ready=active;retention=description;status.setText(outcome);updateCapture();}});
        });
    }
    private void upload(){
        if(!resumed||busy||capturing||!ready||!consent.isChecked()||photos.size()<2)return;
        ArrayList<Bitmap> chosen=new ArrayList<>(photos);
        busy=true;final int session=++generation;stopCamera();updateCapture();status.setText("Sending "+chosen.size()+" selected photos together for one cloud reading…");
        worker.execute(() -> {
            ArrayList<byte[]> selected=new ArrayList<>();
            LabelDraft result=null;String error=null;long started=SystemClock.elapsedRealtime();
            try(CloudReader connection=new CloudReader(this)){
                reader=connection;
                for(Bitmap photo:chosen) synchronized(photosLock){
                    if(!current(session)){connection.cancel();return;}
                    byte[] jpeg=CloudReader.photo(photo);
                    if(jpeg.length>3*1024*1024){Arrays.fill(jpeg,(byte)0);throw new IOException("A photo is too large. Move closer to the relevant label section and retake.");}
                    selected.add(jpeg);
                }
                if(!current(session)){connection.cancel();return;}
                result=connection.read(selected);
            }
            catch(IOException failure){error=failure.getMessage();}
            catch(RuntimeException failure){error="The cloud reader returned an invalid response. No fields were accepted.";}
            finally{reader=null;for(byte[] image:selected)Arrays.fill(image,(byte)0);}
            final LabelDraft draft=result;final String message=error;final long elapsed=SystemClock.elapsedRealtime()-started;
            runOnUiThread(() -> {if(!current(session))return;busy=false;if(draft==null){consent.setChecked(false);status.setText(message==null?"Reading failed. Try again.":message);startCamera();updateCapture();}else showReview(draft,elapsed);});
        });
    }
    private void showReview(LabelDraft draft,long elapsed){
        reviewing=true;screen("Review the cloud reading");text(root,"One reading of all selected photos ("+elapsed/1000+" seconds). Check every field against the original label. Missing or conflicting text must be corrected manually.",15);
        for(int i=0;i<photos.size();i++){final int index=i;button(root,"Inspect source photo "+(i+1),() -> inspect(index));}
        EditText name=field("Medication name",draft.name,120),strength=field("Printed strength with units",draft.strength,80),directions=field("Full printed taking instructions",draft.directions,2000);
        directions.setSingleLine(false);directions.setMinLines(3);TextView error=text(root,"",14);
        CheckBox confirm=new CheckBox(this);confirm.setText("I checked the medication name, strength and complete instructions against the label.");confirm.setSaveEnabled(false);root.addView(confirm);
        Button use=button(root,"Use reviewed details",() -> {
            if(!confirm.isChecked())return;
            if(name.getText().toString().trim().isEmpty()||strength.getText().toString().trim().isEmpty()||directions.getText().toString().trim().isEmpty()){error.setText("Complete the missing printed fields before confirming, or return and enter details manually.");return;}
            setResult(RESULT_OK,new Intent().putExtra("product_name",name.getText().toString().trim()).putExtra("strength",strength.getText().toString().trim())
                .putExtra("confirmed_directions",directions.getText().toString().trim()).putExtra("cloud_draft_reviewed",true));finish();
        });use.setEnabled(false);confirm.setOnCheckedChangeListener((v,checked) -> use.setEnabled(checked));
        TextWatcher edits=new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){confirm.setChecked(false);}public void afterTextChanged(Editable value){}};
        name.addTextChangedListener(edits);strength.addTextChangedListener(edits);directions.addTextChangedListener(edits);
        button(root,"Discard reading and take new photos",() -> {generation++;clearPhotos();ready=false;showCapture("Capture new sections of the same bottle.");checkConnection();startCamera();});
        button(root,"Return without saving",this::finish);text(root,"Only reviewed text returns to the medication form. Set quantity and times yourself, then save explicitly. No dose has been logged.",12);
    }
    private EditText field(String title,String value,int max){text(root,title,15);EditText edit=new EditText(this);edit.setContentDescription(title);edit.setSingleLine(true);edit.setSaveEnabled(false);edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});edit.setText(value);root.addView(edit);return edit;}
    private TextView text(LinearLayout parent,String value,int size){TextView text=new TextView(this);text.setText(value);text.setTextSize(size);text.setSaveEnabled(false);text.setPadding(0,dp(5),0,dp(5));parent.addView(text);return text;}
    private Button button(LinearLayout parent,String value,Runnable action){Button button=new Button(this);button.setText(value);button.setOnClickListener(v -> action.run());parent.addView(button);return button;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void clearPhotos(){if(enlarged!=null){enlarged.dismiss();enlarged=null;}if(root!=null)root.removeAllViews();synchronized(photosLock){for(Bitmap photo:photos)photo.recycle();photos.clear();}}
    @Override public void onResume(){super.onResume();resumed=true;startCamera();checkConnection();}
    @Override public void onPause(){resumed=false;generation++;CloudReader active=reader;if(active!=null)active.cancel();busy=false;capturing=false;ready=false;stopCamera();clearPhotos();showCapture("Photos and unconfirmed readings were cleared when this screen lost focus.");super.onPause();}
    @Override public void onDestroy(){worker.shutdown();super.onDestroy();}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==94){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startCamera();else status.setText("Camera permission is needed for label photos. Manual entry remains available.");}}
}
