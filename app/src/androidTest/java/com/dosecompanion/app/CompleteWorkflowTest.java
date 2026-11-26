package com.dosecompanion.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.view.*;
import android.view.inspector.WindowInspector;
import android.widget.*;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.dosecompanion.core.*;
import com.google.gson.JsonParser;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.*;
import org.junit.runners.MethodSorters;
import static org.junit.Assert.*;

/** Real activity handoff, local HTTP, SQLite, AlarmManager, notification actions and journal UI.
 * Only the synthetic model reply and camera input are substituted. Never targets Lab/user data. */
@android.annotation.TargetApi(29)
@SdkSuppress(minSdkVersion=29)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class CompleteWorkflowTest {
    private static final String NAME="Workflow sample", TOKEN="workflow-test-token-12345678901234567890";
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context context=instrumentation.getTargetContext();

    @Test public void aCloudSetupReminderUncertaintyAndConfirmation() throws Exception {
        assertIsolated();ReminderIntegrationTest.resetIsolated(context);
        var prefs=context.getSharedPreferences("cloud_bridge",0);
        String oldAddress=prefs.getString("endpoint",null),oldToken=prefs.getString("token",null);
        var scannerMonitor=instrumentation.addMonitor(CloudLabelActivity.class.getName(),null,false);
        try(CloudFixture fixture=new CloudFixture();MainSession scenario=new MainSession()) {
            prefs.edit().putString("endpoint","http://127.0.0.1:"+fixture.port()).putString("token",TOKEN).commit();
            awaitMain(scenario,activity -> (Boolean)value(activity,"loaded"));
            scenario.onActivity(activity -> {
                clickIn(activity.getWindow().getDecorView(),"+ Add medication");
                AlertDialog form=(AlertDialog)value(activity,"medicationDialog");
                ((EditText)findHint(form.getWindow().getDecorView(),"For example, 1 tablet")).setText("1 tablet");
                clickIn(form.getWindow().getDecorView(),"Read label from photos (cloud)");
            });
            CloudLabelActivity scanner=(CloudLabelActivity)instrumentation.waitForMonitorWithTimeout(scannerMonitor,10000);
            assertNotNull("Medication form must launch the real cloud screen",scanner);
            AtomicReference<CloudScanSession> scan=new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                CloudScanSession state=(CloudScanSession)value(scanner,"session");scan.set(state);
                assertEquals("Name & strength",((TextView)value(scanner,"captureTitle")).getText().toString());
                state.acceptPhoto(label("Workflow sample","2.5 mg"));
                assertEquals("Taking instructions",((TextView)value(scanner,"captureTitle")).getText().toString());
                state.acceptPhoto(label("Take 1 tablet","daily."));
                assertFalse(((Button)value(scanner,"send")).isEnabled());
            });
            awaitUi(() -> scan.get().ready&&!scan.get().checking);
            progress("Guided capture complete; checking cloud failure and explicit retry.");
            instrumentation.runOnMainSync(() -> {
                ((CheckBox)value(scanner,"consent")).setChecked(true);
                ((Button)value(scanner,"send")).performClick();
            });
            awaitUi(() -> !scan.get().busy&&fixture.uploads.get()==1);
            instrumentation.runOnMainSync(() -> {
                assertTrue(((TextView)value(scanner,"status")).getText().toString().contains("credits"));
                assertEquals(2,scan.get().photos.size());assertNull(scan.get().draft);
                assertFalse(((CheckBox)value(scanner,"consent")).isChecked());
                assertFalse(((Button)value(scanner,"send")).isEnabled());
            });
            Thread.sleep(700);assertEquals("Failure must not retry itself",1,fixture.uploads.get());
            assertJournal(0,0);
            instrumentation.runOnMainSync(() -> {
                ((CheckBox)value(scanner,"consent")).setChecked(true);
                ((Button)value(scanner,"send")).performClick();
            });
            awaitUi(() -> scan.get().draft!=null);
            instrumentation.runOnMainSync(() -> {
                View root=scanner.getWindow().getDecorView();
                Button use=(Button)findText(root,"Use reviewed details");assertFalse(use.isEnabled());
                CheckBox confirm=(CheckBox)findText(root,"I checked the medication name, strength and complete instructions against the label.");
                confirm.setChecked(true);((EditText)value(scanner,"reviewStrength")).setText("2.50 mg");assertFalse(use.isEnabled());
                confirm.setChecked(true);use.performClick();
            });
            awaitMain(scenario,activity -> {
                EditText name=(EditText)value(activity,"scanName");return name!=null&&NAME.contentEquals(name.getText());
            });
            assertJournal(0,0);
            AtomicReference<Instant> due=new AtomicReference<>();
            scenario.onActivity(activity -> {
                AlertDialog form=(AlertDialog)value(activity,"medicationDialog");
                assertEquals("1 tablet",((EditText)findHint(form.getWindow().getDecorView(),"For example, 1 tablet")).getText().toString());
                assertEquals("2.50 mg",((EditText)value(activity,"scanStrength")).getText().toString());
                assertEquals("Take 1 tablet daily.",((EditText)value(activity,"scanDirections")).getText().toString());
                // Missing schedule is validated without saving anything.
                form.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(form.isShowing());
                due.set(Instant.now().plusSeconds(120).truncatedTo(ChronoUnit.MINUTES));
                LocalTime time=due.get().atZone(ZoneId.systemDefault()).toLocalTime();
                addTime(form,time);addTime(form,time.plusMinutes(3));
                form.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            await(() -> {try(DoseStore store=new DoseStore(context)){return store.medications().size()==1;}},10000);
            DoseSlot slot;
            try(DoseStore store=new DoseStore(context)) {
                Medication medication=store.medications().get(0);
                assertEquals(NAME,medication.name);assertEquals("2.50 mg",medication.strength);assertEquals("1 tablet",medication.quantity);
                assertEquals(2,medication.times.size());assertEquals("Take 1 tablet daily.",store.directions(medication.id));assertTrue(store.history().isEmpty());
                ZonedDateTime target=due.get().atZone(medication.zone);slot=new DoseSlot(medication,target.toLocalDate(),target.toLocalTime());
            }
            progress("Reviewed medication saved with two daily times. Waiting for the real OS alarm.");
            // No call to ReminderEngine.process(deliverDue=true): actual AlarmManager must deliver.
            await(() -> notification(slot)!=null,150000);
            StatusBarNotification alert=notification(slot);assertNotNull(alert);
            assertTrue(alert.getPostTime()>=slot.scheduledAt().toEpochMilli()-1000);
            assertJournal(1,0);
            alert.getNotification().contentIntent.send();
            awaitUi(() -> globalText("I've taken it")!=null);
            try(DoseStore store=new DoseStore(context)){assertEquals(DoseRules.Status.PENDING,store.status(slot));}
            instrumentation.runOnMainSync(() -> clickGlobal("Cancel"));assertJournal(1,0);
            alert.getNotification().actions[2].actionIntent.send();
            await(() -> {try(DoseStore store=new DoseStore(context)){return store.status(slot)==DoseRules.Status.UNSURE;}},10000);
            await(() -> notification(slot)==null,10000);
            try(DoseStore store=new DoseStore(context)){assertEquals(1,store.history().size());assertEquals(DoseRules.Status.UNSURE,store.history().get(0).status);}
            progress("Real reminder delivered; opening/cancelling did not confirm. Uncertainty is retained.");
            alert.getNotification().contentIntent.send();awaitUi(() -> globalText("I've taken it")!=null);
            Instant before=Instant.now();instrumentation.runOnMainSync(() -> clickGlobal("I've taken it"));
            await(() -> {try(DoseStore store=new DoseStore(context)){return store.status(slot)==DoseRules.Status.TAKEN;}},10000);
            Instant after=Instant.now();
            try(DoseStore store=new DoseStore(context)) {
                assertEquals(2,store.history().size());DoseStore.HistoryEntry taken=store.history().get(0);
                assertEquals(DoseRules.Status.TAKEN,taken.status);assertFalse(taken.recordedAt.isBefore(before));assertFalse(taken.recordedAt.isAfter(after));
                assertEquals(slot.date.toString(),taken.scheduledDate);assertEquals(slot.time.toString(),taken.scheduledTime);
                try{store.record(slot,DoseRules.Status.TAKEN,true,Instant.now());fail("A dose cannot be confirmed twice");}catch(IllegalStateException expected){}
            }
            alert.getNotification().contentIntent.send();Thread.sleep(500);assertJournal(1,2);
            awaitMain(scenario,activity -> ((List<?>)value(activity,"history")).size()==2);
            scenario.onActivity(activity -> {
                clickIn(activity.getWindow().getDecorView(),"History");
                assertNotNull(findText(activity.getWindow().getDecorView(),"✓  Confirmed taken"));
                assertNotNull(findText(activity.getWindow().getDecorView(),"?  Marked unsure"));
            });
            scenario.recreate();
            awaitMain(scenario,activity -> ((List<?>)value(activity,"history")).size()==2);
            scenario.onActivity(activity -> assertNotNull(findText(activity.getWindow().getDecorView(),"✓  Confirmed taken")));
            assertEquals("Only two explicitly approved HTTP submissions",2,fixture.uploads.get());fixture.assertHealthy();
            progress("Taken timestamp, earlier uncertainty, duplicate protection and recreated history verified.");
        } finally {
            instrumentation.removeMonitor(scannerMonitor);
            ReminderEngine.setEnabled(context,false);ReminderEngine.reconcile(context);
            var edit=prefs.edit();if(oldAddress==null)edit.remove("endpoint");else edit.putString("endpoint",oldAddress);
            if(oldToken==null)edit.remove("token");else edit.putString("token",oldToken);edit.commit();
        }
    }

    /** Invoke separately after `am kill` to prove persistence across a fresh app process. */
    @Test public void bPersistedHistoryAndArchive() throws Exception {
        assertIsolated();assertJournal(1,2);
        try(DoseStore store=new DoseStore(context)) {
            assertEquals(NAME,store.medications().get(0).name);assertEquals("Take 1 tablet daily.",store.directions(store.medications().get(0).id));
            assertEquals(DoseRules.Status.TAKEN,store.history().get(0).status);assertEquals(DoseRules.Status.UNSURE,store.history().get(1).status);
        }
        try(MainSession scenario=new MainSession()) {
            awaitMain(scenario,activity -> (Boolean)value(activity,"loaded"));
            scenario.onActivity(activity -> {clickIn(activity.getWindow().getDecorView(),"History");assertNotNull(findText(activity.getWindow().getDecorView(),NAME));});
            scenario.onActivity(activity -> {clickIn(activity.getWindow().getDecorView(),"Medications");clickIn(activity.getWindow().getDecorView(),"Archive schedule");clickGlobal("Archive");});
            await(() -> {try(DoseStore store=new DoseStore(context)){return store.medications().get(0).archived;}},10000);
            assertJournal(1,2);
            scenario.onActivity(activity -> {clickIn(activity.getWindow().getDecorView(),"History");assertNotNull(findText(activity.getWindow().getDecorView(),"✓  Confirmed taken"));});
        }
        progress("Fresh-process persistence and archiving without history loss verified.");
    }

    private void assertIsolated(){assertTrue("Never target a user's journal",context.getPackageName().endsWith(".remindertest"));assertTrue(BuildConfig.CLOUD_READER_ENABLED);}
    private void assertJournal(int medications,int events){try(DoseStore store=new DoseStore(context)){assertEquals(medications,store.medications().size());assertEquals(events,store.history().size());}}
    private StatusBarNotification notification(DoseSlot slot){for(var item:context.getSystemService(NotificationManager.class).getActiveNotifications())if(slot.key().equals(item.getTag()))return item;return null;}
    private void awaitUi(BooleanSupplier condition)throws Exception{await(() -> {AtomicBoolean result=new AtomicBoolean();instrumentation.runOnMainSync(() -> result.set(condition.getAsBoolean()));return result.get();},15000);}
    private void awaitMain(MainSession scenario,java.util.function.Predicate<MainActivity> condition)throws Exception{await(() -> {AtomicBoolean result=new AtomicBoolean();scenario.onActivity(activity -> result.set(condition.test(activity)));return result.get();},15000);}
    private void await(BooleanSupplier condition,long timeout)throws Exception{long deadline=android.os.SystemClock.elapsedRealtime()+timeout;while(android.os.SystemClock.elapsedRealtime()<deadline){if(condition.getAsBoolean())return;Thread.sleep(100);}assertTrue("Workflow timed out",condition.getAsBoolean());}
    private static Object value(Object target,String name){try{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}catch(Exception error){throw new AssertionError(error);}}
    private static View find(View root,java.util.function.Predicate<View> predicate){if(predicate.test(root))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=find(((ViewGroup)root).getChildAt(i),predicate);if(found!=null)return found;}return null;}
    private static View findText(View root,String text){return find(root,view -> view instanceof TextView&&text.contentEquals(((TextView)view).getText()));}
    private static View findHint(View root,String hint){return find(root,view -> view instanceof EditText&&hint.contentEquals(((EditText)view).getHint()));}
    private static View globalText(String text){List<View> windows=WindowInspector.getGlobalWindowViews();for(int i=windows.size()-1;i>=0;i--){View found=findText(windows.get(i),text);if(found!=null&&found.isShown())return found;}return null;}
    private static void clickGlobal(String text){View view=globalText(text);assertNotNull("Missing control: "+text,view);assertTrue(view.performClick());}
    private static void clickIn(View root,String text){View view=findText(root,text);assertNotNull("Missing control: "+text,view);assertTrue(view.performClick());}
    private static void addTime(AlertDialog form,LocalTime time){
        clickIn(form.getWindow().getDecorView(),"+ Add a time");
        for(View window:WindowInspector.getGlobalWindowViews()) {
            TimePicker picker=(TimePicker)find(window,view -> view instanceof TimePicker);
            if(picker!=null&&picker.isShown()){picker.setHour(time.getHour());picker.setMinute(time.getMinute());((Button)window.findViewById(android.R.id.button1)).performClick();return;}
        }
        fail("Time picker did not open");
    }
    private static Bitmap label(String first,String second){Bitmap image=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.drawColor(Color.WHITE);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.BLACK);paint.setTextSize(36);canvas.drawText(first,30,130,paint);canvas.drawText(second,30,230,paint);return image;}
    private void progress(String text){Bundle status=new Bundle();status.putString("stream","\nWorkflow: "+text+"\n");instrumentation.sendStatus(2,status);}

    /** Notification onNewIntent changes Intent.data. ActivityScenario's original-intent matching
     * cannot track the subsequent recreation reliably; observe the actual activity class instead. */
    private final class MainSession implements AutoCloseable {
        private MainActivity activity;
        MainSession(){
            activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            onActivity(current -> current.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        }
        void onActivity(java.util.function.Consumer<MainActivity> action){instrumentation.runOnMainSync(() -> action.accept(activity));}
        void recreate(){
            var monitor=instrumentation.addMonitor(MainActivity.class.getName(),null,false);
            try {
                onActivity(Activity::recreate);
                Activity next=instrumentation.waitForMonitorWithTimeout(monitor,10000);
                assertNotNull("The recreated journal must launch",next);activity=(MainActivity)next;
                onActivity(current -> current.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            } finally {instrumentation.removeMonitor(monitor);}
        }
        @Override public void close(){onActivity(Activity::finish);instrumentation.waitForIdleSync();}
    }

    private static final class CloudFixture implements AutoCloseable {
        final AtomicInteger uploads=new AtomicInteger();final ServerSocket server;
        final ExecutorService worker=Executors.newSingleThreadExecutor();final AtomicReference<Throwable> failure=new AtomicReference<>();volatile boolean closed;
        CloudFixture()throws IOException {
            server=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"));server.setSoTimeout(500);
            worker.execute(() -> {while(!closed)try(Socket socket=server.accept()){
                socket.setSoTimeout(10000);BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                String request=input.readLine(),line,id="";int length=0;boolean authenticated=false;
                while((line=input.readLine())!=null&&!line.isEmpty()) {
                    String header=line.toLowerCase(Locale.ROOT);if(header.startsWith("content-length:"))length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
                    if(header.startsWith("x-scan-request-id:"))id=line.substring(line.indexOf(':')+1).trim();
                    if(line.equals("Authorization: Bearer "+TOKEN))authenticated=true;
                }
                assertTrue(authenticated);int status=200;String body;
                if(request.startsWith("POST /v1/extract ")) {
                    assertTrue(id.matches("[a-f0-9]{32}"));assertTrue(length>0&&length<1000000);char[] data=new char[length];int read=0;
                    while(read<length){int n=input.read(data,read,length-read);if(n<0)throw new IOException("Incomplete request");read+=n;}
                    var payload=JsonParser.parseString(new String(data)).getAsJsonObject();assertTrue(payload.get("consent").getAsBoolean());assertEquals(2,payload.getAsJsonArray("images").size());
                    if(uploads.incrementAndGet()==1){status=402;body="{\"error\":\"provider_quota_exhausted\"}";}
                    else body="{\"product_name\":\"Workflow sample\",\"strength\":\"2.5 mg\",\"directions\":\"Take 1 tablet daily.\"}";
                }else{assertTrue(request.startsWith("GET /v1/config "));body="{\"ready\":true,\"retention_policy\":\"standard\",\"retention_notice\":\"Synthetic test service. No provider request.\"}";}
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 "+status+" Test\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();
            }catch(SocketTimeoutException idle){}catch(Throwable error){if(!closed)failure.compareAndSet(null,error);}});
        }
        int port(){return server.getLocalPort();}
        void assertHealthy(){if(failure.get()!=null)throw new AssertionError("Synthetic HTTP fixture failed",failure.get());}
        @Override public void close()throws IOException{closed=true;server.close();worker.shutdownNow();}
    }
}
