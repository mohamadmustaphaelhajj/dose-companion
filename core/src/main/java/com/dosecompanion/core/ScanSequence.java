package com.dosecompanion.core;

/** Only explicit human confirmation advances a step. Directions are text, never a dose calculation. */
public final class ScanSequence {
    public enum Step { NAME, STRENGTH, DIRECTIONS, COMPLETE }
    private final ProductLabel expected;
    private String name = "", strength = "", directions = "";
    public ScanSequence(ProductLabel expected) { this.expected = expected; }
    public Step step() { return name.isEmpty() ? Step.NAME : strength.isEmpty() ? Step.STRENGTH
            : directions.isEmpty() ? Step.DIRECTIONS : Step.COMPLETE; }
    public String name() { return name; }
    public String strength() { return strength; }
    public String directions() { return directions; }
    public void confirm(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("Read or enter this field before confirming.");
        switch (step()) {
            case NAME:
                if (text.length() > 120 || ProductLabel.nameKey(text).isEmpty()) throw new IllegalArgumentException("Use a product name of up to 120 characters.");
                if (expected != null && !ProductLabel.nameKey(text).equals(ProductLabel.nameKey(expected.name)))
                    throw new IllegalArgumentException("This name differs from the saved details. Check the bottle or edit the medication separately.");
                name = text; break;
            case STRENGTH:
                String key = ProductLabel.strengthKey(text);
                if (key.isEmpty()) throw new IllegalArgumentException("Use one printed amount and unit, such as 2.5 mg.");
                if (expected != null && !key.equals(ProductLabel.strengthKey(expected.strength)))
                    throw new IllegalArgumentException("This strength differs from the saved details. Do not confirm a guessed decimal point.");
                strength = key; break;
            case DIRECTIONS:
                if (text.length() > 2000) throw new IllegalArgumentException("Keep directions under 2,000 characters.");
                directions = text; break;
            default: throw new IllegalStateException("All fields are already confirmed.");
        }
    }
    /** Join visible sections conservatively; a missing overlap stays visibly separated for review. */
    public static String appendSection(String previous, String next) {
        String a = previous.trim(), b = next.trim();
        if (a.isEmpty()) return b;
        if (b.isEmpty() || a.equalsIgnoreCase(b)) return a;
        String[] x = a.split("\\s+"), y = b.split("\\s+");
        for (int n = Math.min(x.length, y.length); n >= 2; n--) {
            boolean same = true;
            for (int i = 0; i < n; i++) if (!x[x.length - n + i].equalsIgnoreCase(y[i])) same = false;
            if (same) return a + (n == y.length ? "" : " " + String.join(" ", java.util.Arrays.copyOfRange(y, n, y.length)));
        }
        return a + "\n" + b;
    }
}
