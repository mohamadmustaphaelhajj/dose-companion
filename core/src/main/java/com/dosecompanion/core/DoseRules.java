package com.dosecompanion.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class DoseRules {
    private DoseRules() {}

    public enum Status { PENDING, UNSURE, TAKEN }

    public static List<DoseSlot> today(List<Medication> medications, Instant now) {
        List<DoseSlot> slots = new ArrayList<>();
        for (Medication medication : medications) {
            LocalDate date = now.atZone(medication.zone).toLocalDate();
            LocalDate firstDate = medication.createdAt.atZone(medication.zone).toLocalDate();
            if (medication.archived || date.isBefore(firstDate)) continue;
            for (LocalTime time : medication.times) {
                DoseSlot slot = new DoseSlot(medication, date, time);
                // Do not call doses before enrollment overdue or offer to confirm them.
                if (!slot.scheduledAt().isBefore(medication.createdAt)) slots.add(slot);
            }
        }
        slots.sort(Comparator.comparing(DoseSlot::scheduledAt)
                .thenComparing(slot -> slot.medication.name)
                .thenComparing(DoseSlot::key));
        return slots;
    }

    public static Status transition(Status current, Status requested, boolean userConfirmed) {
        if (current == null || requested == null) {
            throw new IllegalArgumentException("A dose status is required.");
        }
        if (current == Status.TAKEN) {
            throw new IllegalStateException("This dose is already recorded as taken.");
        }
        if (requested == Status.PENDING || current == requested) {
            throw new IllegalStateException("This dose already has that status.");
        }
        if (requested == Status.TAKEN && !userConfirmed) {
            throw new IllegalStateException("Your confirmation is required to record a dose as taken.");
        }
        return requested;
    }
}
