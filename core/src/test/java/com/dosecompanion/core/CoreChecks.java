package com.dosecompanion.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Executable domain checks with no Android SDK or third-party test runtime required. */
public final class CoreChecks {
    private static int checks;
    private static final ZoneId TORONTO = ZoneId.of("America/Toronto");
    private static final Instant ENROLLED = Instant.parse("2026-11-19T13:00:00Z");

    public static void main(String[] args) {
        Medication medication = medication("a", TORONTO, ENROLLED, false,
                LocalTime.of(20, 0), LocalTime.NOON);
        List<DoseSlot> slots = DoseRules.today(Collections.singletonList(medication),
                Instant.parse("2026-11-19T17:05:00Z"));
        check("daily slots are sorted", slots.size() == 2 && slots.get(0).time.equals(LocalTime.NOON));
        check("Toronto noon uses the schedule's UTC offset",
                slots.get(0).scheduledAt().equals(Instant.parse("2026-11-19T17:00:00Z")));
        List<DoseSlot> tomorrow = DoseRules.today(Collections.singletonList(medication),
                Instant.parse("2026-11-20T05:00:00Z"));
        check("local midnight creates new dose identities", tomorrow.size() == 2
                && tomorrow.get(0).date.equals(LocalDate.of(2026, 11, 20))
                && !tomorrow.get(0).key().equals(slots.get(0).key()));

        Medication lateEnrollment = medication("late", TORONTO,
                Instant.parse("2026-11-19T19:00:00Z"), false, LocalTime.NOON, LocalTime.of(20, 0));
        List<DoseSlot> firstDay = DoseRules.today(Collections.singletonList(lateEnrollment),
                Instant.parse("2026-11-19T19:05:00Z"));
        check("pre-enrollment doses are excluded", firstDay.size() == 1
                && firstDay.get(0).time.equals(LocalTime.of(20, 0)));
        check("archived schedules are excluded", DoseRules.today(Collections.singletonList(
                medication("archived", TORONTO, ENROLLED, true, LocalTime.NOON)), ENROLLED).isEmpty());
        check("clock rollback before enrollment cannot invent doses", DoseRules.today(
                Collections.singletonList(medication), Instant.parse("2026-11-18T17:00:00Z")).isEmpty());

        expect("duplicate schedule times are rejected", IllegalArgumentException.class,
                () -> medication("duplicate", TORONTO, ENROLLED, false, LocalTime.NOON, LocalTime.NOON));
        expect("a missing schedule is rejected", IllegalArgumentException.class,
                () -> medication("empty", TORONTO, ENROLLED, false));
        expect("sub-minute schedules are rejected", IllegalArgumentException.class,
                () -> medication("seconds", TORONTO, ENROLLED, false, LocalTime.of(12, 0, 1)));
        expect("blank medication names are rejected", IllegalArgumentException.class,
                () -> new Medication("blank", " ", "10 mg", "1 tablet", List.of(LocalTime.NOON),
                        Medication.Priority.ROUTINE, TORONTO, ENROLLED, false));

        check("uncertainty stays unresolved", DoseRules.transition(DoseRules.Status.PENDING,
                DoseRules.Status.UNSURE, false) == DoseRules.Status.UNSURE);
        expect("CV alone cannot mark a dose taken", IllegalStateException.class,
                () -> DoseRules.transition(DoseRules.Status.PENDING, DoseRules.Status.TAKEN, false));
        check("explicit confirmation can resolve uncertainty", DoseRules.transition(DoseRules.Status.UNSURE,
                DoseRules.Status.TAKEN, true) == DoseRules.Status.TAKEN);
        expect("repeated confirmation is rejected", IllegalStateException.class,
                () -> DoseRules.transition(DoseRules.Status.TAKEN, DoseRules.Status.TAKEN, true));
        expect("uncertainty cannot erase a confirmation", IllegalStateException.class,
                () -> DoseRules.transition(DoseRules.Status.TAKEN, DoseRules.Status.UNSURE, false));
        expect("repeated uncertainty is rejected", IllegalStateException.class,
                () -> DoseRules.transition(DoseRules.Status.UNSURE, DoseRules.Status.UNSURE, false));

        Medication utc = medication("utc", ZoneId.of("UTC"), ENROLLED, false, LocalTime.NOON);
        Instant boundary = Instant.parse("2026-11-20T00:30:00Z");
        List<DoseSlot> mixedZones = DoseRules.today(Arrays.asList(medication, utc), boundary);
        check("each schedule keeps its own local date", mixedZones.stream().anyMatch(slot ->
                slot.medication.id.equals("utc") && slot.date.equals(LocalDate.of(2026, 11, 20)))
                && mixedZones.stream().anyMatch(slot -> slot.medication.id.equals("a")
                && slot.date.equals(LocalDate.of(2026, 11, 19))));

        Medication spring = medication("spring", TORONTO, Instant.parse("2026-01-01T00:00:00Z"),
                false, LocalTime.of(2, 30));
        List<DoseSlot> springSlots = DoseRules.today(Collections.singletonList(spring),
                Instant.parse("2026-03-08T12:00:00Z"));
        check("DST gap moves the unavailable time forward", springSlots.size() == 1
                && springSlots.get(0).scheduledAt().equals(Instant.parse("2026-03-08T07:30:00Z")));
        Medication autumn = medication("autumn", TORONTO, Instant.parse("2026-01-01T00:00:00Z"),
                false, LocalTime.of(1, 30));
        List<DoseSlot> autumnSlots = DoseRules.today(Collections.singletonList(autumn),
                Instant.parse("2026-11-01T12:00:00Z"));
        check("DST overlap produces one scheduled dose", autumnSlots.size() == 1
                && autumnSlots.get(0).scheduledAt().equals(Instant.parse("2026-11-01T05:30:00Z")));
        Medication sameName = medication("b", TORONTO, ENROLLED, false, LocalTime.NOON);
        check("same-name medications have distinct dose identities", !new DoseSlot(sameName,
                slots.get(0).date, LocalTime.NOON).key().equals(slots.get(0).key()));
        expect("a dose cannot use a time outside its schedule", IllegalArgumentException.class,
                () -> new DoseSlot(medication, LocalDate.of(2026, 11, 19), LocalTime.of(3, 0)));
        System.out.println("Passed " + checks + " core checks.");
    }

    private static Medication medication(String id, ZoneId zone, Instant created, boolean archived,
                                         LocalTime... times) {
        return new Medication(id, "Example medication", "10 mg", "1 tablet", Arrays.asList(times),
                Medication.Priority.ROUTINE, zone, created, archived);
    }

    private static void check(String name, boolean condition) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }

    private static void expect(String name, Class<? extends Exception> type, Runnable action) {
        try { action.run(); }
        catch (Exception error) {
            if (!type.isInstance(error)) throw new AssertionError(name, error);
            checks++;
            return;
        }
        throw new AssertionError(name + ": expected " + type.getSimpleName());
    }
}
