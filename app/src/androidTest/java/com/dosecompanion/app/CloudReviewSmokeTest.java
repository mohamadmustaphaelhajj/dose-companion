package com.dosecompanion.app;

import android.graphics.Bitmap;
import android.view.*;
import android.widget.*;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.dosecompanion.core.LabelDraft;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class CloudReviewSmokeTest {
    @Test public void uploadAndReturnedDetailsRequireSeparateConfirmationAndClearOnBackground() throws Exception {
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();assertTrue(context.getPackageName().endsWith(".localexperiment"));
        Bitmap a=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888),b=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);
        int before;try(DoseStore store=new DoseStore(context)){before=store.medications().size();}
        try(ActivityScenario<CloudLabelActivity> scenario=ActivityScenario.launch(CloudLabelActivity.class)){
            scenario.onActivity(activity -> {try{
                CloudScanSession session=(CloudScanSession)field("session").get(activity);
                session.photos.addAll(List.of(a,b));session.ready=true;session.checking=false;session.reviewPhotos();
                Button send=(Button)field("send").get(activity);assertFalse(send.isEnabled());
                ((CheckBox)field("consent").get(activity)).setChecked(true);assertTrue(send.isEnabled());
                // Do not click Send: this smoke test performs no cloud upload.
                Method review=CloudLabelActivity.class.getDeclaredMethod("showReview",LabelDraft.class,long.class);review.setAccessible(true);
                review.invoke(activity,new LabelDraft("Synthetic product","2.5 mg","Take 1 tablet daily."),100L);
                View root=activity.getWindow().getDecorView();Button use=(Button)find(root,"Use reviewed details");assertFalse(use.isEnabled());
                CheckBox confirmation=(CheckBox)find(root,"I checked the medication name, strength and complete instructions against the label.");
                confirmation.setChecked(true);assertTrue(use.isEnabled());
                EditText strength=(EditText)described(root,"Printed strength with units");strength.setText("25 mg");assertFalse(use.isEnabled());
            }catch(ReflectiveOperationException error){throw new AssertionError(error);}});
            scenario.moveToState(Lifecycle.State.CREATED);assertTrue(a.isRecycled());assertTrue(b.isRecycled());
        }finally{if(!a.isRecycled())a.recycle();if(!b.isRecycled())b.recycle();}
        try(DoseStore store=new DoseStore(context)){assertEquals(before,store.medications().size());}
    }
    private static Field field(String name)throws NoSuchFieldException{Field value=CloudLabelActivity.class.getDeclaredField(name);value.setAccessible(true);return value;}
    private static View find(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View result=find(((ViewGroup)view).getChildAt(i),text);if(result!=null)return result;}return null;}
    private static View described(View view,String text){if(view.getContentDescription()!=null&&text.contentEquals(view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View result=described(((ViewGroup)view).getChildAt(i),text);if(result!=null)return result;}return null;}
}
