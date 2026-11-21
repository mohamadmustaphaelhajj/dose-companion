package com.dosecompanion.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;

final class ReminderChecks {
    private static int checks;

    static int run() {
        Instant noon = Instant.parse("2026-11-19T12:00:00Z");
        Instant expiry = noon.plusSeconds(7200);
        check("routine alerts are bounded", ReminderPolicy.deliveryLimit(Medication.Priority.ROUTINE) == 2);
        check("important alerts are bounded", ReminderPolicy.deliveryLimit(Medication.Priority.IMPORTANT) == 3);
        check("highest alerts are bounded", ReminderPolicy.deliveryLimit(Medication.Priority.HIGH) == 4);
        check("routine follow-up", ReminderPolicy.afterDelivery(Medication.Priority.ROUTINE, 1,
                noon, expiry).equals(noon.plusSeconds(1800)));
        check("important follow-up", ReminderPolicy.afterDelivery(Medication.Priority.IMPORTANT, 1,
                noon, expiry).equals(noon.plusSeconds(900)));
        check("highest follow-up", ReminderPolicy.afterDelivery(Medication.Priority.HIGH, 1,
                noon, expiry).equals(noon.plusSeconds(600)));
        check("stop at the automatic limit", ReminderPolicy.afterDelivery(Medication.Priority.HIGH,
                4, noon, expiry) == null);
        check("do not repeat at or after expiry", ReminderPolicy.afterDelivery(Medication.Priority.ROUTINE,
                1, expiry.minusSeconds(1800), expiry) == null);
        check("snooze is ten minutes", ReminderPolicy.snooze(noon, expiry).equals(noon.plusSeconds(600)));
        check("snooze cannot cross expiry", ReminderPolicy.snooze(expiry.minusSeconds(300), expiry) == null);
        Medication medication = new Medication("schedule", "Example", "10 mg", "1 tablet",
                Arrays.asList(LocalTime.NOON, LocalTime.of(12, 30)), Medication.Priority.HIGH,
                ZoneId.of("UTC"), noon.minusSeconds(3600), false);
        check("repeats stop at the next scheduled dose", ReminderPolicy.expiresAt(new DoseSlot(medication,
                LocalDate.of(2026, 11, 19), LocalTime.NOON)).equals(noon.plusSeconds(1800)));
        check("final daily dose has a two-hour window", ReminderPolicy.expiresAt(new DoseSlot(medication,
                LocalDate.of(2026, 11, 19), LocalTime.of(12, 30))).equals(noon.plusSeconds(9000)));
        Medication midnight = new Medication("midnight", "Example", "10 mg", "1 tablet",
                Arrays.asList(LocalTime.of(23, 50), LocalTime.of(0, 10)), Medication.Priority.ROUTINE,
                ZoneId.of("UTC"), noon, false);
        check("expiry respects the next dose across midnight", ReminderPolicy.expiresAt(new DoseSlot(midnight,
                LocalDate.of(2026, 11, 19), LocalTime.of(23, 50)))
                .equals(Instant.parse("2026-11-20T00:10:00Z")));
        return checks;
    }

    private static void check(String name, boolean condition) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }
}
