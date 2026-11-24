package com.dosecompanion.app;

import android.content.Context;
import android.graphics.Bitmap;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real phone HTTP serialization against a local fixture, never a cloud call or patient photo. */
public class CloudReaderSmokeTest {
    @Test public void oneConsentedPhotoSetUsesOneRequestAndKeepsProviderKeyOffPhone() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();assertTrue(BuildConfig.CLOUD_READER_ENABLED);
        assertTrue(context.getPackageName().endsWith(".localexperiment"));
        var preferences=context.getSharedPreferences("cloud_bridge",Context.MODE_PRIVATE);
        String originalAddress=preferences.getString("endpoint",null),originalToken=preferences.getString("token",null);
        String token="synthetic-pairing-token-12345678901234567890";
        AtomicInteger uploads=new AtomicInteger(); ExecutorService worker=Executors.newSingleThreadExecutor();
        Bitmap image=Bitmap.createBitmap(500,500,Bitmap.Config.ARGB_8888);image.eraseColor(android.graphics.Color.WHITE);byte[] jpeg=CloudReader.photo(image);image.recycle();
        try(ServerSocket server=new ServerSocket(0,2,InetAddress.getByName("127.0.0.1"))){
            server.setSoTimeout(5000);
            Future<?> service=worker.submit(() -> {
                try {
                    for(int i=0;i<2;i++)try(Socket socket=server.accept()){
                        socket.setSoTimeout(5000);BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                        String line=input.readLine();boolean post=line.startsWith("POST ");int length=0;boolean authenticated=false;
                        while(!(line=input.readLine()).isEmpty()){
                            if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
                            if(line.equals("Authorization: Bearer "+token))authenticated=true;
                        }
                        assertTrue(authenticated);String body;
                        if(post){
                            char[] data=new char[length];int read=0,n;while(read<length&&(n=input.read(data,read,length-read))>0)read+=n;
                            var payload=com.google.gson.JsonParser.parseString(new String(data)).getAsJsonObject();
                            assertTrue(payload.get("consent").getAsBoolean());assertEquals(2,payload.getAsJsonArray("images").size());uploads.incrementAndGet();
                            body="{\"product_name\":\"Synthetic product\",\"strength\":\"2.5 mg\",\"directions\":\"Take 1 tablet daily.\"}";
                        }else body="{\"ready\":true,\"retention_policy\":\"standard\",\"retention_notice\":\"Synthetic policy\"}";
                        byte[] result=body.getBytes(StandardCharsets.UTF_8);OutputStream output=socket.getOutputStream();
                        output.write(("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: "+result.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));output.write(result);output.flush();
                    }
                }catch(Exception failure){throw new RuntimeException(failure);}
            });
            preferences.edit().putString("endpoint","http://127.0.0.1:"+server.getLocalPort()).putString("token",token).commit();
            try(CloudReader reader=new CloudReader(context)){
                assertTrue(reader.settings().get("ready").getAsBoolean());assertEquals("2.5 mg",reader.read(List.of(jpeg,jpeg)).strength);
            }
            service.get(10,TimeUnit.SECONDS);assertEquals(1,uploads.get());
            try{CloudReader.validateEndpoint("http://example.com");fail("Unencrypted public server accepted");}catch(IOException expected){}
        }finally{
            Arrays.fill(jpeg,(byte)0);worker.shutdownNow();
            var edit=preferences.edit();if(originalAddress==null)edit.remove("endpoint");else edit.putString("endpoint",originalAddress);
            if(originalToken==null)edit.remove("token");else edit.putString("token",originalToken);edit.commit();
        }
    }
}
