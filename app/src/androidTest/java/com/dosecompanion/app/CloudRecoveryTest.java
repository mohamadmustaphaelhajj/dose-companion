package com.dosecompanion.app;

import android.graphics.Bitmap;
import android.widget.*;
import android.view.*;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class CloudRecoveryTest {
    @Test public void explainsQuotaAndTransportFailuresWithoutProviderContent() {
        assertTrue(CloudReader.errorMessage(502,"provider_quota_exhausted").contains("credits"));
        assertTrue(CloudReader.errorMessage(502,"provider_auth_failed").contains("key"));
        assertTrue(CloudReader.errorMessage(429,"provider_rate_limited").contains("rate limiting"));
        assertTrue(CloudReader.errorMessage(502,"incomplete_model_result").contains("No fields"));
        assertFalse(CloudReader.errorMessage(500,"untrusted patient data").contains("patient"));
    }
    @Test public void rotationPreservesOneInflightRequestPhotosAndEditsButBackgroundClearsThem() throws Exception {
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();assertTrue(context.getPackageName().endsWith(".localexperiment"));
        var prefs=context.getSharedPreferences("cloud_bridge",0);String oldAddress=prefs.getString("endpoint",null),oldToken=prefs.getString("token",null);
        Bitmap a=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888),b=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888);
        CountDownLatch received=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();ExecutorService worker=Executors.newSingleThreadExecutor();
        try(ServerSocket socket=new ServerSocket(0,2,InetAddress.getByName("127.0.0.1"));ActivityScenario<CloudLabelActivity> scenario=ActivityScenario.launch(CloudLabelActivity.class)) {
            socket.setSoTimeout(10000);
            Future<?> service=worker.submit(() -> {try(Socket client=socket.accept()){
                client.setSoTimeout(10000);BufferedReader input=new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.UTF_8));
                assertTrue(input.readLine().startsWith("POST "));String line;int length=0;
                while(!(line=input.readLine()).isEmpty())if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
                while(length>0){char[] data=new char[Math.min(length,4096)];int n=input.read(data);if(n<0)throw new IOException("Short body");length-=n;}
                calls.incrementAndGet();received.countDown();assertTrue(release.await(15,TimeUnit.SECONDS));
                byte[] body="{\"product_name\":\"Synthetic product\",\"strength\":\"2.5 mg\",\"directions\":\"Take 1 tablet daily.\"}".getBytes(StandardCharsets.UTF_8);
                OutputStream out=client.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
            }catch(Exception error){throw new RuntimeException(error);}});
            scenario.onActivity(activity -> {try{
                prefs.edit().putString("endpoint","http://127.0.0.1:"+socket.getLocalPort()).putString("token","test-only-pairing-12345678901234567890").commit();
                CloudScanSession state=session(activity);state.photos.addAll(List.of(a,b));state.ready=true;state.checking=false;state.reviewPhotos();
                field("ready").setBoolean(activity,true);((CheckBox)field("consent").get(activity)).setChecked(true);
                Method upload=CloudLabelActivity.class.getDeclaredMethod("upload");upload.setAccessible(true);upload.invoke(activity);upload.invoke(activity);
            }catch(Exception error){throw new AssertionError(error);}});
            assertTrue("Request should reach the local fixture",received.await(10,TimeUnit.SECONDS));
            scenario.recreate();
            scenario.onActivity(activity -> {try{assertTrue(session(activity).busy);assertEquals(2,session(activity).photos.size());assertFalse(a.isRecycled());}catch(Exception error){throw new AssertionError(error);}});
            release.countDown();service.get(10,TimeUnit.SECONDS);
            for(int i=0;i<100;i++){
                boolean[] done={false};scenario.onActivity(activity -> {try{done[0]=session(activity).draft!=null;}catch(Exception error){throw new AssertionError(error);}});
                if(done[0])break;Thread.sleep(50);
            }
            scenario.onActivity(activity -> {try{assertNotNull(session(activity).draft);((EditText)field("reviewStrength").get(activity)).setText("2.50 mg");}catch(Exception error){throw new AssertionError(error);}});
            scenario.recreate();
            scenario.onActivity(activity -> {try{assertEquals("2.50 mg",((EditText)field("reviewStrength").get(activity)).getText().toString());assertEquals(2,session(activity).photos.size());}catch(Exception error){throw new AssertionError(error);}});
            assertEquals("Rotation and double taps must not resubmit",1,calls.get());
            scenario.moveToState(Lifecycle.State.CREATED);assertTrue(a.isRecycled());assertTrue(b.isRecycled());
        }finally{
            release.countDown();worker.shutdownNow();if(!a.isRecycled())a.recycle();if(!b.isRecycled())b.recycle();
            var edit=prefs.edit();if(oldAddress==null)edit.remove("endpoint");else edit.putString("endpoint",oldAddress);if(oldToken==null)edit.remove("token");else edit.putString("token",oldToken);edit.commit();
        }
    }
    private static Field field(String name)throws Exception{Field field=CloudLabelActivity.class.getDeclaredField(name);field.setAccessible(true);return field;}
    private static CloudScanSession session(CloudLabelActivity activity)throws Exception{return (CloudScanSession)field("session").get(activity);}
}
