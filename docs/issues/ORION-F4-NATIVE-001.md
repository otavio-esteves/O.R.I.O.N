# ORION-F4-NATIVE-001 — Scoped minified release/native qualification

OBJECTIVE: Prepare and execute a non-debuggable R8 qualification build from the
current app sources under a separate app identity. Verify real startup and JNI
loading plus static ELF/ABI/APK alignment without replacing the user's app or
promoting emulator/4KiB evidence to physical arm64/16KiB qualification.

FILES ALLOWED: conditional app qualification build/source sets and focused release
instrumentation; native package checker/profile tests; canonical qualification CI
command; focused host runtime recorder/contracts and qualification documentation.

CONTRACTS INVOLVED: Architecture native/16KiB/API compatibility separation;
Master Plan F4 diagnostics/qualification; ADR-0001; unchanged MAIN ownership and
closed production runtime authority.

INVARIANTS INVOLVED: INV-001/011/012/014/023/025/027/029/030/032/033/035.

TESTS REQUIRED: Disabled-by-default qualification variant, actual R8 mapping,
non-debuggable and separate package identity, actual native loads on available
ABI/page-size targets, startup with healthy local storage, and static ELF/APK
alignment. Default release remains arm64-only; qualification x86 is accepted only
for a validated non-debuggable qualification package. Test refusal of arbitrary
package/profile, pre-existing installs and unsupported environments. CI builds the
test artifacts but never substitutes packaging for Android runtime evidence.

ACCEPTANCE CRITERIA: Product debug/release identities and minification policy are
unchanged. Qualification uses explicit opt-in, test signing and its own private
data/UID. No public component or new production process is added. Runtime reports
bind source/APKs, environment, process ABI, page size and actual assertions. Cleanup
removes only packages created by that run. Native gate stays pending until its
complete required physical matrix is approved.

OUT OF SCOPE: Production signing/publication, blanket keep rules, changing product
ABI support, enabling AI/voice, bypassing Android restrictions, replacing/clearing
network.orion, full physical16KiB or 24-hour claims and F4 approval.
