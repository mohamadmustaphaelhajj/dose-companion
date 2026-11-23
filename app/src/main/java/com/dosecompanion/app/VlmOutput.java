package com.dosecompanion.app;

import com.dosecompanion.core.ProductLabel;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict, bounded output contract. Prose, duplicate keys and invented schema fail closed. */
final class VlmOutput {
    private static final Pattern NAME_STRENGTH = Pattern.compile("(?i)\\s+(\\d+(?:[.,]\\d+)?\\s*(?:mcg|ug|mg|g|ml|iu|%))$");
    private VlmOutput() {}
    static ProductLabel parse(String value) {
        if (value == null || value.length() > 2048) return ProductLabel.UNKNOWN;
        try (JsonReader json = new JsonReader(new StringReader(value))) {
            json.setStrictness(Strictness.STRICT);
            json.beginObject();
            Set<String> keys = new HashSet<>();
            String name = "", strength = "";
            while (json.hasNext()) {
                String key = json.nextName();
                if ((!key.equals("product_name") && !key.equals("strength")) || !keys.add(key)) {
                    return ProductLabel.UNKNOWN;
                }
                String field;
                if (json.peek() == JsonToken.NULL) { json.nextNull(); field = ""; }
                else if (json.peek() == JsonToken.STRING) field = json.nextString();
                else return ProductLabel.UNKNOWN;
                if (key.equals("product_name")) name = field; else strength = field;
            }
            json.endObject();
            if (keys.size() != 2 || json.peek() != JsonToken.END_DOCUMENT) return ProductLabel.UNKNOWN;
            // If this reader duplicated its own strength at the end of the name, separate it.
            // Neither the saved profile nor OCR output participates in this normalization.
            Matcher suffix = NAME_STRENGTH.matcher(name);
            if (suffix.find() && !ProductLabel.strengthKey(strength).isEmpty()
                    && ProductLabel.strengthKey(suffix.group(1)).equals(ProductLabel.strengthKey(strength))) {
                name = name.substring(0, suffix.start()).trim();
            }
            // A strength-only view must not invent a product identity from its measurement.
            if (!ProductLabel.strengthKey(name).isEmpty()) name = "";
            ProductLabel result = new ProductLabel(name, strength);
            if (!result.strength.isEmpty() && ProductLabel.strengthKey(result.strength).isEmpty()) {
                return ProductLabel.UNKNOWN;
            }
            return result;
        } catch (IOException | IllegalStateException | IllegalArgumentException invalid) {
            return ProductLabel.UNKNOWN;
        }
    }
}
