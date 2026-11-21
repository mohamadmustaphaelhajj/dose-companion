package com.dosecompanion.core;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Separates printed product identity and strength from directions, counts and promotion text. */
public final class LabelMatcher {
    private static final Pattern MEASUREMENT = Pattern.compile(
            "(?<![a-z0-9.,])(\\d+(?:\\.\\d+)?)\\s*(mcg|ug|mg|g|ml|iu|%)(?![a-z0-9])");
    private static final Pattern RATIO = Pattern.compile(
            "\\d+(?:\\.\\d+)?\\s*(?:mcg|ug|mg|g|ml|iu|%)\\s*/");
    private final String name;
    private final BigDecimal amount;
    private final String unit;
    private final boolean supported;

    public LabelMatcher(String name, String strength) {
        this.name = words(name);
        Matcher value = MEASUREMENT.matcher(normalize(strength).trim());
        supported = !this.name.isEmpty() && value.matches()
                && new BigDecimal(value.group(1)).signum() > 0;
        amount = supported ? new BigDecimal(value.group(1)) : BigDecimal.ZERO;
        unit = supported ? canonicalUnit(value.group(2)) : "";
    }

    public enum Result { MATCH, NAME_NOT_SEEN, STRENGTH_NOT_SEEN, CONFLICT, UNSUPPORTED }
    public enum Strength { NOT_SEEN, MATCH, CONFLICT }

    /** Only evidence flags leave the parser; no raw OCR text is retained. */
    public static final class Reading {
        public final boolean nameSeen, hasText, supported;
        public final Strength strength;
        private Reading(boolean nameSeen, boolean hasText, boolean supported, Strength strength) {
            this.nameSeen = nameSeen;
            this.hasText = hasText;
            this.supported = supported;
            this.strength = strength;
        }
    }

    public Reading read(String observed) {
        String text = normalize(observed);
        boolean nameSeen = supported && containsName(text);
        boolean found = false, conflict = false, relatedSeen = false;
        for (String line : text.split("\\R")) {
            // Read a product/strength field, rather than any matching number in an ingredient list.
            if (!containsName(line) && !primaryStrengthLine(line)) continue;
            Matcher values = MEASUREMENT.matcher(line);
            List<Boolean> related = new ArrayList<>();
            while (values.find()) {
                String candidateUnit = canonicalUnit(values.group(2));
                if (!family(candidateUnit).equals(family(unit))) continue;
                relatedSeen = true;
                boolean same = candidateUnit.equals(unit)
                        && amount.compareTo(new BigDecimal(values.group(1))) == 0;
                found |= same;
                related.add(same);
            }
            boolean lineMatches = related.contains(true);
            boolean lineDiffers = related.contains(false);
            // Other named ingredients on separate lines are not the product's primary strength.
            String remainder = MEASUREMENT.matcher(line).replaceAll("").replaceAll("[^a-z0-9]", "");
            if (lineDiffers && (containsName(line) || remainder.isEmpty() || lineMatches)) conflict = true;
            if (!related.isEmpty() && RATIO.matcher(line).find()) conflict = true;
        }
        if (nameSeen && relatedSeen && !found) conflict = true;
        return new Reading(nameSeen, !words(text).isEmpty(), supported,
                conflict ? Strength.CONFLICT : found ? Strength.MATCH : Strength.NOT_SEEN);
    }

    /** Single-image comparison remains available for OCR integration checks. */
    public Result compare(String observed) {
        Reading reading = read(observed);
        if (!reading.supported) return Result.UNSUPPORTED;
        if (reading.strength == Strength.CONFLICT) return Result.CONFLICT;
        if (!reading.nameSeen) return Result.NAME_NOT_SEEN;
        return reading.strength == Strength.MATCH ? Result.MATCH : Result.STRENGTH_NOT_SEEN;
    }

    private boolean containsName(String text) {
        return !name.isEmpty() && (" " + words(text) + " ").contains(" " + name + " ");
    }

    private boolean primaryStrengthLine(String line) {
        String remainder = " " + words(MEASUREMENT.matcher(line).replaceAll(" ")) + " ";
        for (String token : name.split(" ")) {
            if (!token.isEmpty()) remainder = remainder.replace(" " + token + " ", " ");
        }
        remainder = remainder.replaceAll("\\b(strength|total|teneur|each|per|par|tablet|tablets|"
                + "capsule|capsules|softgel|softgels|gelule|gelules|comprime|comprimes)\\b", "").trim();
        return remainder.isEmpty();
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replace('μ', 'u').replace('µ', 'u');
    }

    private static String words(String text) {
        return normalize(text).replaceAll("([a-z])([0-9])", "$1 $2")
                .replaceAll("([0-9])([a-z])", "$1 $2")
                .replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String canonicalUnit(String value) { return value.equals("mcg") ? "ug" : value; }
    private static String family(String value) {
        return value.equals("mg") || value.equals("g") || value.equals("ug") ? "mass" : value;
    }
}
