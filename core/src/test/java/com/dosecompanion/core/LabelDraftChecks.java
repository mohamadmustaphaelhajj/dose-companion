package com.dosecompanion.core;

import java.util.List;

final class LabelDraftChecks {
    static int run() {
        LabelDraft front = new LabelDraft("Loniten", "2.5 mg", null);
        LabelDraft side = new LabelDraft(null, null, "Take 1 tablet daily");
        check("separate views retain name", LabelDraft.unique(List.of(front, side), 0).equals("Loniten"));
        check("separate views retain strength", LabelDraft.unique(List.of(front, side), 1).equals("2.5 mg"));
        check("directions retain original wording", LabelDraft.unique(List.of(front, side), 2).equals("Take 1 tablet daily"));
        check("decimal shift remains unresolved", LabelDraft.unique(List.of(front, new LabelDraft("Loniten", "25 mg", "")), 1).isEmpty());
        check("missing decimal remains unresolved", LabelDraft.unique(List.of(front, new LabelDraft("Loniten", "5 mg", "")), 1).isEmpty());
        check("formatting may agree", LabelDraft.unique(List.of(front, new LabelDraft("LONITEN", "2,50mg", "")), 1).equals("2,50mg"));
        check("different products remain unresolved", LabelDraft.unique(List.of(front, new LabelDraft("Other", "2.5 mg", "")), 0).isEmpty());
        check("different directions require human composition", LabelDraft.unique(List.of(side, new LabelDraft("", "", "Take 1 tablet")), 2).isEmpty());
        check("empty views cannot supply a prescription", LabelDraft.unique(List.of(new LabelDraft(null, null, null)), 2).isEmpty());
        check("invalid strength cannot become a proposal", LabelDraft.unique(List.of(new LabelDraft("", "125", "")), 1).isEmpty());
        check("ambiguous thousands remain unresolved", LabelDraft.unique(List.of(new LabelDraft("", "1,250 mg", "")), 1).isEmpty());
        check("no unit conversion", LabelDraft.unique(List.of(front, new LabelDraft("", "0.0025 g", "")), 1).isEmpty());
        try { new LabelDraft("X".repeat(121), "", ""); throw new AssertionError("long name accepted"); }
        catch (IllegalArgumentException expected) { /* contract */ }
        try { new LabelDraft("", "", "a\0b"); throw new AssertionError("NUL accepted"); }
        catch (IllegalArgumentException expected) { /* contract */ }
        return 14;
    }
    private static void check(String message, boolean value) { if (!value) throw new AssertionError(message); }
}
