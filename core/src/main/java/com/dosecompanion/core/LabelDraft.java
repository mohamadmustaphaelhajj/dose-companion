package com.dosecompanion.core;

/** Untrusted model transcription, never a prescription or ingestion decision. */
public final class LabelDraft {
    public final String name, strength, directions;
    public LabelDraft(String name, String strength, String directions) {
        this.name = bounded(name, 120); this.strength = bounded(strength, 80); this.directions = bounded(directions, 2000);
    }
    private static String bounded(String value, int max) {
        String text = value == null ? "" : value.trim();
        if (text.length() > max || text.indexOf('\0') >= 0) throw new IllegalArgumentException("Model field exceeds its contract.");
        return text;
    }
    public static String unique(java.util.List<LabelDraft> views, int field) {
        String found = "", key = "";
        for (LabelDraft view : views) {
            String text = field == 0 ? view.name : field == 1 ? view.strength : view.directions;
            if (text.isEmpty()) continue;
            String normalized = field == 0 ? ProductLabel.nameKey(text) : field == 1 ? ProductLabel.strengthKey(text)
                    : text.replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
            if (normalized.isEmpty() || (!key.isEmpty() && !key.equals(normalized))) return "";
            found = text; key = normalized;
        }
        return found;
    }
}
