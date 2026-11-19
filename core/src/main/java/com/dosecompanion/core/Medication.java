package com.dosecompanion.core;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** A user-entered daily schedule. Strength and quantity are descriptive, not calculated. */
public final class Medication {
    public enum Priority {
        ROUTINE("Routine"), IMPORTANT("Important"), HIGH("Highest");

        public final String label;
        Priority(String label) { this.label = label; }
    }

    public final String id;
    public final String name;
    public final String strength;
    public final String quantity;
    public final List<LocalTime> times;
    public final Priority priority;
    public final ZoneId zone;
    public final Instant createdAt;
    public final boolean archived;

    public Medication(String id, String name, String strength, String quantity,
                      List<LocalTime> times, Priority priority, ZoneId zone,
                      Instant createdAt, boolean archived) {
        this.id = text(id, "Medication ID", 100);
        this.name = text(name, "Medication name", 120);
        this.strength = text(strength, "Strength", 80);
        this.quantity = text(quantity, "Quantity per dose", 80);
        Objects.requireNonNull(times, "Schedule is required");
        if (times.isEmpty() || times.size() > 8) {
            throw new IllegalArgumentException("Choose between 1 and 8 daily times.");
        }
        TreeSet<LocalTime> unique = new TreeSet<>();
        for (LocalTime time : times) {
            if (time == null || time.getSecond() != 0 || time.getNano() != 0) {
                throw new IllegalArgumentException("Use times in hours and minutes.");
            }
            if (!unique.add(time)) {
                throw new IllegalArgumentException("Each daily time must be different.");
            }
        }
        this.times = Collections.unmodifiableList(new ArrayList<>(unique));
        this.priority = Objects.requireNonNull(priority);
        this.zone = Objects.requireNonNull(zone);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.archived = archived;
    }

    private static String text(String value, String label, int limit) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        String result = value.trim();
        if (result.length() > limit) {
            throw new IllegalArgumentException(label + " is too long (maximum " + limit + ").");
        }
        return result;
    }
}
