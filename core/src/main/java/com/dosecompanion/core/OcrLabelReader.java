package com.dosecompanion.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative blind extraction. No medication profile, formulary, prescription or VLM input. */
public final class OcrLabelReader {
    private static final Pattern MEASURE = Pattern.compile(
            "(?<![a-z0-9.,])(\\d+(?:[.,]\\d+)?)\\s*(mcg|ug|mg|g|ml|iu|%)(?![a-z0-9])");
    private static final Pattern IGNORE = Pattern.compile(
            "\\b(take|prendre|prenez|daily|jour|fois|directions|posologie|rx|din|refill|pharmacy|pharmacie|"
            + "patient|doctor|dr|tel|phone|expiry|expires|exp|lot|keep|conserver|store|ingredient|ingredients|"
            + "supplement facts|nutrition|nutritionnel|more|plus|prime|bonus|save|concentration|coeur|cerveau)\\b");
    private static final Pattern FORM = Pattern.compile(
            "\\b(tablets?|capsules?|softgels?|gelules?|comprimes?|strength|teneur)\\b");

    private OcrLabelReader() {}

    public static final class Line {
        public final String text;
        public final float height;
        public Line(String text, float height) { this.text = text; this.height = Math.max(1, height); }
    }

    public static ProductLabel read(List<Line> lines) {
        List<String> strengths = new ArrayList<>();
        String name = "";
        List<String> candidates = new ArrayList<>();
        List<Float> scores = new ArrayList<>();
        float best = 0;
        boolean ambiguous = false;
        for (Line line : lines) {
            String value = ProductLabel.normalize(line.text).trim();
            if (IGNORE.matcher(value).find()) continue;
            Matcher m = MEASURE.matcher(value);
            boolean hasMeasure = false;
            while (m.find()) {
                hasMeasure = true;
                // Ratios, combined strengths and percentages require manual review in this version.
                String key = ProductLabel.strengthKey(m.group());
                if (!key.isEmpty() && !strengths.contains(key)) strengths.add(key);
            }
            if (hasMeasure && value.matches(".*(?:/|\\+).*")) strengths.add("unsupported");
            String candidate = FORM.matcher(MEASURE.matcher(value).replaceAll(" ")).replaceAll(" ")
                    .replaceAll("[®™]", "").trim().replaceAll("\\s+", " ");
            // A package count or address must never become a product name.
            if (candidate.isEmpty() || !candidate.matches(".*[a-z].*")
                    || candidate.matches("^\\d+\\s.*") || candidate.length() > 80
                    || candidate.split(" ").length > 5 || candidate.matches(".*[:/@].*")) continue;
            // A name printed alongside a strength is stronger evidence than font size alone.
            float score = line.height * (hasMeasure ? 3f : 1f);
            candidates.add(candidate); scores.add(score);
            if (score > best) { name = candidate; best = score; }
        }
        for (int i = 0; i < candidates.size(); i++) {
            if (!ProductLabel.nameKey(name).equals(ProductLabel.nameKey(candidates.get(i)))
                    && scores.get(i) >= best * .85f) ambiguous = true;
        }
        ProductLabel result = new ProductLabel(ambiguous ? "" : name, strengths.size() == 1 ? strengths.get(0) : "");
        if (strengths.size() > 1) {
            // Explicitly latch ambiguity; another reader agreeing on one number cannot erase it.
            return result.conflicted();
        }
        return result;
    }
}
