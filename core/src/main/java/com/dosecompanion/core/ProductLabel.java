package com.dosecompanion.core;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A reader's independently extracted fields. Empty fields mean unknown, never a guessed value. */
public final class ProductLabel {
    public static final ProductLabel UNKNOWN = new ProductLabel("", "");
    private static final Pattern SCALAR = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(mcg|ug|mg|g|ml|iu|%)");
    public final String name, strength;
    public final boolean conflict;

    public ProductLabel(String name, String strength) { this(name, strength, false); }

    private ProductLabel(String name, String strength, boolean conflict) {
        this.name = clean(name, 120);
        this.strength = clean(strength, 80);
        this.conflict = conflict;
    }

    public boolean complete() {
        return !conflict && !nameKey(name).isEmpty() && !strengthKey(strength).isEmpty();
    }

    public ProductLabel conflicted() { return new ProductLabel(name, strength, true); }

    /** Merge views from ONE reader in ONE uninterrupted scan; conflicting fields latch rejection. */
    public ProductLabel merge(ProductLabel next) {
        boolean bad = conflict || next.conflict
                || different(name, next.name, false) || different(strength, next.strength, true);
        return new ProductLabel(name.isEmpty() ? next.name : name,
                strength.isEmpty() ? next.strength : strength, bad);
    }

    public static String nameKey(String value) {
        return normalize(value).replaceAll("([a-z])([0-9])", "$1 $2")
                .replaceAll("([0-9])([a-z])", "$1 $2").replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** Decimal punctuation and trailing zeroes normalize; no unit conversion or dose arithmetic. */
    public static String strengthKey(String value) {
        Matcher m = SCALAR.matcher(normalize(value).trim());
        if (!m.matches()) return "";
        // A comma followed by three digits may be a thousands separator. Don't guess.
        if (m.group(1).matches(".*,[0-9]{3,}")) return "";
        BigDecimal amount = new BigDecimal(m.group(1).replace(',', '.'));
        if (amount.signum() <= 0) return "";
        String unit = m.group(2).equals("ug") ? "mcg" : m.group(2);
        return amount.stripTrailingZeros().toPlainString() + " " + unit;
    }

    public static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replace('µ', 'u').replace('μ', 'u');
    }

    private static boolean different(String a, String b, boolean strength) {
        if (a.isEmpty() || b.isEmpty()) return false;
        return !(strength ? strengthKey(a).equals(strengthKey(b)) : nameKey(a).equals(nameKey(b)));
    }

    private static String clean(String value, int limit) {
        if (value == null) return "";
        String result = value.trim();
        return result.length() > limit || result.matches("(?is).*\\p{Cntrl}.*") ? "" : result;
    }
}
