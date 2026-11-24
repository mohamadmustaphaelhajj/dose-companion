package com.dosecompanion.core;

import java.util.List;
import java.util.regex.Pattern;

/** Searches observed OCR text for the saved identity; numeric values always come from the image. */
public final class TargetLabelReader {
    private TargetLabelReader() {}
    public static boolean containsName(String text, ProductLabel profile) {
        String target = ProductLabel.nameKey(profile.name);
        return !target.isEmpty() && (" " + ProductLabel.nameKey(text) + " ").contains(" " + target + " ");
    }
    public static boolean directions(String text) {
        return Pattern.compile("\\b(take|prendre|prenez|daily|jour|fois|tablets?|capsules?|comprimes?|gelules?)\\b")
                .matcher(ProductLabel.normalize(text)).find();
    }
    public static ProductLabel read(List<OcrLabelReader.Line> lines, ProductLabel profile) {
        ProductLabel result = ProductLabel.UNKNOWN;
        for (OcrLabelReader.Line line : lines) {
            boolean name = containsName(line.text, profile);
            ProductLabel observed = OcrLabelReader.read(List.of(line));
            String remainder = ProductLabel.normalize(line.text)
                    .replaceAll("\\d+(?:[.,]\\d+)?\\s*(mcg|ug|mg|g|ml|iu|%)", "")
                    .replaceAll("\\b(strength|teneur|tablets?|capsules?|comprimes?|gelules?)\\b", "")
                    .replaceAll("[^a-z0-9]", "");
            if (name || remainder.isEmpty()) {
                // Extract the observed matching span, without replacing an unread name or strength.
                String words = ProductLabel.nameKey(line.text), target = ProductLabel.nameKey(profile.name);
                String found = name ? words.substring(words.indexOf(target), words.indexOf(target) + target.length()) : "";
                ProductLabel part = new ProductLabel(found, observed.strength);
                if (observed.conflict) part = part.conflicted();
                result = result.merge(part);
            }
        }
        return result;
    }
}
