# HOST recorder for the unloaded physical baseline

This procedure prepares the measurement scope of ORION-F5-PHYSICAL-BASELINE-001
under [#89](https://github.com/otavio-esteves/O.R.I.O.N/issues/89). The Galaxy S21
and physical API37 target are unavailable; no physical collection or result is
claimed here. #89, the #64 stability prerequisite and F5 remain open. Production
inference stays disabled, and no profile, native, soak or F6 gate is approved.

The recorder accepts only the opt-in baseline APK pair in the separate
`network.orion.qualification` identity. Build that scope canonically with
`-PorionQualificationRelease=true -PorionPhysicalBaseline=true`, as described in
[the checklist](F5-QualificationChecklist.md#prepared-unloaded-resource-baseline).
Copy/freeze both produced APKs and their identities before rebuilding: the ordinary
qualification probe uses the same output directory but a different runner.
The baseline does not accept a model file or execute load/inference.

## Freeze exactly the two baseline artifacts

The source manifest has exactly `schemaVersion`, `sourceCommit`, `files` and
`artifacts`. `files` covers every tracked file of the clean reviewed checkout;
`artifacts` contains exactly `app` and `test`, each a SHA256 of the selected baseline
APK. This is a separate manifest from the model-required physical preflight, whose
existing `app`/`test`/`model` contract remains unchanged. There is no CLI option to
relax either artifact set.

Set the following HOST environment variables to the reviewed source directory,
immutable baseline APK copies and a new manifest path outside the checkout:
`ORION_REVIEWED_SOURCE_ROOT`, `ORION_BASELINE_APP_APK`, `ORION_BASELINE_TEST_APK` and
`ORION_BASELINE_SOURCE_FREEZE`. After the reviewed build, generate the manifest:

```bash
python3 - <<'PY'
import hashlib, json, os, stat, subprocess
from pathlib import Path
root = Path(os.environ['ORION_REVIEWED_SOURCE_ROOT']).resolve()
output = Path(os.environ['ORION_BASELINE_SOURCE_FREEZE']).resolve()
assert not output.is_relative_to(root), 'Manifest must be outside the checkout'
def git(*args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True)
def sha(path):
    path = Path(path)
    assert stat.S_ISREG(path.lstat().st_mode), 'Only regular files are accepted'
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()
assert Path(git('rev-parse', '--show-toplevel').strip()).resolve() == root
assert not git('status', '--porcelain=v1', '-z', '--untracked-files=all')
paths = list(filter(None, git('ls-files', '-z').split('\0')))
value = {'schemaVersion': 1, 'sourceCommit': git('rev-parse', 'HEAD').strip(),
         'files': {name: sha(root / name) for name in paths},
         'artifacts': {'app': sha(os.environ['ORION_BASELINE_APP_APK']),
                       'test': sha(os.environ['ORION_BASELINE_TEST_APK'])}}
with output.open('x') as stream:
    stream.write(json.dumps(value, indent=2, sort_keys=True) + '\n')
PY
```

Hashes bind the selected files; they do not independently attest build provenance.
The recorder verifies the clean checkout and exact source manifest, local APKs and
already installed qualification APK pair before collection, then rechecks them
after collection. Installation/provisioning remains a separate reviewed procedure;
the recorder never installs, grants permissions, pushes files or changes settings.

The qualification APK declares MAIN, `:ai`, `:voice` and `:voice_session`. The
recorder accepts an unloaded sample only when the qualification UID's running
process inventory matches MAIN and the pinned `:ai` PID. A running voice
process or a missing process observation invalidates the sample. The Android
process-list API identifies running processes at each sampling boundary. PSS
comes from each of the two sampled processes.

## Collect only after a physical target is available

Do not run now without an allocated physical target and the exact baseline pair
already installed. The serial is explicit; emulators and synthetic device labels
are rejected. The S21 role requires the observed Samsung SM-G991 identity; API37
uses a separately allocated compatible physical device with `--target API37`.

```bash
python3 scripts/android/record-f5-physical-baseline.py \
  --adb "$ORION_ADB" --serial "$ORION_S21_SERIAL" --target S21 \
  --source-root "$ORION_REVIEWED_SOURCE_ROOT" \
  --source-manifest "$ORION_BASELINE_SOURCE_FREEZE" \
  --app-apk "$ORION_BASELINE_APP_APK" --test-apk "$ORION_BASELINE_TEST_APK" \
  --samples 3 --interval-ms 250 \
  --evidence-dir "$ORION_NEW_BASELINE_EVIDENCE"
```

The evidence directory must be new. Accepted bounds are 1–10 samples and
100–1000 milliseconds between samples. The only instrumentation endpoint is
`network.orion.qualification.test/network.orion.app.compatibility.OrionPhysicalBaselineInstrumentation`,
with the exact environment/count/interval arguments. There is no selector for
arbitrary ADB commands, a model, resource budgets or a profile.

`am instrument` starts the separate qualification APK and its lightweight AI
binding. Ordinary bootstrap can create that identity's own Room, Keystore or
technical WorkManager state. The Core can still be initializing; this collection
proves neither a quiescent Core nor an unchanged device. The recorder requests no
production-data access or heavy work and records those intervention limits.

## Interpret the retained result

`f5-physical-baseline.json` records source/artifact hashes, hashed device/boot
identities, bounded observation windows and validated technical samples. It does
not retain raw instrumentation output, device serials or raw errors. Same-boot,
stable-PID, freshness, cadence, complete inventory and plausible numeric checks
must all agree; missing or invalid metrics never become zero use.

A complete collection reports `MEASUREMENT_ONLY` with `NOT_QUALIFIED` and exit 0.
That exit means the bounded collection completed for review. It grants no model,
resource-profile, performance, native, F5 or soak approval. Every approval flag
stays false. A valid partial runner failure stays `INCOMPLETE` or `REJECTED` with
exit 2. Invalid input, framing, identity or metrics are `REJECTED`, also exit 2.
A runner summary labelled `UNVERIFIED` is deliberately rejected as
`SUMMARY_IDENTITY_INVALID`; it is never relabelled `PHYSICAL_DEVICE`.
`CLEANUP_NOT_REGISTERED` is a typed non-successful cleanup outcome.

Interrupted/unverifiable attempts keep their available report and never overwrite
a prior directory. Missing output after abrupt HOST termination proves no success.
Review failures before another explicitly selected attempt; no automatic rebind,
replay, model import or qualification promotion is requested by the recorder.

Device-independent regression commands use synthetic HOST fixtures only:

```bash
python3 scripts/android/test_f5_physical_baseline_harness.py
python3 scripts/android/test_f5_physical_preflight_harness.py
```

Those contracts do not supply physical samples. Model workload instrumentation,
actual API37 MemoryLimiter stimulus and post-Cortex heavy-work/memory-stress soak
remain separate preparation/execution work under #89; this baseline replaces none
of them or the complete physical #64 stability profile.
