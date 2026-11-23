package com.dosecompanion.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.*;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Experimental model provisioning. The browser downloads public weights; the app has no network access. */
public final class ExtractModelActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private final Button[] imports = new Button[2];
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this); LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24, 48, 24, 24); root.setFitsSystemWindows(true);
        scroll.addView(root); setContentView(scroll);
        text(root, "Prepare local experiment", 25);
        text(root, "This branch tests Liquid AI’s LFM2.5-VL-1.6B-Extract entirely on your phone. "
                + "Its two files total 1.31 GB; import them once. Reading then works offline. "
                + "The files are kept outside Android backups. Camera photos and draft readings stay in memory only.\n\n"
                + "This is a research build. Confirm every field against your label. Quantity and reminder times remain your choice. "
                + "Model license: Liquid AI LFM Open License (see project documentation).\n", 16);
        status = text(root, "", 16); refresh();
        for (int i = 0; i < 2; i++) {
            final int index = i;
            text(root, (i == 0 ? "Language model (731 MB)" : "Vision projector (583 MB)"), 18);
            Button download = new Button(this); download.setText("Download " + (i == 0 ? "language model" : "vision projector"));
            download.setOnClickListener(v -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ExtractModels.url(index)))); }
                catch (android.content.ActivityNotFoundException unavailable) { status.setText("Use the pinned download links in docs/local-extraction-experiment.md."); }
            }); root.addView(download);
            imports[i] = new Button(this); imports[i].setText("Import " + (i == 0 ? "language model" : "vision projector"));
            imports[i].setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 91 + index)); root.addView(imports[i]);
        }
        Button done = new Button(this); done.setText("Return to experiment"); done.setOnClickListener(v -> finish()); root.addView(done);
    }
    private TextView text(LinearLayout root, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); root.addView(view); return view;
    }
    private void refresh() { status.setText((ExtractModels.installed(this, 0) ? "Language model imported. " : "Language model needed. ")
            + (ExtractModels.installed(this, 1) ? "Vision projector imported." : "Vision projector needed.")); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if ((request != 91 && request != 92) || result != RESULT_OK || data == null || data.getData() == null) return;
        for (Button button : imports) button.setEnabled(false);
        status.setText("Importing and checking the exact file…");
        worker.execute(() -> {
            String error = null;
            try { ExtractModels.importFile(getApplicationContext(), data.getData(), request - 91); }
            catch (IOException | RuntimeException failure) { error = "Import failed. Check storage and select the exact linked file."; }
            final String message = error;
            runOnUiThread(() -> { if (!isDestroyed()) {
                for (Button button : imports) button.setEnabled(true);
                if (message == null) refresh(); else status.setText(message);
            } });
        });
    }
    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
