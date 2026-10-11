#!/usr/bin/env python3
"""Package the Thor Forever Wine runtime as a Winlator content package (.wcp).

The .wcp can be installed in the app under Components -> Local package. It holds:
  profile.json     what the app reads to know this is a Wine build
  bin/ lib/ share/ the Wine runtime, unchanged
  prefixPack.txz   the starting Wine prefix for new containers

The prefix comes from the app's bundled Proton 9 arm64ec template, with the Windows
DLLs that our Wine ships removed, so new containers get our DLLs instead of Proton's.
Wine updates the rest of the prefix (registry) itself on first start.

Usage:
  make-thor-wine-wcp.py WINE_RUNTIME_TAR PREFIX_TEMPLATE_TZST OUT.wcp [--version 11.0] [--code 1]

Needs: pip install zstandard
"""
import argparse, io, json, lzma, sys, tarfile

import zstandard


def windows_dlls(runtime_tar):
    """Names of the files our Wine puts into system32 (aarch64) and syswow64 (i386)."""
    names = {"system32": set(), "syswow64": set()}
    with tarfile.open(runtime_tar, "r|") as t:
        for m in t:
            if not m.isfile():
                continue
            for src, dst in (("lib/wine/aarch64-windows/", "system32"), ("lib/wine/i386-windows/", "syswow64")):
                if m.name.startswith(src) and "/" not in m.name[len(src):]:
                    names[dst].add(m.name[len(src):].lower())
    return names


def build_prefix_pack(template_tzst, ours):
    """Copy the prefix template to an xz tar, leaving out DLLs our Wine provides."""
    out = io.BytesIO()
    removed = 0
    reader = zstandard.ZstdDecompressor().stream_reader(open(template_tzst, "rb"))
    # The xz stream gets its own "with": tarfile does not close a fileobj it was
    # handed, and an unclosed xz stream is missing its end marker.
    with tarfile.open(fileobj=reader, mode="r|") as src, \
         lzma.open(out, "wb", preset=6) as xz, \
         tarfile.open(fileobj=xz, mode="w") as dst:
        for m in src:
            parts = m.name.split("/")
            if len(parts) == 5 and parts[:3] == [".wine", "drive_c", "windows"] \
                    and parts[3] in ours and parts[4].lower() in ours[parts[3]] and not m.isdir():
                removed += 1
                continue
            dst.addfile(m, src.extractfile(m) if m.isfile() else None)
    return out.getvalue(), removed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runtime_tar")
    ap.add_argument("prefix_template")
    ap.add_argument("out")
    ap.add_argument("--version", default="11.0")
    ap.add_argument("--code", type=int, default=1)
    a = ap.parse_args()

    ours = windows_dlls(a.runtime_tar)
    if not ours["system32"]:
        sys.exit("runtime tar has no lib/wine/aarch64-windows files")
    prefix, removed = build_prefix_pack(a.prefix_template, ours)
    print(f"prefixPack.txz: {len(prefix)} bytes, {removed} template DLLs replaced by ours")

    # The app reads the version as "<number>-<arch>". Our Wine is plain ARM64; "arm64ec"
    # is the app's name for containers that run ARM64 Wine directly, without Box64.
    profile = {
        "type": "Wine",
        "versionName": f"{a.version}-arm64ec",
        "versionCode": a.code,
        "description": "Thor Forever Wine (ARM64, esync fixes)",
        "files": [],
        "wine": {"binPath": "bin", "libPath": "lib", "prefixPack": "prefixPack.txz"},
    }

    def add_bytes(tar, name, data):
        info = tarfile.TarInfo(name)
        info.size = len(data)
        info.mode = 0o644
        tar.addfile(info, io.BytesIO(data))

    cctx = zstandard.ZstdCompressor(level=19, threads=-1)
    with open(a.out, "wb") as f, cctx.stream_writer(f) as z, tarfile.open(fileobj=z, mode="w|") as out:
        add_bytes(out, "profile.json", json.dumps(profile, indent=2).encode())
        add_bytes(out, "prefixPack.txz", prefix)
        with tarfile.open(a.runtime_tar, "r|") as t:
            for m in t:
                if m.name.startswith("../") or m.name.startswith("/"):
                    continue
                out.addfile(m, t.extractfile(m) if m.isfile() else None)
    print(f"wrote {a.out}")


if __name__ == "__main__":
    main()
