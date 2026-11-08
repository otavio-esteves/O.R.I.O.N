# Task/reminder recreation test environment

Work package: [ORION-F4-UI-001](../issues/ORION-F4-UI-001.md).

The only test code change imports `createAndroidComposeRule` from
`androidx.compose.ui.test.junit4.v2` in `OrionTaskScreenTest` and
`OrionReminderScreenTest`. Both tests keep their original titles/selectors,
pre-confirmation absence assertions, recreation, enabled checks, single clicks,
10-second waits and final domain-state assertions. No production code, library
version, permission or confirmation policy changed.

The initial current API37 default suite passed 323/325 with zero skips. Both
recreation selectors failed, but DB diagnostics found exactly one readable
matching pending request. Temporary count-only probes then reproduced both
failures and observed the recreated gateway read one request and the screen
compose one pending item. The semantic lookup still reported the earlier empty
list. No user content or exception message was logged. These probes were removed;
a Docker check confirmed their tag is absent from every final application DEX.

The v2 focused pair passed. A subsequent legacy control paired the *same*
application APK with the original test APK and failed both cases again. This
isolates an observed test-environment difference without asserting the exact
upstream framework cause or changing application state to satisfy a test.

The already-pinned Compose dependency supplies v2. Its
[official migration guide](https://developer.android.com/develop/ui/compose/testing/migrate-v2)
documents the move from immediate unconfined dispatch to queued standard test
dispatch. This package adopts that setup only for the two affected tests and
retains all existing behavioral checks.

Every run uses fresh installs on the owned API37 AOSP ATD/x86_64/4KiB emulator,
with artifact/report identities recorded separately. Temporary packages and
settings/display changes are restored after each attempt. This is API/test
compatibility evidence and does not supply S21/OEM behavior, physical16KiB,
long-duration or F4 approval.

The final complete manifest passed **325/325, zero failures and zero skips** on
API37, using the same APK pair as the successful v2 focused run. Canonical Docker
check/build/lint/architecture/static/native checks passed again (4,111 actionable
tasks). Every temporary install and original setting/display was restored. See
the [exact source, artifact, report and retained-control identities](f4-ui-recreation-evidence.json).
