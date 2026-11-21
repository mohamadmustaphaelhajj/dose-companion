package com.dosecompanion.core;

/** Bounded single-bottle scan; independent field evidence tolerates OCR misses and latches success. */
public final class LabelStability {
    private static final long WINDOW_MS = 12000;
    private final Evidence name = new Evidence();
    private final Evidence strength = new Evidence();
    private long previous = -1, blankSince = -1;
    private boolean complete;

    public boolean observe(LabelMatcher.Reading reading, long now) {
        if (complete) return true;
        expire(now);
        if (!reading.supported || reading.strength == LabelMatcher.Strength.CONFLICT) {
            reset(); previous = now; return false;
        }
        if (!reading.hasText) {
            if (blankSince < 0) blankSince = now;
            if (now - blankSince >= 2000) { reset(); previous = now; }
        } else {
            blankSince = -1;
            if (reading.nameSeen) name.add(now);
            if (reading.strength == LabelMatcher.Strength.MATCH) strength.add(now);
        }
        complete = name.ready() && strength.ready();
        return complete;
    }

    public boolean nameReady(long now) { expire(now); return complete || name.ready(); }
    public boolean strengthReady(long now) { expire(now); return complete || strength.ready(); }
    public boolean isComplete() { return complete; }

    private void expire(long now) {
        if (complete) return;
        if (previous >= 0 && (now < previous || now - previous > WINDOW_MS)) reset();
        previous = now;
        name.expire(now);
        strength.expire(now);
    }

    public void reset() {
        name.clear(); strength.clear(); complete = false; previous = -1; blankSince = -1;
    }

    private static final class Evidence {
        int hits;
        long first, last;
        void add(long now) {
            expire(now);
            if (hits == 0) first = now;
            if (hits > 0 && now - last < 200) return;
            hits++; last = now;
        }
        boolean ready() { return hits >= 3 && last - first >= 600; }
        void expire(long now) { if (hits > 0 && now - first > WINDOW_MS) clear(); }
        void clear() { hits = 0; first = 0; last = 0; }
    }
}
