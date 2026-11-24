package com.dosecompanion.app;

import com.dosecompanion.core.LabelDraft;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

final class ExtractJson {
    static LabelDraft parse(String output) throws IOException {
        if (output == null || output.length() > 8192) throw new IOException("Model output exceeds the JSON contract.");
        try (JsonReader reader = new JsonReader(new StringReader(output))) {
            reader.setStrictness(Strictness.STRICT); reader.beginObject();
            Map<String, String> fields = new HashMap<>();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if ((!key.equals("product_name") && !key.equals("strength") && !key.equals("directions")) || fields.containsKey(key))
                    throw new IOException("Unexpected or duplicate model field.");
                String value;
                if (reader.peek() == JsonToken.NULL) { reader.nextNull(); value = ""; }
                else if (reader.peek() == JsonToken.STRING) value = reader.nextString();
                else throw new IOException("Model fields must be text or null.");
                fields.put(key, value);
            }
            reader.endObject();
            if (fields.size() != 3 || reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Incomplete or extra model JSON.");
            return new LabelDraft(fields.get("product_name"), fields.get("strength"), fields.get("directions"));
        } catch (IllegalArgumentException | IllegalStateException invalid) { throw new IOException("Invalid model JSON."); }
    }
}
