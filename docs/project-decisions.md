# Project decisions

## Intended experience

The first user has an Android phone and a Windows laptop. The Android app provides local offline reminders and camera assistance; a later installable web app provides the Windows companion. People configure their own medication and daily times. Start with a free deployment within service quotas and evaluate costs before expanding distribution.

The camera flow is:

1. Prompt: "Show the medication." Read the bottle's label and compare medication name and strength with the scheduled medication. A label match is evidence about packaging, not bottle contents.
2. Prompt: "Take the medication." Assess a sequence of visible hand and mouth movements. Record only what the detector supports; do not claim confirmed swallowing.
3. Ask the user to confirm they have taken the dose. An automatic detector result alone never writes a taken record.
4. Save the scheduled dose and actual timestamp, plus the confirmation method. Preserve uncertainty and correction events rather than silently overwriting history.

Users are assumed to be acting honestly. Vision accuracy and uncertainty remain engineering concerns even without adversarial users. A failed camera attempt must not ask the user to take medication again; provide manual confirmation and record its method.

## Privacy

- Never persist or upload camera video, photos, thumbnails or frame buffers. Analyze in memory on the device and release buffers when the session ends.
- Local model weights and runtime compilation caches may be stored privately. They contain no user camera recordings. Model downloads happen explicitly through the browser; the application retains no network permission.
- Store only the schedule and minimal journal metadata required for history and future synchronization. Do not keep raw OCR text from the rest of a prescription label, which may contain personal information.
- Public GitHub content and demos use fictional medication labels, never a friend's records.
- No analytics, advertising, face identification or biometric templates.
- Future accounts require per-user access rules and a deletion/export design before any personal data is synchronized.

## Reminder behavior

Priority controls escalation, not dose amount or instructions about late/missed doses. The app provides no automatic recommendation to take an additional dose. Uncertainty remains distinct from confirmed, pending and missed states.

Offline support cannot make one disconnected device aware of an entry on another disconnected device. Show synchronization freshness and handle conflicting events when reconnecting. Do not present stale data as current certainty.

## Initial implementation choices

- Native Android application using Java 17 and framework views; minSdk 26.
- A pure Java core module, with scheduling and explicit confirmation rules independent of the interface.
- SQLite storage with transactions, foreign keys, and a unique constraint for a taken dose's scheduled identity.
- Daily times use the schedule's saved time zone; they do not automatically change while traveling.
- Archive rather than hard-delete medications so journal entries retain their context.
- No fake CV results, simulated reminders, example patient records, or placeholder authentication in production flows.
- Java avoids adding a separate language/plugin toolchain to the first milestone; CV, reminders and sync are separate subsequent changes. Revisit UI architecture when the workflows mature.

## Delivery and review

The current experimental OCR/local-VLM design, pinned model, phone benchmarks and reproduction steps are documented in [local vision](local-vision.md). Setup scans yield editable suggestions requiring user confirmation; daily matching compares independent readings with the confirmed profile. Routine OCR searches for confirmed profile details and compares observed strength exactly. The optional VLM receives neither expected values nor the OCR answer. This does not train the model on the user's bottle.

This workspace initially had Java but no Android SDK or Android Studio. After Android Studio and the SDK were installed, the debug APK built successfully using Gradle 8.13 and JDK 22. It was installed and launched on the user's ARM64 API 37 emulator on November 19, 2026, and the initial screen was visually checked. Full workflow and physical-device testing are still required before claiming the Android app works end to end.

Each milestone should produce a reviewable change. Ask the owner before every commit. The connected GitHub tools support changes in existing repositories but currently expose no repository-creation operation; the owner may need to create the empty public repository first.

## Reminder milestone

Reminders use a separate SQLite queue (database version 2), a single local AlarmManager alarm, and explicit notification/alarm permissions. The migration preserves the version 1 journal. Notification actions open a specific scheduled dose, snooze ten minutes, or record uncertainty. Dismissal and uncertainty pause that occurrence without marking it taken. Repeat counts are bounded by priority and stop before the next dose or after two hours.

Keep the validated reminders tree staged before starting camera edits. A tree/patch checkpoint in `.git` allows a later reminder-only commit while preserving uncommitted camera work. No commit is made by checkpointing, and the owner must approve the actual commit. See `docs/reminder-testing.md`.

## Sequential prescription review

The current scanner asks for name, strength and directions separately, with explicit review and confirmation before every transition. It uses OCR without loading the experimental VLM. Directions can be collected in sections and corrected by the user; quantities and reminder times are never inferred. Database v3 adds only a table of user-confirmed directions, preserving the v1/v2 medication, reminder and journal records. Confirmed fields survive rotation while unconfirmed OCR and image buffers are discarded.

## Local extraction feature branch

The owner authorized implementing and committing a local-model experiment on `feature/local-label-extraction`. The branch retains the existing scanner and adds a separate two/three-photo, per-image LFM2.5-VL-1.6B-Extract flow. Model drafts and independent OCR are shown with source photos; differing strengths or directions require explicit human review. Expected prescription values are withheld from the model. Photos and unconfirmed readings are discarded on background/rotation. No cloud inference, automatic dose logging or automatic prescription scheduling is introduced. Build with `-PlocalExperiment=true` for an isolated app and journal. Reproduction and measured results are in [the experiment guide](local-extraction-experiment.md).
