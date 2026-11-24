package com.dosecompanion.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.dosecompanion.core.DoseRules;
import com.dosecompanion.core.DoseSlot;
import com.dosecompanion.core.Medication;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local medication journal with opt-in device reminders. */
public final class MainActivity extends Activity {
    private static final int BACKGROUND = 0xFFF5F6F0;
    private static final int INK = 0xFF19342D;
    private static final int MUTED = 0xFF64766C;
    private static final int GREEN = 0xFF176650;
    private static final int PALE_GREEN = 0xFFE3EEDC;
    private static final int AMBER = 0xFF85541F;
    private static final int PALE_AMBER = 0xFFFFEED4;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private final DateTimeFormatter eventTime =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss XXX", Locale.getDefault());

    private final ExecutorService storageWorker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() { load(); handler.postDelayed(this, 30_000); }
    };
    private DoseStore store;
    private LinearLayout content;
    private int page;
    private boolean loaded;
    private List<Medication> medications = new ArrayList<>();
    private List<DoseSlot> slots = new ArrayList<>();
    private List<DoseStore.HistoryEntry> history = new ArrayList<>();
    private Map<String, DoseRules.Status> statuses = new HashMap<>();
    private AlertDialog medicationDialog;
    private EditText scanName, scanStrength, scanDirections;
    private Map<String, String> directions = new HashMap<>();
    private TextView scanReview;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        page = savedInstanceState == null ? 0 : savedInstanceState.getInt("page", 0);
        store = new DoseStore(this);
        ReminderEngine.createChannels(this);
        render();
        openRequestedDose(getIntent());
    }

    @Override protected void onResume() {
        super.onResume();
        ReminderEngine.WORKER.execute(() -> ReminderEngine.reconcile(getApplicationContext()));
        handler.post(refresh);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openRequestedDose(intent);
    }

    private void openRequestedDose(Intent intent) {
        if (intent == null || !intent.hasExtra("medication_id")) return;
        Intent request = new Intent(intent);
        intent.removeExtra("medication_id");
        storageWorker.execute(() -> {
            DoseSlot slot = ReminderEngine.slotFromIntent(store, request);
            if (slot == null) return;
            DoseRules.Status status = store.status(slot);
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                if (slot.medication.archived) toast("This schedule is archived.");
                else if (status == DoseRules.Status.TAKEN) toast("This dose is already confirmed in your history.");
                else confirmTaken(slot);
            });
        });
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("page", page);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(refresh);
        storageWorker.execute(store::close);
        storageWorker.shutdown();
        super.onDestroy();
    }

    private void load() {
        storageWorker.execute(() -> {
            try {
                List<Medication> nextMedications = store.medications();
                Map<String, String> nextDirections = new HashMap<>();
                for (Medication medication : nextMedications) nextDirections.put(medication.id, store.directions(medication.id));
                List<DoseSlot> nextSlots = DoseRules.today(nextMedications, Instant.now());
                Map<String, DoseRules.Status> nextStatuses = new HashMap<>();
                for (DoseSlot slot : nextSlots) nextStatuses.put(slot.key(), store.status(slot));
                List<DoseStore.HistoryEntry> nextHistory = store.history();
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    medications = nextMedications;
                    directions = nextDirections;
                    slots = nextSlots;
                    statuses = nextStatuses;
                    history = nextHistory;
                    loaded = true;
                    render();
                });
            } catch (Exception error) {
                showError("Couldn't read your local history. Please reopen the app.");
            }
        });
    }

    private void render() {
        LinearLayout root = column();
        root.setBackgroundColor(BACKGROUND);
        // Insets keep content clear of status/navigation bars on edge-to-edge Android versions.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        setContentView(root);
        root.requestApplyInsets();

        LinearLayout brand = row();
        brand.setPadding(dp(24), dp(18), dp(24), dp(12));
        TextView mark = text("+", 24, Color.WHITE, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(shape(GREEN, 14));
        brand.addView(mark, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView name = text("dose companion", 20, INK, true);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1);
        nameParams.setMargins(dp(12), 0, 0, 0);
        brand.addView(name, nameParams);
        root.addView(brand);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout widthContainer = column();
        widthContainer.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(widthContainer);
        content = column();
        content.setPadding(dp(24), dp(14), dp(24), dp(24));
        int contentWidth = Math.min(getResources().getDisplayMetrics().widthPixels, dp(720));
        widthContainer.addView(content, new LinearLayout.LayoutParams(contentWidth, -2));

        if (!loaded) {
            content.addView(text("Opening your journal…", 18, MUTED, false));
        } else {
            if (page == 0) renderToday();
            else if (page == 1) renderMedications();
            else renderHistory();
        }

        LinearLayout nav = row();
        nav.setBackgroundColor(Color.WHITE);
        nav.setPadding(dp(12), dp(8), dp(12), dp(8));
        String[] labels = {"Today", "Medications", "History"};
        for (int index = 0; index < labels.length; index++) {
            final int destination = index;
            Button item = button(labels[index], page == index);
            item.setOnClickListener(view -> { page = destination; render(); });
            nav.addView(item, new LinearLayout.LayoutParams(0, dp(52), 1));
        }
        root.addView(nav);
    }

    private void renderToday() {
        eyebrow(Instant.now().atZone(ZoneId.systemDefault()).format(
                DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())));
        heading("Your day, clearly.", "A record you can come back to.");

        renderReminderStatus();

        if (medications.stream().noneMatch(medication -> !medication.archived)) {
            empty("Start with your schedule", "Add a medication and the times you take it. "
                    + "Your daily doses and confirmations will appear here.");
            addButton();
            return;
        }

        long taken = slots.stream().filter(slot -> statuses.get(slot.key()) == DoseRules.Status.TAKEN).count();
        LinearLayout summary = card(GREEN);
        summary.addView(text(taken + " / " + slots.size(), 36, Color.WHITE, true));
        summary.addView(text("today's doses confirmed by you", 15, 0xFFE4EFDF, false));
        content.addView(summary);
        if (slots.isEmpty()) {
            empty("Your next day starts here", "No doses are scheduled for the rest of today. "
                    + "Check Medications for your daily times.");
        } else {
            eyebrow("TODAY'S SCHEDULE");
            for (DoseSlot slot : slots) doseCard(slot);
        }
        content.addView(text("Saved on this device · No account connected", 12, MUTED, false));
    }

    private void doseCard(DoseSlot slot) {
        DoseRules.Status status = statuses.get(slot.key());
        LinearLayout card = card(Color.WHITE);
        card.addView(text(TIME.format(slot.time) + "  ·  " + slot.medication.priority.label,
                13, GREEN, true));
        card.addView(text(slot.medication.name, 22, INK, true));
        card.addView(text(slot.medication.strength + " · " + slot.medication.quantity, 15, MUTED, false));
        card.addView(text("Schedule time zone: " + slot.medication.zone.getId(), 12, MUTED, false));

        if (status == DoseRules.Status.TAKEN) {
            TextView confirmed = text("✓  Confirmed taken by you", 15, GREEN, true);
            confirmed.setPadding(0, dp(14), 0, dp(4));
            card.addView(confirmed);
            for (DoseStore.HistoryEntry entry : history) {
                if (entry.status == DoseRules.Status.TAKEN && entry.scheduledDate.equals(slot.date.toString())
                        && entry.scheduledTime.equals(slot.time.toString())
                        && entry.zone.equals(slot.medication.zone)
                        && entry.medicationId.equals(slot.medication.id)) {
                    card.addView(text("Recorded " + eventTime.format(entry.recordedAt.atZone(entry.zone)),
                            13, MUTED, false));
                    break;
                }
            }
        } else {
            String label = status == DoseRules.Status.UNSURE ? "Unsure whether taken"
                    : slot.scheduledAt().isAfter(Instant.now()) ? "Scheduled" : "Awaiting your confirmation";
            TextView state = text(label, 14, status == DoseRules.Status.UNSURE ? AMBER : MUTED, true);
            state.setPadding(0, dp(10), 0, dp(4));
            card.addView(state);
            LinearLayout actions = row();
            Button confirm = button("Confirm taken", true);
            confirm.setOnClickListener(view -> confirmTaken(slot));
            actions.addView(confirm, new LinearLayout.LayoutParams(0, dp(52), 1));
            if (status != DoseRules.Status.UNSURE) {
                Button unsure = button("Unsure", false);
                unsure.setOnClickListener(view -> markUnsure(slot));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(52), 1);
                params.setMargins(dp(8), 0, 0, 0);
                actions.addView(unsure, params);
            }
            card.addView(actions);
            Button scan = button("Check bottle label", false);
            scan.setOnClickListener(view -> startActivity(ReminderEngine.forSlot(
                    new Intent(this, LabelCameraActivity.class), slot)));
            card.addView(scan);
        }
        content.addView(card);
    }

    private void renderMedications() {
        eyebrow("YOUR ROUTINE");
        heading("Medications", "Your medication, your schedule.");
        addButton();
        List<Medication> active = new ArrayList<>();
        for (Medication medication : medications) if (!medication.archived) active.add(medication);
        if (active.isEmpty()) empty("A fresh start", "No active medications yet. Add your first one above.");
        for (Medication medication : active) {
            LinearLayout card = card(Color.WHITE);
            card.addView(text(medication.priority.label + " priority", 13, GREEN, true));
            card.addView(text(medication.name, 22, INK, true));
            card.addView(text(medication.strength + " · " + medication.quantity, 15, MUTED, false));
            if (!directions.getOrDefault(medication.id, "").isEmpty()) card.addView(text("Reviewed label directions: " + directions.get(medication.id), 14, INK, false));
            List<String> times = new ArrayList<>();
            for (LocalTime time : medication.times) times.add(TIME.format(time));
            card.addView(text("Every day at " + String.join(" and ", times), 16, INK, true));
            card.addView(text(medication.zone.getId(), 13, MUTED, false));
            Button archive = button("Archive schedule", false);
            archive.setOnClickListener(view -> new AlertDialog.Builder(this)
                    .setTitle("Archive " + medication.name + "?")
                    .setMessage("This removes its daily schedule from Today. Your existing history stays saved. "
                            + "To correct a schedule, archive it and add a replacement.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Archive", (dialog, which) -> write(() -> store.archive(medication)))
                    .show());
            card.addView(archive);
            content.addView(card);
        }
        content.addView(text("Times stay in the time zone shown, including when you travel. "
                + "Priority controls reminder frequency; it does not change your dose.", 13, MUTED, false));
    }

    private void renderHistory() {
        eyebrow("YOUR JOURNAL");
        heading("A little peace of mind.", "What you confirmed, and when.");
        if (history.isEmpty()) {
            empty("Your history starts here", "Confirmed doses and unsure entries will appear here. "
                    + "Nothing is automatically marked as taken.");
        }
        for (DoseStore.HistoryEntry entry : history) {
            LinearLayout card = card(Color.WHITE);
            boolean taken = entry.status == DoseRules.Status.TAKEN;
            card.addView(text(taken ? "✓  Confirmed taken" : "?  Marked unsure", 14,
                    taken ? GREEN : AMBER, true));
            card.addView(text(entry.name, 21, INK, true));
            card.addView(text(entry.strength + " · " + entry.quantity, 14, MUTED, false));
            card.addView(text("Recorded " + eventTime.format(entry.recordedAt.atZone(entry.zone)),
                    15, INK, true));
            card.addView(text("Scheduled " + entry.scheduledDate + " at " + entry.scheduledTime
                    + " · " + entry.zone.getId(), 13, MUTED, false));
            card.addView(text(taken ? "Confirmed by you" : "Uncertainty recorded by you; not a taken dose",
                    13, MUTED, false));
            content.addView(card);
        }
        content.addView(text("Stored only on this device. Uninstalling the app removes this history.",
                13, MUTED, false));
    }

    private void confirmTaken(DoseSlot slot) {
        boolean early = slot.scheduledAt().isAfter(Instant.now());
        String message = slot.medication.name + " · " + slot.medication.strength + "\n"
                + slot.medication.quantity + "\n\nScheduled " + slot.date + " at "
                + TIME.format(slot.time) + " (" + slot.medication.zone.getId() + ").\n\n"
                + (early ? "This scheduled time is still in the future. " : "")
                + "Confirm only if you have already taken this dose. "
                + "This records your confirmation at the current time.";
        new AlertDialog.Builder(this).setTitle("Have you taken this dose?")
                .setMessage(message).setNegativeButton("Cancel", null)
                .setPositiveButton("I've taken it", (dialog, which) -> write(() ->
                        store.record(slot, DoseRules.Status.TAKEN, true, Instant.now())))
                .show();
    }

    private void markUnsure(DoseSlot slot) {
        new AlertDialog.Builder(this).setTitle("Unsure whether taken?")
                .setMessage("Your history will record that you are unsure about this dose of "
                        + slot.medication.name + ". It will stay unresolved. "
                        + "You can confirm later if you establish that it was taken.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Mark unsure", (dialog, which) -> write(() ->
                        store.record(slot, DoseRules.Status.UNSURE, false, Instant.now())))
                .show();
    }

    private void addMedication() {
        addMedication("", "", "");
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 82 && result == RESULT_OK && data != null) {
            String review = data.getBooleanExtra("cloud_draft_reviewed", false)
                    ? "You reviewed the cloud reader’s suggestions. Set quantity and times yourself before saving. No dose has been logged."
                    : data.getBooleanExtra("local_draft_reviewed", false)
                    ? "You reviewed the local model drafts against the source photos. Set quantity and times yourself before saving. No dose has been logged."
                    : data.getBooleanExtra("sequence_confirmed", false)
                    ? "You reviewed each scanned field. Check the directions, then set quantity and times yourself. No dose has been logged."
                    : data.getBooleanExtra("guided_match", false)
                    ? "OCR matched the details you entered. Review every field before saving."
                    : data.getBooleanExtra("readers_agree", false)
                            ? "Both readers agreed. Check the bottle and confirm or correct every suggested field."
                            : "The readers did not both confirm these fields. Check the bottle and correct this OCR suggestion manually.";
            if (medicationDialog != null && medicationDialog.isShowing()) {
                scanName.setText(data.getStringExtra("product_name"));
                scanStrength.setText(data.getStringExtra("strength"));
                scanDirections.setText(data.getStringExtra("confirmed_directions"));
                scanReview.setText(review); scanReview.setVisibility(View.VISIBLE);
            } else addMedication(data.getStringExtra("product_name"), data.getStringExtra("strength"), review,
                    data.getStringExtra("confirmed_directions"));
        }
    }

    private void addMedication(String suggestedName, String suggestedStrength, String review) {
        addMedication(suggestedName, suggestedStrength, review, "");
    }
    private void addMedication(String suggestedName, String suggestedStrength, String review, String initialDirections) {
        LinearLayout form = column();
        form.setPadding(dp(24), dp(8), dp(24), dp(8));
        Button scan = button("Scan name, strength and directions", false);
        form.addView(scan);
        Button experiment = button(BuildConfig.CLOUD_READER_ENABLED ? "Read label from photos (cloud)" : "Try local extraction (experiment)", false);
        experiment.setOnClickListener(view -> startActivityForResult(new Intent(this, BuildConfig.CLOUD_READER_ENABLED ? CloudLabelActivity.class : ExtractionLabActivity.class), 82));
        form.addView(experiment);
        scanReview = text(review, 14, AMBER, true);
        scanReview.setVisibility(review.isEmpty() ? View.GONE : View.VISIBLE); form.addView(scanReview);
        EditText name = field(form, "Medication name", "Name as printed on the label", 120);
        EditText strength = field(form, "Printed product strength", "For example, 900 mg (from the label)", 80);
        name.setText(suggestedName); strength.setText(suggestedStrength);
        scanName = name; scanStrength = strength;
        EditText labelDirections = field(form, "Reviewed label directions (optional)", "Confirm the full printed wording", 2000);
        labelDirections.setSingleLine(false); labelDirections.setMinLines(2); labelDirections.setText(initialDirections);
        labelDirections.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(110)));
        scanDirections = labelDirections;
        EditText quantity = field(form, "Quantity per dose", "For example, 1 tablet", 80);
        form.addView(text("Enter your own dose quantity and schedule. The camera checks only the product "
                + "name and printed strength; it does not infer a prescription from bottle directions or capsule count.",
                13, MUTED, false));
        form.addView(text("Daily times (24-hour clock)", 14, INK, true));
        List<LocalTime> times = new ArrayList<>();
        LinearLayout timeRows = column();
        form.addView(timeRows);
        Button addTime = button("+ Add a time", false);
        addTime.setOnClickListener(view -> {
            if (times.size() >= 8) { toast("Choose up to 8 daily times."); return; }
            new TimePickerDialog(this, (picker, hour, minute) -> {
                LocalTime time = LocalTime.of(hour, minute);
                if (times.contains(time)) { toast("That time is already included."); return; }
                times.add(time);
                times.sort(LocalTime::compareTo);
                showTimeRows(timeRows, times);
            }, 12, 0, true).show();
        });
        form.addView(addTime);
        TextView zone = text("Schedule time zone: " + ZoneId.systemDefault().getId(), 13, MUTED, false);
        zone.setPadding(0, dp(4), 0, dp(16));
        form.addView(zone);
        form.addView(text("Reminder priority", 14, INK, true));
        Spinner priority = new Spinner(this);
        priority.setMinimumHeight(dp(48));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{"Routine", "Important", "Highest"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        priority.setAdapter(adapter);
        form.addView(priority);
        form.addView(text("Follow-ups: Routine every 30 minutes (2 alerts total); Important every 15 minutes "
                        + "(3 total); Highest every 10 minutes (4 total). Device settings may delay alerts.",
                13, MUTED, false));
        TextView error = text("", 14, 0xFF9A322B, false);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        form.addView(error);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Add medication")
                .setView(scroll).setNegativeButton("Cancel", null).setPositiveButton("Confirm details and save", null).create();
        medicationDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (medicationDialog == dialog) { medicationDialog = null; scanName = null; scanStrength = null; scanDirections = null; scanReview = null; }
        });
        scan.setOnClickListener(view -> {
            startActivityForResult(new Intent(this, LabelCameraActivity.class).putExtra(LabelCameraActivity.ENROLL, true)
                    .putExtra("expected_name", name.getText().toString()).putExtra("expected_strength", strength.getText().toString()), 82);
        });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        Medication medication = new Medication(UUID.randomUUID().toString(),
                                name.getText().toString(), strength.getText().toString(),
                                quantity.getText().toString(), times,
                                Medication.Priority.values()[priority.getSelectedItemPosition()],
                                ZoneId.systemDefault(), Instant.now(), false);
                        Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                        save.setEnabled(false);
                        String reviewedDirections = labelDirections.getText().toString();
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                        dialog.setCancelable(false);
                        storageWorker.execute(() -> {
                            try {
                                store.add(medication, reviewedDirections);
                                ReminderEngine.reconcile(getApplicationContext());
                                runOnUiThread(() -> {
                                    if (isDestroyed() || isFinishing()) return;
                                    dialog.dismiss();
                                    page = 0;
                                    load();
                                });
                            } catch (Exception failure) {
                                runOnUiThread(() -> {
                                    if (isDestroyed() || isFinishing()) return;
                                    error.setText("Couldn't save this medication. Please try again.");
                                    save.setEnabled(true);
                                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                                    dialog.setCancelable(true);
                                });
                            }
                        });
                    } catch (IllegalArgumentException failure) {
                        error.setText(failure.getMessage());
                    }
                }));
        dialog.show();
    }

    private void showTimeRows(LinearLayout rows, List<LocalTime> times) {
        rows.removeAllViews();
        for (LocalTime time : new ArrayList<>(times)) {
            Button remove = button(TIME.format(time) + "  ·  Remove", false);
            remove.setOnClickListener(view -> { times.remove(time); showTimeRows(rows, times); });
            rows.addView(remove);
        }
    }

    private EditText field(LinearLayout form, String label, String hint, int maxLength) {
        TextView heading = text(label, 14, INK, true);
        heading.setPadding(0, dp(8), 0, dp(4));
        form.addView(heading);
        EditText input = new EditText(this);
        input.setId(View.generateViewId());
        heading.setLabelFor(input.getId());
        input.setSingleLine(true);
        input.setTextColor(INK);
        input.setTextSize(16);
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        input.setPadding(dp(12), dp(12), dp(12), dp(12));
        input.setBackground(shape(Color.WHITE, 10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
        params.setMargins(0, 0, 0, dp(12));
        form.addView(input, params);
        return input;
    }

    private void write(Runnable operation) {
        storageWorker.execute(() -> {
            try {
                operation.run();
                ReminderEngine.reconcile(getApplicationContext());
                runOnUiThread(() -> { if (!isDestroyed() && !isFinishing()) load(); });
            } catch (IllegalStateException error) {
                showError(error.getMessage());
            } catch (Exception error) {
                showError("Couldn't save your entry. It has not been confirmed; please try again.");
            }
        });
    }

    private void showError(String message) {
        runOnUiThread(() -> { if (!isDestroyed() && !isFinishing()) toast(message); });
    }

    private void renderReminderStatus() {
        boolean enabled = ReminderEngine.enabled(this);
        boolean allowed = ReminderEngine.notificationsAllowed(this);
        boolean exact = ReminderEngine.exactAllowed(this);
        boolean channels = ReminderEngine.channelsAllowed(this);
        LinearLayout card = card(enabled && allowed && exact && channels ? PALE_GREEN : PALE_AMBER);
        String status = !enabled ? "Reminders are off" : !allowed ? "Notifications are blocked"
                : !channels ? "Some reminder categories are blocked"
                : !exact ? "Reminders on · timing may be delayed" : "Reminders are on";
        card.addView(text(status, 16, INK, true));
        card.addView(text("Local reminders work without internet. Sound and timing depend on your device "
                + "settings. You can check a bottle label from a scheduled dose.", 13, MUTED, false));
        if (!enabled) {
            Button enable = button("Enable reminders", true);
            enable.setOnClickListener(view -> enableReminders());
            card.addView(enable);
        } else {
            if (!allowed || !channels) {
                Button permissions = button("Notification settings", true);
                permissions.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())));
                card.addView(permissions);
            }
            if (!exact && Build.VERSION.SDK_INT >= 31) {
                Button precision = button("Allow precise reminders", true);
                precision.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName()))));
                card.addView(precision);
            }
            Button disable = button("Turn reminders off", false);
            disable.setOnClickListener(view -> {
                ReminderEngine.setEnabled(this, false);
                ReminderEngine.WORKER.execute(() -> ReminderEngine.reconcile(getApplicationContext()));
                render();
            });
            card.addView(disable);
        }
        content.addView(card);
    }

    private void enableReminders() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41);
        } else {
            ReminderEngine.setEnabled(this, true);
            ReminderEngine.WORKER.execute(() -> ReminderEngine.reconcile(getApplicationContext()));
            render();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 41) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) enableReminders();
            else {
                ReminderEngine.setEnabled(this, true);
                toast("Notifications are blocked. You can enable them in Notification settings.");
                render();
            }
        }
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    private void addButton() {
        Button add = button("+ Add medication", true);
        add.setOnClickListener(view -> addMedication());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(54));
        params.setMargins(0, dp(4), 0, dp(18));
        content.addView(add, params);
    }

    private void heading(String title, String subtitle) {
        content.addView(text(title, 32, INK, true));
        TextView description = text(subtitle, 16, MUTED, false);
        description.setPadding(0, dp(8), 0, dp(20));
        content.addView(description);
    }

    private void eyebrow(String value) {
        TextView label = text(value.toUpperCase(Locale.getDefault()), 12, GREEN, true);
        label.setLetterSpacing(0.08f);
        label.setPadding(0, dp(12), 0, dp(10));
        content.addView(label);
    }

    private void empty(String title, String description) {
        LinearLayout card = card(PALE_GREEN);
        card.addView(text(title, 22, INK, true));
        TextView detail = text(description, 16, MUTED, false);
        detail.setPadding(0, dp(10), 0, dp(4));
        card.addView(detail);
        content.addView(card);
    }

    private LinearLayout card(int color) {
        LinearLayout card = column();
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        card.setBackground(shape(color, 20));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(14));
        card.setLayoutParams(params);
        return card;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTextColor(primary ? Color.WHITE : GREEN);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(10), dp(4), dp(10), dp(4));
        button.setBackgroundTintList(null);
        android.graphics.drawable.RippleDrawable background = new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(primary ? 0x33FFFFFF : 0x22176650),
                shape(primary ? GREEN : PALE_GREEN, 12), null);
        button.setBackground(background);
        return button;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setFontFeatureSettings("kern");
        text.setLineSpacing(dp(3), 1);
        if (bold) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return text;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
