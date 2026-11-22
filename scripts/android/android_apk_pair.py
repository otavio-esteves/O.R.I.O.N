"""Verify the exact canonical debug APK pair before any emulator installation."""
import hashlib
import re


APK_NAMES = {'network.orion': 'app-debug.apk', 'network.orion.test': 'app-debug-androidTest.apk'}


class RunnerFailure(Exception):
    pass


def verify_artifacts(directory):
    manifest = directory / 'SHA256SUMS'
    if manifest.is_symlink() or not manifest.is_file() or manifest.stat().st_size > 512:
        raise RunnerFailure('ARTIFACT_MANIFEST_INVALID')
    entries = {}
    for line in manifest.read_text(encoding='ascii').splitlines():
        match = re.fullmatch(r'([a-f0-9]{64})  (app-debug(?:-androidTest)?\.apk)', line)
        if not match or match[2] in entries:
            raise RunnerFailure('ARTIFACT_MANIFEST_INVALID')
        entries[match[2]] = match[1]
    if set(entries) != set(APK_NAMES.values()):
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
