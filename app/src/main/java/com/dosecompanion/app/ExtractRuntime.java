package com.dosecompanion.app;

import android.content.Context;
import com.dosecompanion.core.LabelDraft;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Worker-owned native resources; cancel and destroy share a lock, never held over generation. */
final class ExtractRuntime implements AutoCloseable {
    static { System.loadLibrary("dose_extract"); }
    static final String PROMPT = "Extract the following from the image:\n\n"
            + "product_name: The printed trade or generic medication/product name only. Exclude pharmacy, store, patient, and address names. Return null if no medication/product name is visible.\n"
            + "strength: Copy the complete printed drug strength text INCLUDING its unit into one JSON string. Preserve decimal points. Do not return a bare number or a tablet count. Return null if no strength is visible; never infer it.\n"
            + "directions: Copy the explicit printed instructions for taking the medication, including quantity and frequency when visible. Preserve the original language and wording. Pharmacy addresses, patient details and bottle counts are not instructions. Return null if no taking instructions are visible.\n"
            + "\nRespond with only a JSON object. All three values must be strings or null. Do not include any text outside the JSON.";
    private final Context context;
    private final Object lock = new Object();
    private volatile boolean cancelled;
    private long handle;
    ExtractRuntime(Context context) { this.context = context.getApplicationContext(); }
    LabelDraft read(byte[] jpeg) throws IOException { return ExtractJson.parse(answer(jpeg)); }
    String answer(byte[] jpeg) throws IOException {
        if (cancelled) throw new IOException("Cancelled.");
        if (handle == 0) {
            ExtractModels.verify(context);
            long created = create(ExtractModels.file(context, 0).getAbsolutePath(), ExtractModels.file(context, 1).getAbsolutePath());
            synchronized (lock) { handle = created; if (cancelled) cancelNative(handle); }
        }
        if (cancelled) throw new IOException("Cancelled.");
        byte[] result = generate(handle, jpeg, PROMPT);
        try {
            if (cancelled) throw new IOException("Cancelled.");
            return new String(result, StandardCharsets.UTF_8);
        } finally { if (result != null) Arrays.fill(result, (byte) 0); }
    }
    void cancel() { cancelled = true; synchronized (lock) { if (handle != 0) cancelNative(handle); } }
    @Override public void close() { synchronized (lock) { if (handle != 0) { destroy(handle); handle = 0; } } }
    private static native long create(String model, String vision) throws IOException;
    private static native byte[] generate(long handle, byte[] jpeg, String system) throws IOException;
    private static native void cancelNative(long handle);
    private static native void destroy(long handle);
}
