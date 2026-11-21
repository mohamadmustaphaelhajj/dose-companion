package com.dosecompanion.core;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;

/** Bounded reminders about an unresolved record, never instructions to take another dose. */
public final class ReminderPolicy {
    private ReminderPolicy() {}

    public static int deliveryLimit(Medication.Priority priority) {
        switch (priority) {
            case HIGH: return 4;
            case IMPORTANT: return 3;
            default: return 2;
        }
    }

    public static Instant expiresAt(DoseSlot slot) {
        Instant expires = slot.scheduledAt().plus(Duration.ofHours(2));
        for (int day = 0; day <= 1; day++) {
            for (LocalTime time : slot.medication.times) {
                Instant next = slot.date.plusDays(day).atTime(time).atZone(slot.medication.zone).toInstant();
                if (next.isAfter(slot.scheduledAt()) && next.isBefore(expires)) expires = next;
            }
        }
        return expires;
    }

    public static Instant afterDelivery(Medication.Priority priority, int delivered,
                                        Instant now, Instant expires) {
        if (delivered >= deliveryLimit(priority)) return null;
        long minutes = priority == Medication.Priority.HIGH ? 10
                : priority == Medication.Priority.IMPORTANT ? 15 : 30;
        Instant next = now.plus(Duration.ofMinutes(minutes));
        return next.isBefore(expires) ? next : null;
    }

    public static Instant snooze(Instant now, Instant expires) {
        Instant next = now.plus(Duration.ofMinutes(10));
        return next.isBefore(expires) ? next : null;
    }
}
