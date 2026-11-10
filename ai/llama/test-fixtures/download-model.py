#!/usr/bin/env python3
"""Explicit development-only fixture download. Never called by APK code or Gradle."""
import hashlib
import json
import pathlib
import urllib.request

root = pathlib.Path(__file__).resolve().parent
manifest = json.loads((root / "model-manifest.json").read_text())
output = root.parents[2] / "build" / "model-fixtures" / manifest["filename"]
output.parent.mkdir(parents=True, exist_ok=True)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


if not output.exists() or output.stat().st_size != manifest["sizeBytes"] or digest(output) != manifest["sha256"]:
    temporary = output.with_suffix(".gguf.part")
    try:
        with urllib.request.urlopen(manifest["url"], timeout=120) as source, temporary.open("wb") as target:
            size = 0
            while chunk := source.read(1024 * 1024):
                size += len(chunk)
                if size > manifest["sizeBytes"]:
                    raise ValueError("Fixture exceeds pinned size")
                target.write(chunk)
        if temporary.stat().st_size != manifest["sizeBytes"] or digest(temporary) != manifest["sha256"]:
            raise ValueError("Fixture hash/size mismatch")
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
print(f"Verified test-only fixture: {output}")
