package com.dosecompanion.app;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Only pinned, verified model weights are persisted. Camera input is never passed here. */
final class LocalModel {
    static final String URL = "https://huggingface.co/litert-community/SmolVLM2-2.2B/resolve/"
            + "0b7c01ddf299a4500a4a87224d8e209dacf2578e/SmolVLM2-2.2B.litertlm";
    static final long SIZE = 1511391232L;
    static final String SHA256 = "192e2aa412fb741acb040343d5b80b4785a57d5ffca2f085f7a49dd4b5dc72a7";

    private LocalModel() {}
    static File file(Context context) { return new File(context.getNoBackupFilesDir(), "label-reader.litertlm"); }
    static boolean installed(Context context) { return file(context).length() == SIZE; }

    static synchronized void importFile(Context context, Uri uri) throws IOException {
        if (context.getNoBackupFilesDir().getUsableSpace() < SIZE + 100_000_000L) {
            throw new IOException("Free at least 1.7 GB, then try again.");
        }
        File temp = new File(context.getNoBackupFilesDir(), "label-reader.download");
        try {
            MessageDigest digest = digest();
            long bytes = 0;
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(temp)) {
                if (input == null) throw new IOException("Couldn't open that file.");
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    bytes += read;
                    if (bytes > SIZE) throw new IOException("This is not the supported model file.");
                    output.write(buffer, 0, read); digest.update(buffer, 0, read);
                }
                output.getFD().sync();
            }
            if (bytes != SIZE || !hex(digest.digest()).equals(SHA256)) {
                throw new IOException("Model verification failed. Download the linked model again.");
            }
            if (!temp.renameTo(file(context))) throw new IOException("Couldn't finish installing the model.");
        } finally {
            if (temp.exists() && !temp.delete()) temp.deleteOnExit();
        }
    }

    static void verify(Context context) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = new FileInputStream(file(context))) {
            byte[] buffer = new byte[65536]; int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        if (!hex(digest.digest()).equals(SHA256)) throw new IOException("Model verification failed; reinstall it.");
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
