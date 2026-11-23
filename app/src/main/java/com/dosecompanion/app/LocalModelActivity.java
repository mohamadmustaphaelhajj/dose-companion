package com.dosecompanion.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One-time, explicit import of public weights. The app itself has no network permission. */
public final class LocalModelActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private Button install;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad * 2, pad, pad);
        root.setFitsSystemWindows(true); root.setBackgroundColor(0xFFF5F6F0); setContentView(root);
        TextView title = new TextView(this); title.setText("Offline label reader"); title.setTextSize(26);
        root.addView(title);
        TextView info = new TextView(this); info.setTextSize(16); info.setTextColor(Color.DKGRAY);
        info.setText("The experimental second reader runs on this device. Download its 1.51 GB model once, "
                + "then import it here. After import, both readers work offline. Label photos never leave the app "
                + "and are not saved. The journal and reminders also work without this model.\n\n"
                + "Model: SmolVLM2-2.2B, Apache 2.0. Exact file integrity is checked before use. "
                + "This needs a compatible 64-bit phone GPU and several GB of available memory. "
                + "Reading speed and accuracy depend on the phone. Supplement labels can still need manual review.\n"); root.addView(info);
        status = new TextView(this); status.setTextSize(17); root.addView(status); refresh();
        Button download = new Button(this); download.setText("1. Download model in browser");
        download.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(LocalModel.URL))); }
            catch (android.content.ActivityNotFoundException noBrowser) { status.setText("Open the model link from the project README."); }
        }); root.addView(download);
        install = new Button(this); install.setText("2. Import downloaded model");
        install.setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(pick, 81);
        }); root.addView(install);
        Button done = new Button(this); done.setText("Return"); done.setOnClickListener(v -> finish()); root.addView(done);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 81 || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        install.setEnabled(false); status.setText("Copying and verifying model…");
        worker.execute(() -> {
            String message;
            try { LocalModel.importFile(getApplicationContext(), uri); message = "Model installed. Label reading is available offline."; }
            catch (IOException | RuntimeException failure) { message = "Import failed. Check free space and choose the exact linked model file."; }
            final String outcome = message;
            runOnUiThread(() -> { if (!isDestroyed()) { install.setEnabled(true); status.setText(outcome); } });
        });
    }

    private void refresh() { status.setText(LocalModel.installed(this) ? "Model installed." : "Model not installed yet."); }
    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
