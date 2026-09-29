# Friction: Android Focus Blocker — Revised Planning Specification

## 1. Purpose and Product Principle

Friction is a local-first, open-source Android app that reduces impulsive phone use through app blocking and timed interventions using personally meaningful photos.

The goal is behavioral friction, not making distraction technically impossible. Users retain control of their device and a dependable recovery route.

This document records the revised product decisions. Platform implementation choices still require validation before full implementation.

## 2. Target Device and Distribution

Initial validation device:

- Samsung Galaxy S22+
- Android 16
- One UI 8

Prioritize reliable behavior on this device before making broader Android/OEM compatibility claims. Determine the minimum supported Android version during implementation planning.

Initial distribution is through GitHub: source, documentation, release APKs, and SHA-256 checksums. Google Play distribution is not required initially. F-Droid and Obtainium-compatible releases may follow.

## 3. Single On/Off Model

Friction has one app configuration and one global state: **ON** or **OFF**.

- ON: enforce the configured app policies.
- OFF: all restrictions stop, active overlays disappear, and usage accumulation stops.
- There are no multiple profiles, overlapping rules, profile selection, or conflict-resolution rules.
- No restriction remains independently always-on when Friction is off.
- Turning Friction off resets all interruption sessions, but preserves daily usage totals.
- Turning it on starts fresh interruption sessions and immediately reapplies any daily caps already reached.

A general-purpose Boolean rule engine is not required. Future activation methods control this same global state.

## 4. First Iteration Scope

The first usable iteration includes:

1. Kotlin Android application and Jetpack Compose UI.
2. Local app picker and one shared app configuration.
3. Allowed, Interrupted, and Blocked app policies.
4. Manual activation.
5. Manual deactivation gated by a 30-second motivation-photo screen and explicit confirmation.
6. Actual foreground-use tracking.
7. Initial and recurring usage interventions.
8. Optional per-app daily hard caps for Interrupted apps.
9. Motivation-photo selection through Android Photo Picker.
10. Intervention overlay with countdown and Exit-to-Home action.
11. Local persistence and restart/reboot recovery.
12. Essential-function and system recovery access.
13. Clear indication when required permissions prevent enforcement.
14. No backend, account, or analytics.

NFC and geofencing are later iterations, not requirements for the first manual release. Settings-bypass interventions are deferred.

## 5. Per-App Policies

### Allowed

No restrictions and no enforcement usage counter is required.

### Interrupted

Allow use until either:

- accumulated foreground usage reaches the next intervention threshold, or
- the optional daily hard cap is reached.

Example configuration:

```text
Instagram
Initial allowance: 10 minutes
Repeat interval: 5 additional minutes of use
Intervention duration: global setting, default 30 seconds
Session reset: global setting, default 25 minutes away
Daily hard cap: optional, for example 1 hour
```

The initial allowance is cumulative across short breaks, not a wall-clock timer or a sliding-window quota. After a completed intervention, the repeat interval applies until the interruption session resets.

### Blocked

Opening the app while Friction is on immediately shows a blocking screen.

Offer Exit-to-Home and a separate route to the global deactivation flow. Do not introduce per-app temporary unlocks in the first iteration.

## 6. Settings and Editing

Per-app settings for Interrupted apps:

- initial foreground-use threshold,
- repeat foreground-use interval,
- optional daily cap, disabled unless configured.

Global settings:

- ordinary usage-intervention duration: default 30 seconds,
- interruption-session reset duration: default 25 minutes away,
- selected motivation-photo collection.

Manual deactivation always requires 30 seconds, independent of the configurable ordinary intervention duration.

Require Friction to be off before editing restriction configuration, including policies, timing, caps, and future activation/deactivation controls. Editing an app to Allowed must not bypass the deactivation flow. Configuration edits do not erase accumulated daily usage.

## 7. Foreground Usage and Session Resets

Track actual foreground use while Friction is on. Do not count time while the device is locked, the app is backgrounded, or an intervention blocks interaction.

Each Interrupted app has an independent interruption session and daily counter.

### Interruption session

- Accumulate foreground use across short visits.
- Switching apps, going Home, or locking the phone pauses accumulation and begins a continuous absence period.
- Returning before the reset duration preserves progress and ends that absence period.
- A later departure starts a new absence period.
- Reaching the reset duration clears session progress, restores the initial allowance, and clears any unfinished ordinary intervention.
- Completing an intervention grants the configured repeat interval of additional foreground use.
- Time spent viewing an intervention is not app usage and does not itself count as time away from that app.

Example with a 10-minute initial allowance, 5-minute repeat interval, and 25-minute reset:

```text
Use Instagram for 6 minutes -> 6 minutes accumulated
Use another app for 10 minutes -> progress preserved
Use Instagram for 4 minutes -> intervention
Complete intervention -> next intervention after 5 more minutes of use
Leave Instagram for 25 continuous minutes -> fresh 10-minute initial allowance
```

### Pending interventions

- Exit navigates Home; it does not mark the intervention completed.
- Returning before the session resets resumes the remaining countdown.
- Going Home or switching away pauses the countdown.
- Locking the phone pauses the countdown and starts time away.
- The countdown advances only while the intervention is visible and the screen is on.
- Turning Friction off clears pending ordinary interventions.

## 8. Daily Hard Caps

An Interrupted app may also have a daily foreground-use cap, such as 1 hour.

- Count foreground usage only while Friction is on.
- Preserve the daily total across short breaks, session resets, off/on toggles, process death, and reboot.
- Reaching the cap makes the app blocked for the remainder of the local calendar day while Friction is on.
- The daily block takes priority if both thresholds are reached together.
- Completing an ordinary intervention cannot bypass a daily cap.
- Friction OFF still means unrestricted use; it does not clear the daily total.
- Re-enabling Friction immediately blocks apps whose daily cap is already reached.
- Reset daily totals at local midnight, including when the app was not running at midnight.
- Midnight does not reset interruption-session progress or clear an unfinished ordinary intervention. Once the daily block lifts, the ordinary interruption policy still applies.

| Event | Interruption session | Daily usage |
|---|---|---|
| Away for reset duration | Reset | Preserved |
| Friction OFF | Reset; enforcement stops | Preserved; accumulation stops |
| Friction ON | Fresh session | Existing total applies |
| Local midnight | Unchanged, except normal absence reset | Reset |
| Process restart/reboot | Restore and account for absence | Restore for current local day |

Use a local-calendar-day model rather than a rolling 24-hour window. Time-zone and manual-clock changes need an explicit, tested implementation policy; this is not intended to be tamper-proof time enforcement.

## 9. Motivation Photos and Intervention UI

Users explicitly select meaningful photos, such as family, hobbies, travel, or personal goals, through Android Photo Picker. Do not request unrestricted gallery access.

Persist selected-photo access where supported and store only necessary local references/metadata. Rotate or randomly select from the shared collection.

Ordinary intervention screen:

```text
[Personal motivation photo]
You've used Instagram for 10 minutes.
00:24 remaining
[Exit Instagram]
```

Behavior:

- Block interaction with the underlying app.
- Back and outside taps must not dismiss an ordinary usage intervention.
- Home remains available.
- Exit returns to Home; it does not promise to terminate the other app.
- No normal Skip button.
- After the countdown completes, the app may resume unless another restriction, such as its daily cap, applies.
- If photos are absent, deleted, or inaccessible, show a simple text fallback with the same countdown.

Prefer an accessibility overlay or another mechanism validated on the target device. Avoid relying on repeated background activity launches.

## 10. Manual Activation and Deactivation

Manual activation turns Friction on without a waiting period.

Every manual deactivation attempt uses this flow, including a future fallback from an NFC-started session:

```text
Tap Turn off Friction
-> show selected motivation photos and 30-second countdown
-> Friction remains ON throughout
-> enable Turn off Friction after countdown
-> user explicitly confirms
-> Friction OFF; clear interruption sessions, preserve daily totals
```

Offer **Keep focusing** to cancel. Countdown completion alone must never turn Friction off.

Require the deactivation screen to be visible with the screen on for the countdown to advance. Leaving or locking pauses it. Resuming the same attempt continues the remaining countdown; cancelling discards the attempt. After process death or reboot, discard an unconfirmed deactivation attempt and keep the persisted focus state.

Reaching this flow must remain possible from Friction and from blocking screens without creating a per-app bypass.

## 11. Recovery and Essential Access

A separate in-app emergency override feature is unnecessary for the first iteration:

- The 30-second manual deactivation flow provides deliberate access, including future lost-tag recovery.
- Essential calling functions and necessary system recovery interfaces remain accessible without waiting.
- Disabling the accessibility service through Android settings or uninstalling provides a last-resort recovery route if Friction malfunctions.
- Do not obstruct these system recovery routes in the first iteration.

Define and validate the concrete essential-app/system exclusions on the Samsung target device before shipping. Friction must not trap users in its own enforcement UI.

If permissions are removed or enforcement becomes unavailable, clearly distinguish the saved ON state from actual enforcement capability. Do not claim protection that is not working.

## 12. Later Iteration: NFC

NFC controls the same global focus state; tags do not select profiles.

- Scan a registered tag to activate an NFC session.
- NFC-started sessions require a registered tag scan for immediate normal deactivation.
- Manual deactivation without the tag remains available through the mandatory 30-second flow.
- A successful NFC deactivation does not require that countdown.
- A lost or damaged tag must never cause permanent lockout.

Use a random opaque tag identifier mapped locally to Friction, rather than a plain FOCUS_OFF command. Strong cryptographic authentication is not a V1 goal.

Handle duplicate scans, disabled/unsupported NFC, unreadable tags, and replacement registration. Track activation source only where necessary for deactivation behavior; this does not introduce multiple configurations.

## 13. Later Iteration: Geofencing

Support one optional configured location initially.

- When location-based departure behavior is enabled, confirmed departure turns Friction off automatically, without the manual countdown.
- Users choose **Activate when I enter this location**.
- If enabled, confirmed entry turns Friction on.
- If disabled, returning leaves Friction off until manual or NFC activation.
- Automatic deactivation clears interruption sessions but preserves daily totals, just like other deactivation methods.
- Arrival activation starts fresh interruption sessions and honors existing daily totals.

Geofencing is approximate and may be delayed. Use INSIDE / OUTSIDE / UNKNOWN state. Temporary location loss preserves the current on/off state; do not treat UNKNOWN as departure or arrival. Prefer battery-conscious platform mechanisms over continuous GPS polling.

Before this iteration, settle boundary debounce, location radius, startup reconciliation, and whether activation outside the location should be constrained. Arrival/departure automation does not itself imply that constraint. An enabled automatic departure is an intentional exception to NFC-based normal deactivation.

## 14. Persistence and Runtime Recovery

Persist:

- global on/off state,
- app policies and settings,
- selected media references,
- accumulated interruption-session usage and next threshold,
- pending ordinary intervention state,
- last background/absence information,
- per-app daily totals and local date,
- future NFC registration and geofence configuration.

A process/service restart alone must not grant a fresh usage allowance. On recovery, restore state, apply elapsed absence resets where appropriate, and reconcile local-day rollover. Do not count device downtime as foreground use.

Use elapsed-time measurements for active intervals and a deliberate persistence strategy for cross-reboot recovery. Validate correctness instead of assuming all accessibility events arrive or all clocks are interchangeable.

## 15. Technical Direction

Suggested stack:

- Kotlin
- Jetpack Compose
- AccessibilityService for foreground/window observation and enforcement
- Room and/or DataStore for local persistence
- Android Photo Picker
- NFC and battery-conscious geofencing APIs in later iterations
- WorkManager only for suitable work, not as an assumed precise usage timer
- Foreground service only if demonstrably necessary

A simple separation of responsibilities is sufficient:

```text
Configuration and focus-state repository
Foreground usage tracker
Per-app session and daily-cap evaluator
Intervention/blocking controller
Motivation-media repository
Compose configuration and status UI
Later: NFC and geofence adapters
```

Do not classify internal screens of third-party apps. Reels/Home/Explore detection, app modification, private APIs, rooting, and direct integration with tools such as Feurstagram are out of scope.

Validate the foreground-event source, any need for UsageStats supplementation, overlay behavior, and Home action on Android 16 / One UI 8. Multi-window and picture-in-picture counting need a documented support policy before release.

## 16. Privacy

Keep configuration, usage, photos, and future location/NFC data on-device.

- No backend.
- No accounts.
- No analytics.
- No cloud sync.
- Retain runtime counters needed for enforcement; detailed historical statistics are not required.
- Omit INTERNET permission if technically feasible, and verify the packaged app before making that claim.

## 17. Implementation Sequence

### A. Technical spike on the target phone

Prove foreground-package detection, a short usage threshold, a countdown overlay that blocks underlying interaction, Exit-to-Home, and continued Home/system recovery access. Validate lock/unlock and service restart behavior.

NFC is not required for this initial spike.

### B. Manual focus foundation

Build the app picker, policy configuration, global on/off state, persistence, configuration-edit gating, and full blocking.

### C. Usage and caps

Implement cumulative foreground sessions, repeat thresholds, absence resets, pending intervention behavior, daily totals, midnight rollover, and cap precedence.

### D. Motivation and deactivation

Add selected photos, fallback media behavior, ordinary interventions, and the fixed 30-second manual deactivation flow with explicit confirmation.

### E. Recovery and hardening

Validate process death, reboot, service disable/restart, lock/unlock, Samsung battery restrictions, permission loss, invalid photo URIs, and daily rollover. Complete the manual first release.

### F. Physical/context activation

Add NFC, then optional geofence departure and configurable activation on arrival, reusing the same state and counter semantics.

## 18. Acceptance Scenarios

The first release should demonstrate:

1. OFF allows all apps and accumulates no enforcement usage.
2. ON immediately enforces Blocked policies.
3. Interrupted app use accumulates across short visits and excludes lock/background/overlay time.
4. Initial and repeat interventions occur at their configured usage thresholds.
5. Exiting and immediately reopening resumes an unfinished intervention.
6. A full absence-reset period grants the initial allowance again.
7. Daily-cap usage survives absence resets and off/on toggles.
8. Reaching a daily cap blocks the app while ON, including after reactivation.
9. Local midnight clears the daily block without indiscriminately resetting ordinary intervention state.
10. Manual deactivation requires 30 visible seconds followed by explicit confirmation.
11. Cancelling or leaving the deactivation flow does not turn Friction off.
12. Restriction configuration cannot be weakened while ON without deactivation.
13. Reboot/process recovery preserves state and reconciles elapsed absence and date changes.
14. Missing photos use a working text fallback.
15. Essential functions and system recovery remain accessible.

Test timer boundaries with deterministic logic tests, and validate enforcement and recovery on the physical Samsung S22+.

## 19. Deferred Features and Remaining Validation

Deferred product features:

- NFC and geofencing until after the manual first iteration
- schedules
- intervention escalation and photo captions
- statistics and intervention history
- export/import and backup
- widgets and quick-settings tile
- F-Droid packaging
- settings-bypass interventions, only if consistent with recovery access

Not part of the current design:

- multiple profiles or arbitrary AND/OR rule editing
- parental control or device-owner enforcement
- preventing uninstall
- cloud sync, web dashboard, social features, or ML

Remaining implementation decisions are primarily technical: minimum Android version, exact persistence/API choices, essential-app exclusions, multi-window/PiP support, clock-change handling, and measured reliability on the target phone. Resolve these with the prototype and targeted validation rather than expanding the product model.
