package com.dosecompanion.app;

import android.graphics.Bitmap;
import android.widget.*;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the real guided screens with fictional in-memory photos, without uploading. */
public class GuidedScanningTest {
    @Test public void guidesSectionsRetakesAndExtraViewsWithoutUploading() throws Exception {
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(context.getPackageName().endsWith(".localexperiment"));
        Bitmap front=photo(),instructions=photo(),replacement=photo();
        try(ActivityScenario<CloudLabelActivity> scenario=ActivityScenario.launch(CloudLabelActivity.class)) {
            scenario.onActivity(activity -> {
                CloudScanSession state=session(activity);
                assertEquals("Name & strength",((TextView)value(activity,"captureTitle")).getText().toString());
                assertNull("Consent belongs on photo review, not camera capture",value(activity,"consent"));
                state.acceptPhoto(front);
                assertEquals("Taking instructions",((TextView)value(activity,"captureTitle")).getText().toString());
                assertEquals(CloudScanSession.Page.CAPTURE,state.page);
                // Even code outside the UI cannot upload before the photo-review step.
                state.ready=true;state.checking=false;state.upload();assertFalse(state.busy);
                state.acceptPhoto(instructions);
                assertTrue((Boolean)value(activity,"photoReview"));
                assertFalse(((Button)value(activity,"send")).isEnabled());
                ((CheckBox)value(activity,"consent")).setChecked(true);
                state.captureMore(0);
                assertFalse((Boolean)value(activity,"photoReview"));
                assertEquals("Name & strength",((TextView)value(activity,"captureTitle")).getText().toString());
                state.acceptPhoto(replacement);
                assertTrue(front.isRecycled());assertSame(instructions,state.photos.get(1));
                assertEquals(2,state.photos.size());assertFalse(((CheckBox)value(activity,"consent")).isChecked());
                state.captureMore(-1);state.acceptPhoto(photo());state.captureMore(-1);state.acceptPhoto(photo());
                assertEquals(4,state.photos.size());state.captureMore(-1);
                assertEquals("A fifth photo must not open capture",CloudScanSession.Page.PHOTOS,state.page);
                assertEquals(android.view.View.GONE,((Button)value(activity,"extra")).getVisibility());
                Bitmap required=state.photos.get(1);state.removeExtra(1);assertSame(required,state.photos.get(1));assertFalse(required.isRecycled());
                assertFalse(state.busy);assertNull(state.draft);
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertTrue((Boolean)value(activity,"photoReview"));assertEquals(4,session(activity).photos.size());
                assertFalse(((CheckBox)value(activity,"consent")).isChecked());
                session(activity).captureMore(1);
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals(1,session(activity).retakeIndex);
                assertEquals("Taking instructions",((TextView)value(activity,"captureTitle")).getText().toString());
                session(activity).reviewPhotos();
                // Use the actual optional-photo removal control and verify required views stay intact.
                @SuppressWarnings("unchecked") var actions=(java.util.ArrayList<android.view.View>)value(activity,"photoActions");
                for(android.view.View action:new java.util.ArrayList<>(actions)) if(action instanceof Button&&"Remove".contentEquals(((Button)action).getText())) {action.performClick();break;}
                assertEquals(3,session(activity).photos.size());assertSame(replacement,session(activity).photos.get(0));assertSame(instructions,session(activity).photos.get(1));
            });
            scenario.moveToState(Lifecycle.State.CREATED);
            assertTrue(replacement.isRecycled());assertTrue(instructions.isRecycled());
        } finally {for(Bitmap bitmap:new Bitmap[]{front,instructions,replacement})if(!bitmap.isRecycled())bitmap.recycle();}
    }
    private static Bitmap photo(){return Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);}
    private static Object value(CloudLabelActivity activity,String name){try{Field field=CloudLabelActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(activity);}catch(Exception error){throw new AssertionError(error);}}
    private static CloudScanSession session(CloudLabelActivity activity){return (CloudScanSession)value(activity,"session");}
}
