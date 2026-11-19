package com.dosecompanion.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/** Identity uses the schedule's date, time and zone, independent of when it is confirmed. */
public final class DoseSlot {
    public final Medication medication;
    public final LocalDate date;
    public final LocalTime time;

    public DoseSlot(Medication medication, LocalDate date, LocalTime time) {
        this.medication = Objects.requireNonNull(medication);
        this.date = Objects.requireNonNull(date);
        this.time = Objects.requireNonNull(time);
        if (!medication.times.contains(time)) {
            throw new IllegalArgumentException("This time is not in the medication schedule.");
        }
    }

    public Instant scheduledAt() {
        // A gap moves forward; an overlapping clock time creates one slot, not two.
        return date.atTime(time).atZone(medication.zone).toInstant();
    }

    public String key() {
        return medication.id + "|" + date + "|" + time + "|" + medication.zone.getId();
    }
}
