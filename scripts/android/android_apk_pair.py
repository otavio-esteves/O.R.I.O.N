"""Verify the exact canonical APK pair before any emulator installation."""
import hashlib
import re


APK_NAMES = {'network.orion': 'app-debug.apk', 'network.orion.test': 'app-debug-androidTest.apk'}
QUALIFICATION_APK_NAMES = {
    'network.orion.qualification': 'app-qualificationRelease.apk',
    'network.orion.qualification.test': 'app-qualificationRelease-androidTest.apk',
}
SECURITY_APK_NAMES = {'network.orion.core.security.test': 'security-debug-androidTest.apk'}


class RunnerFailure(Exception):
    pass


def verify_artifacts(directory, apk_names=APK_NAMES):
    manifest = directory / 'SHA256SUMS'
    if manifest.is_symlink() or not manifest.is_file() or manifest.stat().st_size > 512:
        raise RunnerFailure('ARTIFACT_MANIFEST_INVALID')
    entries = {}
    for line in manifest.read_text(encoding='ascii').splitlines():
        digest, _, name = line.partition('  ')
        if not re.fullmatch(r'[a-f0-9]{64}', digest) or name not in apk_names.values() or name in entries:
            raise RunnerFailure('ARTIFACT_MANIFEST_INVALID')
        entries[name] = digest
    if set(entries) != set(apk_names.values()):
        raise RunnerFailure('ARTIFACT_MANIFEST_INVALID')
    for name, expected in entries.items():
        artifact = directory / name
        if artifact.is_symlink() or not artifact.is_file():
            raise RunnerFailure('ARTIFACT_MISSING')
        with artifact.open('rb') as stream:
            actual = hashlib.file_digest(stream, 'sha256').hexdigest()
        if actual != expected:
            raise RunnerFailure('ARTIFACT_HASH_MISMATCH')
    return entries
