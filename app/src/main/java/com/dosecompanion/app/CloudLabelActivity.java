package com.dosecompanion.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.*;
import android.util.Size;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.camera.core.*;
import androidx.camera.core.resolutionselector.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.dosecompanion.core.LabelDraft;
import java.util.*;
import java.util.concurrent.*;

/** A few separate photos, one explicit upload, one reviewed prescription draft. No image files. */
public final class CloudLabelActivity extends ComponentActivity {
    private CloudScanSession session;
    private ArrayList<Bitmap> photos;
    private EditText reviewName, reviewStrength, reviewDirections;
    private PreviewView preview;
    private ProcessCameraProvider provider;
    private androidx.camera.core.Camera camera;
    private ImageCapture capture;
    private LinearLayout root, thumbnails;
    private TextView status, notice, captureTitle, captureHint;
    private ProgressBar progress;
    private Button take, remove, send;
    private Button allowCamera, extra;
    private final ArrayList<View> photoActions = new ArrayList<>();
    private CheckBox consent;
    private AlertDialog enlarged;
    private volatile boolean resumed;
    private volatile int generation;
    private boolean busy, capturing, reviewing, photoReview, binding, ready;
    private static final int INK = 0xFF183C39, MUTED = 0xFF526B67, ACCENT = 0xFF176C5A, PAPER = 0xFFF4F7F4;
    private String retention = "Checking the configured provider’s retention policy…";
    private int captureWidth, captureHeight;
    private int shownCaptureIndex = -1;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        session = new ViewModelProvider(this).get(CloudScanSession.class); photos = session.photos;
        session.changes.observe(this, value -> { if (resumed) renderSession(); });
        showCapture();
    }
    private void screen(String title) {
        ScrollView scroll = new ScrollView(this); root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(16),dp(20),dp(24)); root.setFitsSystemWindows(true); root.setBackgroundColor(PAPER);
        scroll.setFillViewport(true); scroll.addView(root); setContentView(scroll);
        TextView eyebrow = text(root,"ADD MEDICATION",12); eyebrow.setTextColor(ACCENT); eyebrow.setLetterSpacing(.12f);
        TextView heading = text(root,title,28); heading.setTypeface(null,Typeface.BOLD); androidx.core.view.ViewCompat.setAccessibilityHeading(heading,true);
    }
    private void steps(int active) {
        LinearLayout row=new LinearLayout(this); row.setPadding(0,dp(12),0,dp(16)); root.addView(row);
        String[] labels={"Label","Instructions","Photos","Details"};
        for(int i=0;i<labels.length;i++) {
            TextView label=new TextView(this); label.setText((i+1)+"\n"+labels[i]);label.setTextSize(12);label.setGravity(Gravity.CENTER);
            label.setPadding(dp(2),dp(10),dp(2),dp(10));label.setTextColor(i==active?Color.WHITE:INK);
            label.setBackground(rounded(i==active?ACCENT:0xFFE2ECE6,12));
            label.setContentDescription("Step "+(i+1)+" of 4: "+labels[i]+(i==active?", current step":i<active?", completed":""));
            LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(0,-2,1);if(i>0)size.leftMargin=dp(5);row.addView(label,size);
        }
    }
    private int captureIndex() { return session.retakeIndex>=0?session.retakeIndex:photos.size(); }
    private String photoTitle(int index) {return index==0?"Name & strength":index==1?"Taking instructions":"Extra view "+(index-1);}
    private void updateCapturePrompt() {
        int index=captureIndex(); captureTitle.setText(photoTitle(index));
        captureHint.setText(index==0?"Point at the medicine name and printed strength, including units such as mg. If they wrap around the bottle, add another view later."
            :index==1?"Turn the bottle to the taking instructions: how much to take and how often. Photograph the whole sentence; add extra views later if it wraps around."
            :"Capture any text missing from the first two photos, such as strength on the side or the rest of a wrapped instruction. Use the same bottle.");
    }
    private void showCapture() {
        shownCaptureIndex=captureIndex();
        reviewing = false; photoReview = false; screen("Scan your prescription"); steps(Math.min(captureIndex(),2));
        captureTitle=text(root,"",21);captureTitle.setTypeface(null,Typeface.BOLD);captureHint=text(root,"",15);updateCapturePrompt();
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
        LinearLayout.LayoutParams cameraSize=new LinearLayout.LayoutParams(-1,dp(280));cameraSize.topMargin=dp(12);root.addView(cameraBox,cameraSize);
        text(root,"Tap the label to focus. Keep text inside the frame and avoid glare.",13);
        status = text(root,"",15); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        thumbnails = new LinearLayout(this); root.addView(thumbnails);
        allowCamera=button(root,"Allow camera",() -> requestPermissions(new String[]{Manifest.permission.CAMERA},94));
        take = primary(root,"Capture photo",this::takePhoto);
        remove = button(root,photos.size()>=2?"Back to photo review":"Retake name & strength",() -> {
            if(capturing||busy)return;stopCamera();
            if(photos.size()>=2)session.reviewPhotos();else session.captureMore(0);
        });
        // Upload consent is shown only after the user has captured both label sections.
        consent=null;send=null;
        button(root,"Cancel scan",this::finish);
        text(root,"Photos stay on this device until you review and send them. Leaving this screen clears them.",12);
        updateCapture();
    }
    private void showPhotoReview() {
        reviewing=false;photoReview=true;stopCamera();screen("Check your photos");steps(2);
        text(root,"Can you read the name, strength and complete taking instructions? Retake a blurry view or add a missing section. All photos will be read together.",15);
        photoActions.clear();thumbnails=new LinearLayout(this);thumbnails.setOrientation(LinearLayout.VERTICAL);root.addView(thumbnails);
        for(int i=0;i<photos.size();i++) {
            final int index=i; LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(12),dp(10),dp(12),dp(12));card.setBackground(rounded(Color.WHITE,16));
            LinearLayout.LayoutParams cardSize=new LinearLayout.LayoutParams(-1,-2);cardSize.topMargin=dp(10);thumbnails.addView(card,cardSize);
            TextView title=text(card,photoTitle(i),18);title.setTypeface(null,Typeface.BOLD);
            ImageView image=new ImageView(this);image.setImageBitmap(photos.get(i));image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription(photoTitle(i)+" photo; tap to inspect");image.setOnClickListener(v -> inspect(index));
            card.addView(image,new LinearLayout.LayoutParams(-1,dp(150)));
            LinearLayout actions=new LinearLayout(this);card.addView(actions);
            rowButton(actions,"Inspect",() -> inspect(index)).setContentDescription("Inspect "+photoTitle(i)+" photo");
            Button retake=rowButton(actions,"Retake",() -> beginCapture(index));retake.setContentDescription("Retake "+photoTitle(i)+" photo");photoActions.add(retake);
            if(i>=2){Button delete=rowButton(actions,"Remove",() -> {if(busy)return;detachImages();session.removeExtra(index);showPhotoReview();updatePhotoReview();});delete.setContentDescription("Remove "+photoTitle(i)+" photo");photoActions.add(delete);}
        }
        extra=button(root,"Add another view (optional)",() -> beginCapture(-1));
        progress=new ProgressBar(this);progress.setContentDescription("Reading selected photos");root.addView(progress,new LinearLayout.LayoutParams(dp(32),dp(32)));
        status=text(root,session.message,15);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        notice=text(root,retention,13);
        consent = new CheckBox(this); consent.setText("I agree to send these selected prescription photos through my backend to OpenAI under the retention policy shown above.");
        consent.setTextColor(INK);consent.setSaveEnabled(false); root.addView(consent); consent.setOnCheckedChangeListener((v,checked) -> updatePhotoReview());
        send = primary(root,"Send selected photos to OpenAI",this::upload);
        button(root,"Check cloud connection",this::checkConnection);
        button(root,"Cloud connection settings (discards photos)",() -> startActivity(new Intent(this,CloudConnectionActivity.class)));
        button(root,"Cancel scan",this::finish);
        text(root,"Sending does not save a medication or log a dose. You will review the reading next. Leaving clears local photos; it cannot retract an upload already sent.",12);
        updatePhotoReview();
    }
    private void beginCapture(int index) {
        if(busy||capturing)return;
        if(enlarged!=null){enlarged.dismiss();enlarged=null;}
        if(consent!=null)consent.setChecked(false);
        session.captureMore(index);
    }
    private void updatePhotoReview() {
        if(!photoReview||consent==null)return;
        notice.setText(retention);progress.setVisibility(busy?View.VISIBLE:View.GONE);
        send.setText(busy?"Reading photos…":"Send selected photos to OpenAI");
        send.setEnabled(ready&&!busy&&photos.size()>=2&&consent.isChecked());consent.setEnabled(ready&&!busy);
        extra.setEnabled(!busy&&photos.size()<4);extra.setVisibility(photos.size()<4?View.VISIBLE:View.GONE);
        for(View action:photoActions)action.setEnabled(!busy);
    }
    private void updateCapture() {
        if (reviewing || photoReview || take == null) return;
        updateCapturePrompt();
        take.setText(capturing?"Capturing…":session.retakeIndex>=0?"Replace photo":captureIndex()==0?"Capture name & strength":captureIndex()==1?"Capture instructions":"Capture extra view");
        take.setEnabled(!busy && !capturing && (photos.size()<4||session.retakeIndex>=0) && capture != null);
        remove.setVisibility(photos.isEmpty()?View.GONE:View.VISIBLE);remove.setEnabled(!busy&&!capturing);
        allowCamera.setVisibility(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED?View.GONE:View.VISIBLE);
        thumbnails.removeAllViews();
        for(int i=0;i<photos.size();i++) {
            final int index=i; ImageView image=new ImageView(this); image.setImageBitmap(photos.get(i)); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setContentDescription(photoTitle(i)+" photo; tap to inspect"); image.setOnClickListener(v -> inspect(index));
            thumbnails.addView(image,new LinearLayout.LayoutParams(0,dp(70),1));
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
        if(!resumed||reviewing||photoReview||busy||binding||provider!=null||checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
        if(preview.getWidth()==0){preview.post(this::startCamera);return;}
        binding=true; final int session=generation; var future=ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            binding=false; if(!current(session)||reviewing||photoReview||busy){if(resumed&&!reviewing&&!photoReview&&!busy)startCamera();return;}
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
        if(capture==null||capturing||busy||photoReview||(photos.size()>=4&&session.retakeIndex<0))return;
        capturing=true; final int session=generation; captureWidth=preview.getWidth();captureHeight=preview.getHeight();updateCapture();status.setText("Hold still…");
        capture.takePicture(ContextCompat.getMainExecutor(this),new ImageCapture.OnImageCapturedCallback(){
            @Override public void onCaptureSuccess(ImageProxy frame){
                try{
                    if(!current(session))return;
                    Bitmap photo=CameraImages.crop(frame,captureWidth,captureHeight);int edge=Math.max(photo.getWidth(),photo.getHeight());
                    if(edge>2000){Bitmap smaller=Bitmap.createScaledBitmap(photo,photo.getWidth()*2000/edge,photo.getHeight()*2000/edge,true);if(smaller!=photo)photo.recycle();photo=smaller;}
                    if(!CameraImages.hasContrast(photo)){photo.recycle();status.setText("This photo is dark or blank. Improve lighting and try again.");}
                    else{
                        // Remove displayed bitmap references before recycling a replaced source.
                        thumbnails.removeAllViews();if(enlarged!=null){enlarged.dismiss();enlarged=null;}
                        capturing=false;CloudLabelActivity.this.session.message="Photo captured. Check that the printed text is sharp and complete.";
                        CloudLabelActivity.this.session.acceptPhoto(photo);
                        if(!photoReview)status.setText("Name & strength captured. Now turn to the taking instructions.");
                    }
                }catch(RuntimeException unavailable){status.setText("Capture failed. Try again.");}
                finally{frame.close();if(session==generation){capturing=false;updateCapture();}}
            }
            @Override public void onError(ImageCaptureException error){if(current(session)){capturing=false;status.setText("Capture failed. Try again.");updateCapture();}}
        });
    }
    private boolean current(int session){return resumed&&!isDestroyed()&&session==generation;}
    private void checkConnection(){
        if(!resumed||busy)return; if(consent!=null)consent.setChecked(false); session.checkConnection();
    }
    private void upload(){
        if(!resumed||!photoReview||busy||capturing||!ready||consent==null||!consent.isChecked()||photos.size()<2)return;
        consent.setChecked(false); stopCamera(); session.upload();
    }
    private void renderSession(){
        busy = session.busy; ready = session.ready && !session.checking; retention = session.retention;
        if(session.draft != null && !busy){
            if(!reviewing)showReview(session.draft,session.elapsed);
        }else{
            if(session.page==CloudScanSession.Page.PHOTOS){if(!photoReview||reviewing)showPhotoReview();updatePhotoReview();}
            else{if(photoReview||reviewing||shownCaptureIndex!=captureIndex()){stopCamera();showCapture();}updateCapture();if(!busy)startCamera();}
            if(photoReview)status.setText(session.message);
        }
    }
    private void showReview(LabelDraft draft,long elapsed){
        session.draft = draft; session.elapsed = elapsed;
        stopCamera();reviewing=true;photoReview=false;screen("Review the details");steps(3);text(root,"Read in "+elapsed/1000+" seconds. Check each field against your photos and the original label. Correct anything missing or wrong before continuing.",15);
        for(int i=0;i<photos.size();i++){final int index=i;button(root,"Inspect source photo "+(i+1),() -> inspect(index));}
        EditText name=field("Medication name",draft.name,120),strength=field("Printed strength with units",draft.strength,80),directions=field("Full printed taking instructions",draft.directions,2000);
        reviewName=name;reviewStrength=strength;reviewDirections=directions;
        String missing = (draft.name.isEmpty()?" name":"") + (draft.strength.isEmpty()?" strength":"") + (draft.directions.isEmpty()?" instructions":"");
        if(!missing.isEmpty())text(root,"Not read reliably:"+missing+". Inspect the source photos and fill these fields manually, or retake the missing section.",15);
        directions.setSingleLine(false);directions.setMinLines(3);TextView error=text(root,"",14);
        CheckBox confirm=new CheckBox(this);confirm.setText("I checked the medication name, strength and complete instructions against the label.");confirm.setSaveEnabled(false);root.addView(confirm);
        Button use=primary(root,"Use reviewed details",() -> {
            if(!confirm.isChecked())return;
            if(name.getText().toString().trim().isEmpty()||strength.getText().toString().trim().isEmpty()||directions.getText().toString().trim().isEmpty()){error.setText("Complete the missing printed fields before confirming, or return and enter details manually.");return;}
            setResult(RESULT_OK,new Intent().putExtra("product_name",name.getText().toString().trim()).putExtra("strength",strength.getText().toString().trim())
                .putExtra("confirmed_directions",directions.getText().toString().trim()).putExtra("cloud_draft_reviewed",true));finish();
        });use.setEnabled(false);confirm.setOnCheckedChangeListener((v,checked) -> use.setEnabled(checked));
        TextWatcher edits=new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){confirm.setChecked(false);}public void afterTextChanged(Editable value){}};
        name.addTextChangedListener(edits);strength.addTextChangedListener(edits);directions.addTextChangedListener(edits);
        button(root,"Discard reading and take new photos",() -> {generation++;clearPhotos();ready=false;showCapture();checkConnection();startCamera();});
        button(root,"Return without saving",this::finish);text(root,"Only reviewed text returns to the medication form. Set quantity and times yourself, then save explicitly. No dose has been logged.",12);
    }
    private EditText field(String title,String value,int max){text(root,title,15);EditText edit=new EditText(this);edit.setContentDescription(title);edit.setSingleLine(true);edit.setSaveEnabled(false);edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});edit.setText(value);root.addView(edit);return edit;}
    private GradientDrawable rounded(int color,int radius){GradientDrawable background=new GradientDrawable();background.setColor(color);background.setCornerRadius(dp(radius));return background;}
    private TextView text(LinearLayout parent,String value,int size){TextView text=new TextView(this);text.setText(value);text.setTextColor(size<=13?MUTED:INK);text.setTextSize(size);text.setSaveEnabled(false);text.setPadding(0,dp(5),0,dp(5));parent.addView(text);return text;}
    private Button button(LinearLayout parent,String value,Runnable action){Button button=new Button(this);button.setText(value);button.setTextColor(ACCENT);button.setAllCaps(false);button.setMinHeight(dp(48));button.setOnClickListener(v -> action.run());parent.addView(button,new LinearLayout.LayoutParams(-1,-2));return button;}
    private Button primary(LinearLayout parent,String value,Runnable action){Button button=button(parent,value,action);button.setBackgroundTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_enabled},new int[]{}},new int[]{ACCENT,0xFFDAE2DD}));button.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_enabled},new int[]{}},new int[]{Color.WHITE,MUTED}));return button;}
    private Button rowButton(LinearLayout parent,String value,Runnable action){Button button=button(parent,value,action);button.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));return button;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void detachImages(){if(enlarged!=null){enlarged.dismiss();enlarged=null;}if(root!=null)root.removeAllViews();}
    private void clearPhotos(){detachImages();session.clear();reviewName=null;reviewStrength=null;reviewDirections=null;}
    @Override public void onResume(){super.onResume();resumed=true;renderSession();if(!busy&&session.draft==null)checkConnection();}
    @Override public void onPause(){
        resumed=false;generation++;capturing=false;stopCamera();
        if(enlarged!=null){enlarged.dismiss();enlarged=null;}
        if(reviewing&&reviewName!=null)session.draft=new LabelDraft(reviewName.getText().toString(),reviewStrength.getText().toString(),reviewDirections.getText().toString());
        super.onPause();
    }
    @Override public void onStop(){
        if(!isChangingConfigurations()){
            detachImages();session.clear();reviewing=false;busy=false;ready=false;
            showCapture();
        }
        super.onStop();
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==94){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startCamera();else status.setText("Camera permission is needed for label photos. Manual entry remains available.");}}
}
