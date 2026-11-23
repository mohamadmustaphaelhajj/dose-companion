package com.dosecompanion.core;

import java.util.List;

final class FieldStabilityChecks {
    private static int checks;
    static int run() {
        FieldStability fields = new FieldStability();
        fields.observe(new ProductLabel("Loniten", ""), 0);
        fields.observe(new ProductLabel("Loniten", "2.5 mg"), 350);
        ProductLabel name = fields.observe(new ProductLabel("Loniten", ""), 700);
        check("strength flicker does not restart name", name.name.equals("Loniten") && name.strength.isEmpty());
        fields.reset();
        fields.observe(new ProductLabel("", "2.5 mg"), 0);
        fields.observe(new ProductLabel("Loniten", "2.50 mg"), 350);
        ProductLabel strength = fields.observe(new ProductLabel("", "2.5 mg"), 700);
        check("name flicker does not restart strength", strength.name.isEmpty() && strength.strength.equals("2.5 mg"));
        check("absent fields are never attached to another view", fields.observe(ProductLabel.UNKNOWN, 900).name.isEmpty());
        fields.reset();
        fields.observe(new ProductLabel("Loniten", ""), 0);
        fields.observe(new ProductLabel("Other", ""), 350);
        check("changed name must stabilize again", fields.observe(new ProductLabel("Loniten", ""), 700).name.isEmpty());
        fields.reset();
        fields.observe(new ProductLabel("Loniten", ""), 0);
        fields.observe(new ProductLabel("Loniten", ""), 350);
        check("long gap resets candidate", fields.observe(new ProductLabel("Loniten", ""), 3000).name.isEmpty());
        fields.observe(ProductLabel.UNKNOWN.conflicted(), 3100);
        check("conflict resets evidence", fields.observe(new ProductLabel("Loniten", ""), 3200).name.isEmpty());
        var header = new OcrLabelReader.Line("Community Health Centre", 45);
        var product = new OcrLabelReader.Line("Loniten 2.5 mg", 25);
        var a = OcrLabelReader.read(List.of(header, product));
        var b = OcrLabelReader.read(List.of(product, header));
        check("inline strength distinguishes product from large heading", a.name.equals("loniten"));
        check("selection is independent of OCR line order", a.name.equals(b.name));
        var other = new OcrLabelReader.Line("OtherDrug 25 mg", 25);
        check("competing products remain ambiguous", !OcrLabelReader.read(List.of(product, other)).complete());
        return checks;
    }
    private static void check(String message, boolean value) { if (!value) throw new AssertionError(message); checks++; }
}
