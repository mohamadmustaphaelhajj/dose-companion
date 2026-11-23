package com.dosecompanion.app;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Pinned public weights only. No capture or generated text ever enters this storage path. */
final class ExtractModels {
    static final String REVISION = "6e60b55c1312f52b94b4d8d78f83fb097a1074c1";
    static final String[] NAMES = {"LFM2.5-VL-1.6B-Extract-Q4_K_M.gguf", "mmproj-LFM2.5-VL-1.6B-Extract-Q8_0.gguf"};
    static final long[] SIZES = {730896192L, 583109792L};
    static final String[] HASHES = {"92e9a7e54a618cee4c9fbec74659c66b3f1aed3f6776f42dfba1fb211a19bd4d",
            "759f5c4133ffaa534cbfb19340a1448b61606969a6d63441fea1a7bd9f4ede31"};
    static File file(Context context, int index) { return new File(context.getNoBackupFilesDir(), NAMES[index]); }
    static String url(int index) { return "https://huggingface.co/LiquidAI/LFM2.5-VL-1.6B-Extract-GGUF/resolve/" + REVISION + "/" + NAMES[index]; }
    static boolean installed(Context context, int index) { return file(context, index).length() == SIZES[index]; }
    static boolean installed(Context context) { return installed(context, 0) && installed(context, 1); }
    static synchronized void importFile(Context context, Uri uri, int index) throws IOException {
        if (context.getNoBackupFilesDir().getUsableSpace() < SIZES[index] + 100_000_000L)
            throw new IOException("Free more storage before importing this file.");
        File temporary = new File(context.getNoBackupFilesDir(), "extract-import.partial");
        try {
            MessageDigest hash = digest(); long count = 0;
            try (InputStream in = context.getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(temporary)) {
                if (in == null) throw new IOException("The selected file is unavailable.");
                byte[] buffer = new byte[65536]; int n;
                while ((n = in.read(buffer)) != -1) {
                    count += n; if (count > SIZES[index]) throw new IOException("Choose the exact linked model file.");
                    hash.update(buffer, 0, n); out.write(buffer, 0, n);
                }
                out.getFD().sync();
            }
            if (count != SIZES[index] || !hex(hash.digest()).equals(HASHES[index])) throw new IOException("File integrity check failed.");
            if (!temporary.renameTo(file(context, index))) throw new IOException("Could not finish the import.");
        } finally { if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit(); }
    }
    static void verify(Context context) throws IOException {
        for (int i = 0; i < 2; i++) {
            if (!installed(context, i)) throw new IOException("Import both model files first.");
            MessageDigest hash = digest();
            try (InputStream input = new FileInputStream(file(context, i))) {
                byte[] buffer = new byte[65536]; int n;
                while ((n = input.read(buffer)) != -1) hash.update(buffer, 0, n);
            }
            if (!hex(hash.digest()).equals(HASHES[i])) throw new IOException("Model integrity check failed. Reimport the linked files.");
        }
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
