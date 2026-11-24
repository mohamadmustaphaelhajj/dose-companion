package com.dosecompanion.core;

import java.util.Arrays;
import java.util.List;

final class AgreementChecks {
    private static int checks;
    private AgreementChecks() {}
    static int run() {
        ProductLabel profile = new ProductLabel("Loniten", "2.5 mg");
        check("exact independent agreement", compare(profile, profile, profile) == LabelAgreement.Result.MATCH);
        check("decimal zeroes normalize", compare(profile, new ProductLabel("LONITEN", "2.50 MG"), profile) == LabelAgreement.Result.MATCH);
        check("decimal comma normalizes", ProductLabel.strengthKey("2,5 mg").equals("2.5 mg"));
        check("ambiguous thousands separator rejected", ProductLabel.strengthKey("2,500 mg").isEmpty());
        check("decimal shift is disagreement", compare(profile, new ProductLabel("Loniten", "25 mg"), profile) == LabelAgreement.Result.DISAGREEMENT);
        check("both read wrong strength", compare(new ProductLabel("Loniten", "25 mg"), new ProductLabel("Loniten", "25 mg"), profile)
                == LabelAgreement.Result.PROFILE_MISMATCH);
        check("unit mismatch", compare(profile, new ProductLabel("Loniten", "2.5 g"), profile) == LabelAgreement.Result.DISAGREEMENT);
        check("no silent unit conversion", compare(new ProductLabel("X", "1 g"), new ProductLabel("X", "1000 mg"), null)
                == LabelAgreement.Result.DISAGREEMENT);
        check("brand and generic are not aliases", compare(profile, new ProductLabel("Minoxidil", "2.5 mg"), profile)
                == LabelAgreement.Result.DISAGREEMENT);
        check("missing VLM is not agreement", compare(profile, ProductLabel.UNKNOWN, profile) == LabelAgreement.Result.INCOMPLETE);
        check("complementary readers cannot fill each other's gaps", compare(new ProductLabel("Loniten", ""), new ProductLabel("", "2.5 mg"), null)
                == LabelAgreement.Result.INCOMPLETE);
        check("partial disagreement is visible", compare(new ProductLabel("Loniten", ""), new ProductLabel("Other", "2.5 mg"), null)
                == LabelAgreement.Result.DISAGREEMENT);
        check("unsupported saved profile blocks match", compare(profile, profile, new ProductLabel("Loniten", "2.5 mg/5 ml"))
                == LabelAgreement.Result.PROFILE_MISMATCH);
        ProductLabel separate = new ProductLabel("Loniten", "").merge(new ProductLabel("", "2.5 mg"));
        check("one reader can merge curved-bottle views", separate.complete() && compare(separate, profile, profile) == LabelAgreement.Result.MATCH);
        ProductLabel conflict = separate.merge(new ProductLabel("", "25 mg"));
        check("contradictory views latch", compare(conflict.merge(profile), profile, profile) == LabelAgreement.Result.CONFLICT);
        check("accent and number spacing", compare(new ProductLabel("Oméga-3", "900 mg"), new ProductLabel("Omega3", "900.0 mg"), null)
                == LabelAgreement.Result.MATCH);
        check("microgram glyph", ProductLabel.strengthKey("25 µg").equals("25 mcg"));
        for (String bad : Arrays.asList("0 mg", "-2.5 mg", "2.5/25 mg", "5 mg/ml", "5 mg + 1 mg", "five mg", "900", "2.5 mg daily")) {
            check("unsupported scalar: " + bad, !new ProductLabel("X", bad).complete());
        }
        ProductLabel ocr = read(line("PHARMACY", 25), line("Loniten 2.5 mg", 42), line("Take 1 tablet daily", 18), line("30 tablets", 18));
        check("blind OCR separates directions and count", compare(ocr, profile, profile) == LabelAgreement.Result.MATCH);
        ProductLabel supplement = read(line("COEUR ET CERVEAU", 25), line("Triple concentration", 24), line("Oméga-3", 60),
                line("900 mg", 40), line("Une par jour", 22), line("120 gélules", 18), line("PRIME 20 % DE PLUS", 30));
        check("blind supplement extraction", compare(supplement, new ProductLabel("Omega3", "900 mg"), null) == LabelAgreement.Result.MATCH);
        check("strength-only view remains partial", read(line("2.5 mg", 35), line("1 tablet per day", 18)).name.isEmpty());
        check("directions don't supply strength", read(line("Take 1 tablet 25 mg daily", 20)).strength.isEmpty());
        check("two strengths require review", read(line("Loniten", 35), line("2.5 mg", 25), line("25 mg", 25)).conflict);
        check("two prominent names require review", read(line("Loniten", 35), line("OtherDrug", 35), line("2.5 mg", 20)).name.isEmpty());
        check("ratio requires review", read(line("Drug 5 mg/5 ml", 30)).conflict);
        check("blank scan", !read(line("", 1)).complete());
        check("overlong fields rejected", !new ProductLabel("x".repeat(121), "2.5 mg").complete());
        return checks;
    }
    private static OcrLabelReader.Line line(String text, int height) { return new OcrLabelReader.Line(text, height); }
    private static ProductLabel read(OcrLabelReader.Line... lines) { return OcrLabelReader.read(List.of(lines)); }
    private static LabelAgreement.Result compare(ProductLabel a, ProductLabel b, ProductLabel expected) { return LabelAgreement.compare(a, b, expected); }
    private static void check(String name, boolean condition) { if (!condition) throw new AssertionError(name); checks++; }
}
