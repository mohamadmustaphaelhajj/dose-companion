package com.dosecompanion.core;
final class ScanSequenceChecks {
    private static int checks;
    static int run() {
        ScanSequence s = new ScanSequence(new ProductLabel("Loniten", "2.5 mg"));
        check(s.step() == ScanSequence.Step.NAME);
        reject(() -> s.confirm("OtherDrug")); check(s.step() == ScanSequence.Step.NAME);
        s.confirm("LONITEN"); check(s.step() == ScanSequence.Step.STRENGTH);
        reject(() -> s.confirm("25 mg")); reject(() -> s.confirm("5 mg")); reject(() -> s.confirm("125 mg"));
        check(s.step() == ScanSequence.Step.STRENGTH);
        s.confirm("2.50 mg"); check(s.step() == ScanSequence.Step.DIRECTIONS);
        reject(() -> s.confirm("")); check(s.step() == ScanSequence.Step.DIRECTIONS);
        s.confirm("Take 1 tablet daily with food"); check(s.step() == ScanSequence.Step.COMPLETE);
        check(s.directions().equals("Take 1 tablet daily with food"));
        check(ScanSequence.appendSection("Take 1 tablet daily", "tablet daily with food").equals("Take 1 tablet daily with food"));
        check(ScanSequence.appendSection("Take 1 tablet", "with food").equals("Take 1 tablet\nwith food"));
        check(ScanSequence.appendSection("Take 1 tablet", "Take 1 tablet").equals("Take 1 tablet"));
        check(ScanSequence.appendSection("", "Read this section").equals("Read this section"));
        ScanSequence setup = new ScanSequence(null); setup.confirm("Vitamin D"); setup.confirm("1000 IU");
        setup.confirm("Follow the reviewed instructions"); check(setup.step() == ScanSequence.Step.COMPLETE);
        return checks;
    }
    private static void check(boolean value) { if (!value) throw new AssertionError("Sequential scan invariant"); checks++; }
    private static void reject(Runnable task) { try { task.run(); throw new AssertionError("Invalid field confirmed"); }
        catch (IllegalArgumentException expected) { checks++; } }
}
