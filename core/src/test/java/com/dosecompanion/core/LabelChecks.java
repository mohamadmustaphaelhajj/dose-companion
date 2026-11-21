package com.dosecompanion.core;

final class LabelChecks {
    private static int count;
    static int run() {
        LabelMatcher vitamin = new LabelMatcher("Vitamin C", "500 mg");
        equal("wrapped name", vitamin.compare("VITAMIN\nC 500mg Tablets"), LabelMatcher.Result.MATCH);
        equal("decimal", vitamin.compare("Vitamin C 500.0 mg"), LabelMatcher.Result.MATCH);
        equal("wrong amount", vitamin.compare("Vitamin C 1500 mg"), LabelMatcher.Result.CONFLICT);
        equal("wrong unit", vitamin.compare("Vitamin C 500 mcg"), LabelMatcher.Result.CONFLICT);
        equal("ambiguous strength", vitamin.compare("Vitamin C 500mg 250mg"), LabelMatcher.Result.CONFLICT);
        equal("ratio", vitamin.compare("Vitamin C 500mg/ml"), LabelMatcher.Result.CONFLICT);
        equal("ratio with quantity", vitamin.compare("Vitamin C 500mg / 5 ml"), LabelMatcher.Result.CONFLICT);
        equal("OCR letter is not guessed", vitamin.compare("Vitamin C 50Omg"), LabelMatcher.Result.STRENGTH_NOT_SEEN);
        equal("count is not strength", vitamin.compare("Vitamin C 30 tablets"), LabelMatcher.Result.STRENGTH_NOT_SEEN);
        equal("name boundary", vitamin.compare("Vitamin CPlus 500mg"), LabelMatcher.Result.NAME_NOT_SEEN);
        equal("other product", vitamin.compare("Other medication 500mg"), LabelMatcher.Result.NAME_NOT_SEEN);
        equal("unsupported ratio", new LabelMatcher("Example", "10mg/ml").compare("Example 10mg/ml"), LabelMatcher.Result.UNSUPPORTED);
        equal("microgram aliases", new LabelMatcher("Example", "10 mcg").compare("Example 10 µg"), LabelMatcher.Result.MATCH);
        LabelMatcher omega = new LabelMatcher("Omega 3", "900 mg");
        String supplement = "COEUR ET CERVEAU\nTriple concentration\nOméga-3\nAEP/ADH\n900 mg\n"
                + "Une par jour\n120 gélules\nPRIME 20 % DE PLUS\nwebber naturals";
        equal("French supplement and promotion", omega.compare(supplement), LabelMatcher.Result.MATCH);
        equal("joined spelling", new LabelMatcher("Oméga-3", "900mg").compare("Omega3\n900 mg"), LabelMatcher.Result.MATCH);
        equal("similar product number", omega.compare("Omega-30\n900mg"), LabelMatcher.Result.NAME_NOT_SEEN);
        equal("irrelevant volume", omega.compare("Omega-3\n900mg\nBottle volume 120ml"), LabelMatcher.Result.MATCH);
        equal("separate ingredient amounts", omega.compare("Omega-3\n900mg\nEPA 540mg\nDHA 360mg"), LabelMatcher.Result.MATCH);
        equal("ingredient value cannot replace product strength", omega.compare("Omega-3\nTotal 1000mg\nEPA 900mg"), LabelMatcher.Result.CONFLICT);
        equal("ingredient alone is not product strength", omega.read("EPA 900mg").strength, LabelMatcher.Strength.NOT_SEEN);
        equal("directions alone cannot match", omega.compare("Omega-3\nUne par jour\n120 gélules\n20% more"), LabelMatcher.Result.STRENGTH_NOT_SEEN);
        equal("zero strength unsupported", new LabelMatcher("Omega3", "0mg").compare(supplement), LabelMatcher.Result.UNSUPPORTED);
        equal("negative strength unsupported", new LabelMatcher("Omega3", "-900mg").compare(supplement), LabelMatcher.Result.UNSUPPORTED);
        equal("name-only evidence", omega.read("Triple concentration\nOméga-3").nameSeen, true);
        equal("strength-only evidence", omega.read("900 mg\n120 gélules").strength, LabelMatcher.Strength.MATCH);
        LabelStability stable = new LabelStability();
        check("one frame cannot complete", !stable.observe(omega.read(supplement), 0));
        check("two frames cannot complete", !stable.observe(omega.read(supplement), 300));
        check("three spaced readings complete", stable.observe(omega.read(supplement), 600));
        check("completion stays latched through missing frames", stable.observe(omega.read(""), 100000));
        check("completion stays latched after scan stops", stable.isComplete());
        stable.reset();
        check("restart clears completion", !stable.isComplete());
        check("name first", !stable.observe(omega.read("Omega-3"), 0));
        stable.observe(omega.read("Omega-3"), 300);
        stable.observe(omega.read("Omega-3"), 600);
        check("name progress is retained", stable.nameReady(700));
        stable.observe(omega.read(""), 900);
        stable.observe(omega.read("Triple concentration"), 1200);
        stable.observe(omega.read("900 mg"), 1600);
        stable.observe(omega.read("900 mg"), 2000);
        check("curved label fields complete across frames", stable.observe(omega.read("900 mg"), 2400));
        stable.reset();
        for (int i = 0; i < 3; i++) stable.observe(omega.read("900 mg"), i * 300);
        for (int i = 0; i < 3; i++) stable.observe(omega.read("Oméga-3"), 1000 + i * 300);
        check("either order works", stable.isComplete());
        stable.reset();
        stable.observe(omega.read(supplement), 0);
        stable.observe(omega.read(""), 300);
        stable.observe(omega.read(supplement), 600);
        check("one unreadable frame does not lose all evidence", stable.observe(omega.read(supplement), 900));
        stable.reset();
        for (int i = 0; i < 3; i++) stable.observe(omega.read("Omega-3"), i * 300);
        stable.observe(omega.read("9000mg"), 1000);
        check("conflicting strength clears old name evidence", !stable.nameReady(1100));
        for (int i = 0; i < 3; i++) stable.observe(omega.read("900mg"), 1300 + i * 300);
        check("cannot complete using evidence before conflict", !stable.isComplete());
        stable.reset();
        for (int i = 0; i < 3; i++) stable.observe(omega.read("Omega3"), i * 300);
        stable.observe(omega.read(""), 1000);
        stable.observe(omega.read(""), 3000);
        check("long blank interruption clears session", !stable.nameReady(3001));
        stable.reset();
        for (int i = 0; i < 3; i++) stable.observe(omega.read("Omega3"), i * 300);
        check("old fields expire", !stable.nameReady(13000));
        for (int i = 0; i < 3; i++) stable.observe(omega.read("900mg"), 13300 + i * 300);
        check("cannot combine old bottle evidence", !stable.isComplete());
        stable.reset();
        for (int i = 0; i < 10; i++) stable.observe(omega.read(supplement), 100);
        check("duplicate timestamps do not establish confidence", !stable.isComplete());
        stable.observe(omega.read(supplement), 50);
        check("clock rollback resets incomplete scan", !stable.isComplete());
        return count;
    }
    private static void equal(String name, Object actual, Object expected) { check(name, expected.equals(actual)); }
    private static void check(String name, boolean condition) {
        if (!condition) throw new AssertionError(name);
        count++;
    }
}
