package com.dosecompanion.core;
import java.util.List;
final class TargetReaderChecks {
    private static int checks;
    static int run() {
        ProductLabel p = new ProductLabel("Loniten", "2.5 mg");
        ProductLabel value = read(p, "COMMUNITY HEALTH CENTRE", "Jane Example", "Loniten", "2.5 mg", "Take 1 tablet daily");
        check("search ignores unrelated prominent names", value.complete() && value.name.equals("loniten"));
        check("wrong strength stays observed", read(p, "Loniten", "25 mg").strength.equals("25 mg"));
        check("no decimal insertion", !read(p, "Loniten", "25 mg").strength.equals(p.strength));
        check("wrong bottle lacks name", read(p, "OtherDrug", "2.5 mg").name.isEmpty());
        check("similar spelling not a match", read(p, "Loniton", "2.5 mg").name.isEmpty());
        check("substring not name", read(p, "Aloniten", "2.5 mg").name.isEmpty());
        check("missing strength not filled from profile", read(p, "Loniten", "30 tablets").strength.isEmpty());
        check("directions strength not inferred", read(p, "Loniten", "Take 1 tablet 25 mg daily").strength.isEmpty());
        check("multiple strengths conflict", read(p, "Loniten", "2.5 mg", "25 mg").conflict);
        check("ratio rejected", read(p, "Loniten", "2.5 mg/5 ml").conflict);
        check("empty view unknown", !read(p, "").complete());
        check("unit observed faithfully", read(p, "Loniten", "2.5 g").strength.equals("2.5 g"));
        return checks;
    }
    private static ProductLabel read(ProductLabel p, String... lines) {
        return TargetLabelReader.read(java.util.Arrays.stream(lines).map(s -> new OcrLabelReader.Line(s, 20)).toList(), p);
    }
    private static void check(String message, boolean value) { if (!value) throw new AssertionError(message); checks++; }
}
