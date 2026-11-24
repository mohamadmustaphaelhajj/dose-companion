package com.dosecompanion.core;

/** Readers never see each other's output or the expected profile. Only this gate compares them. */
public final class LabelAgreement {
    private LabelAgreement() {}
    public enum Result { INCOMPLETE, CONFLICT, DISAGREEMENT, PROFILE_MISMATCH, MATCH }

    public static Result compare(ProductLabel ocr, ProductLabel vision, ProductLabel profile) {
        if (ocr.conflict || vision.conflict) return Result.CONFLICT;
        // Compare available fields first, so a disagreement isn't hidden by another missing field.
        if (differs(ocr.name, vision.name, false) || differs(ocr.strength, vision.strength, true)) {
            return Result.DISAGREEMENT;
        }
        if (!ocr.complete() || !vision.complete()) return Result.INCOMPLETE;
        if (profile != null && (!profile.complete()
                || differs(ocr.name, profile.name, false) || differs(ocr.strength, profile.strength, true))) {
            return Result.PROFILE_MISMATCH;
        }
        return Result.MATCH;
    }

    private static boolean differs(String a, String b, boolean strength) {
        if (a.isEmpty() || b.isEmpty()) return false;
        String ka = strength ? ProductLabel.strengthKey(a) : ProductLabel.nameKey(a);
        String kb = strength ? ProductLabel.strengthKey(b) : ProductLabel.nameKey(b);
        return ka.isEmpty() || kb.isEmpty() || !ka.equals(kb);
    }
}
