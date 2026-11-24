package com.dosecompanion.core;

/** Stabilizes each visible field independently; never attaches stale evidence to a new image. */
public final class FieldStability {
    private final Counter name = new Counter(), strength = new Counter();
    public ProductLabel observe(ProductLabel reading, long now) {
        if (reading.conflict) { reset(); return ProductLabel.UNKNOWN; }
        boolean n = name.observe(ProductLabel.nameKey(reading.name), now);
        boolean s = strength.observe(ProductLabel.strengthKey(reading.strength), now);
        return new ProductLabel(n ? reading.name : "", s ? reading.strength : "");
    }
    public void reset() { name.clear(); strength.clear(); }
    private static final class Counter {
        String key = "";
        int count;
        long since, last;
        boolean observe(String value, long now) {
            if (value.isEmpty()) { if (now - last > 2000) clear(); return false; }
            if (!value.equals(key) || now < last || now - last > 2000) {
                key = value; count = 1; since = now;
            } else count++;
            last = now;
            return count >= 3 && now - since >= 650;
        }
        void clear() { key = ""; count = 0; since = last = 0; }
    }
}
