package com.dosecompanion.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Base64;
import com.dosecompanion.core.LabelDraft;
import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The phone talks only to the configured bridge. No provider API key is stored on the phone. */
final class CloudReader implements AutoCloseable {
    private final String endpoint, token;
    private volatile HttpURLConnection active;
    private volatile boolean cancelled;
    CloudReader(Context context) throws IOException {
        var settings = context.getSharedPreferences("cloud_bridge", Context.MODE_PRIVATE);
        endpoint = settings.getString("endpoint", "http://127.0.0.1:8787"); token = settings.getString("token", "");
        validateEndpoint(endpoint);
        if (token.length() < 32) throw new IOException("Cloud connection needs setup. Open Cloud connection first.");
    }
    static void validateEndpoint(String value) throws IOException {
        try {
            URI uri = new URI(value);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath() == null || uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IOException("Use the server’s base address.");
            boolean local = uri.getHost().equals("127.0.0.1") || uri.getHost().equals("localhost");
            if (!uri.getScheme().equals("https") && !(local && uri.getScheme().equals("http")))
                throw new IOException("Use HTTPS, or the localhost USB development bridge.");
        } catch (URISyntaxException | RuntimeException invalid) { throw new IOException("Enter a valid server address."); }
    }
    JsonObject settings() throws IOException { return JsonParser.parseString(request("/v1/config", null)).getAsJsonObject(); }
    LabelDraft read(List<byte[]> images) throws IOException {
        if (images.size() < 2 || images.size() > 4) throw new IOException("Choose two to four photos.");
        JsonObject body = new JsonObject(); body.addProperty("consent", true); JsonArray encoded = new JsonArray();
        for (byte[] image : images) encoded.add(Base64.encodeToString(image, Base64.NO_WRAP));
        body.add("images", encoded);
        return ExtractJson.parse(request("/v1/extract", body.toString()));
    }
    static byte[] photo(Bitmap source) {
        int edge = Math.max(source.getWidth(), source.getHeight()); Bitmap image = source;
        if (edge > 2000) image = Bitmap.createScaledBitmap(source, source.getWidth()*2000/edge, source.getHeight()*2000/edge, true);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(); image.compress(Bitmap.CompressFormat.JPEG, 94, output); return output.toByteArray();
        } finally { if (image != source) image.recycle(); }
    }
    private String request(String path, String body) throws IOException {
        if (!BuildConfig.CLOUD_READER_ENABLED) throw new IOException("Cloud reading is not enabled in this build.");
        if (cancelled) throw new IOException("Cancelled.");
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint.replaceAll("/+$", "") + path).openConnection();
        active = connection;
        try {
            if (cancelled) throw new IOException("Cancelled.");
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setConnectTimeout(12000); connection.setReadTimeout(170000);
            connection.setRequestProperty("Authorization", "Bearer " + token); connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(data.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(data); }
                finally { Arrays.fill(data, (byte)0); }
            }
            int status = connection.getResponseCode();
            if (status != 200) {
                if (status == 401) throw new IOException("Connection pairing failed. Check Cloud connection settings.");
                if (status == 503) throw new IOException("OpenAI API setup is not complete on the server yet.");
                if (status == 429) throw new IOException("The reader is busy. Wait briefly and try again.");
                throw new IOException("Cloud reading failed. Check the server’s API project, billing and model access, then try again.");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = input.read(buffer)) != -1) { if (output.size()+n > 16384) throw new IOException("Server reply exceeded the expected limit."); output.write(buffer,0,n); }
            }
            if (cancelled) throw new IOException("Cancelled."); return output.toString(StandardCharsets.UTF_8.name());
        } finally { connection.disconnect(); if (active == connection) active = null; }
    }
    void cancel() { cancelled = true; HttpURLConnection connection = active; if (connection != null) connection.disconnect(); }
    @Override public void close() { cancel(); }
}
