#!/usr/bin/env python3
"""Compare two APKs entry by entry, ignoring the signature files.

Usage: scripts/compare_apks.py [--layout] a.apk b.apk

--layout also checks that every entry sits at the same offset in both files.
F-Droid's check (apksigcopier) copies our signature onto its own unsigned
build; that only verifies if signing moved nothing. apksigner re-aligns
entries unless told `--alignment-preserved true` (0.1.0 was signed without
it: same contents, 83 entries moved — F-Droid could not have used it).

Reproducible builds (ADR 0011, 0017): F-Droid rebuilds Hopp from source and
only publishes our signed APK if its own unsigned build matches it byte for
byte outside META-INF signature files. This script shows where two builds
differ before F-Droid does.
"""
import hashlib
import sys
import zipfile

SIGNATURE = ("META-INF/MANIFEST.MF",)
SIG_SUFFIXES = (".SF", ".RSA", ".EC", ".DSA")


def entries(path):
    with zipfile.ZipFile(path) as z:
        out = {}
        for info in z.infolist():
            name = info.filename
            if name in SIGNATURE or (name.startswith("META-INF/") and name.endswith(SIG_SUFFIXES)):
                continue
            out[name] = (hashlib.sha256(z.read(name)).hexdigest(), info.date_time, info.compress_type, info.external_attr)
        return out


def moved(a, b):
    with zipfile.ZipFile(a) as za, zipfile.ZipFile(b) as zb:
        oa = {i.filename: i.header_offset for i in za.infolist()}
        ob = {i.filename: i.header_offset for i in zb.infolist()}
    return sorted(n for n in oa if n in ob and oa[n] != ob[n])


def main(a, b, layout=False):
    if layout:
        shifted = moved(a, b)
        for n in shifted[:20]:
            print(f"moved: {n}")
        if shifted:
            print(f"{len(shifted)} entries at other offsets — DIFFERENT layout")
            return 1
    ea, eb = entries(a), entries(b)
    only_a, only_b = sorted(set(ea) - set(eb)), sorted(set(eb) - set(ea))
    differ = sorted(n for n in set(ea) & set(eb) if ea[n] != eb[n])
    for n in only_a:
        print(f"only in A: {n}")
    for n in only_b:
        print(f"only in B: {n}")
    for n in differ:
        what = [k for k, i in (("content", 0), ("time", 1), ("compression", 2), ("attrs", 3)) if ea[n][i] != eb[n][i]]
        print(f"differs ({', '.join(what)}): {n}")
    same = not (only_a or only_b or differ)
    print(f"{len(ea)} entries compared — {'IDENTICAL' if same else 'DIFFERENT'}")
    return 0 if same else 1


if __name__ == "__main__":
    args = sys.argv[1:]
    layout = "--layout" in args
    files = [x for x in args if x != "--layout"]
    sys.exit(main(files[0], files[1], layout))
