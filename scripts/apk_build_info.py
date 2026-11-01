#!/usr/bin/env python3
"""Collect build-info / hash / acceptance diagnostics for signed APK files.

Standalone, stdlib-only, fully offline. Never touches the network, never
modifies or moves the input APKs, and never extracts zip entries to disk
(zip-slip safe by construction: entries are only listed, and the single
bounded in-memory read is for META-INF/version-control-info.textproto).

What it does
------------
* For every given APK: sha256, size, mtime, zip entry count, packaged ABIs,
  embedded VCS revision (META-INF/version-control-info.textproto, if present),
  and signature presence (v1 jarsigner files / v2+ "APK Sig Block 42").
* Acceptance checks (AGENTS.md "验收必查"):
    - the APK is a readable zip;
    - lib/<abi>/ entries exist; every ABI actually packaged in the APK must
      contain the workspace runtime (libworkspace_pty.so, PRoot libproot_exec.so /
      libproot_loader.so and the terminal bridge libtermux.so). An APK with no lib/ entries at
      all is reported as INCOMPLETE. ABIs not packaged are not required;
    - static/assets/ contains at least one workspaces*.js, storage*.js and
      translator*.js;
    - a signature is present (v1 *.SF entry or v2+ signing block);
    - the web engine's reflective entry points survived R8 (classes*.dex, see
      WEB_ENGINE_CHECKS): NioServerSocketChannel keeps its public no-arg
      constructor, NioSocketChannel is a real class, ChannelInitializer keeps
      @ChannelHandler.Sharable, Ktor's NettyDirectEncoder keeps its generic
      signature with MessageToByteEncoder (also with its signature) as direct
      superclass, and ConcurrentSkipListIntObjMultimap (when shipped) keeps the
      static methods its <clinit> finds by name. 2.6.0-2.8.1 release APKs failed
      these, so the web server could not start.
* Writes one JSON document either to --output, to stdout (--stdout), or as a
  sidecar ``<apk>.build-info.json`` next to each input APK. It refuses to
  overwrite an .apk file and refuses an --output path that equals an input.

Metadata policy: only zip metadata, the VCS textproto and the dex class
tables (read in memory, never written to disk) are inspected. The
tool never opens config.yaml, keystores, or any credential material.

Usage
-----
    python3 scripts/apk_build_info.py app/build/outputs/apk/release/*.apk
    python3 scripts/apk_build_info.py --stdout apk/debug/app-debug.apk
    python3 scripts/apk_build_info.py -o dist/build-info.json apk/release/*.apk
    python3 scripts/apk_build_info.py --check-only apk/release/*.apk   # no files written

Exit code is 0 when every APK passes all checks, 1 otherwise (or 2 on usage
errors such as missing input files).
"""

from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import zipfile
from datetime import datetime, timezone

SCHEMA = "rikkihub.build-info/1"
WORKSPACE_PTY = "libworkspace_pty.so"
RUNTIME_LIBS = (WORKSPACE_PTY, "libproot_exec.so", "libproot_loader.so", "libtermux.so")
ASSET_PATTERNS = ("workspaces*.js", "storage*.js", "translator*.js")
VCS_INFO_ENTRY = "META-INF/version-control-info.textproto"
APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"
# Guard rails for the single in-memory entry read (defence in depth; the entry
# is ~100 bytes in practice).
VCS_INFO_MAX_BYTES = 1 << 20
SHA256_CHUNK = 1 << 20
DEX_ENTRY_RE = re.compile(r"^classes\d*\.dex$")
DEX_MAX_BYTES = 256 << 20

NIO_SERVER_CHANNEL = "Lio/netty/channel/socket/nio/NioServerSocketChannel;"
NIO_CHILD_CHANNEL = "Lio/netty/channel/socket/nio/NioSocketChannel;"
CHANNEL_INITIALIZER = "Lio/netty/channel/ChannelInitializer;"
SHARABLE = "Lio/netty/channel/ChannelHandler$Sharable;"
DIRECT_ENCODER = "Lio/ktor/server/netty/NettyDirectEncoder;"
MESSAGE_TO_BYTE_ENCODER = "Lio/netty/handler/codec/MessageToByteEncoder;"
DEX_SIGNATURE = "Ldalvik/annotation/Signature;"
SKIP_LIST_MULTIMAP = "Lio/netty/util/concurrent/ConcurrentSkipListIntObjMultimap;"
SKIP_LIST_FENCES = ("acquireFence", "acquireFenceFallback")  # MethodHandles.findStatic
# Netty instantiates/inspects these reflectively at runtime; R8 full mode strips
# them unless app/proguard-rules.pro keeps them.
WEB_ENGINE_CHECKS = (
    "NioServerSocketChannel public <init>()",
    "NioSocketChannel concrete",
    "ChannelInitializer @Sharable",
    "NettyDirectEncoder generic signature",
    "ConcurrentSkipListIntObjMultimap fence methods",
)
ACC_PUBLIC = 0x1
ACC_INTERFACE = 0x200
ACC_ABSTRACT = 0x400
NO_INDEX = 0xFFFFFFFF


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(SHA256_CHUNK), b""):
            digest.update(chunk)
    return digest.hexdigest()


def file_mtime_iso(path):
    ts = datetime.fromtimestamp(os.path.getmtime(path), tz=timezone.utc)
    return ts.isoformat().replace("+00:00", "Z")


def collect_git(repo_dir, enabled=True):
    """Return {commit, dirty}; 'unavailable' when git/repo is missing."""
    info = {"repo": repo_dir, "commit": "unavailable", "dirty": "unavailable"}
    if not enabled:
        return info
    try:
        commit = subprocess.run(
            ["git", "-C", repo_dir, "rev-parse", "HEAD"],
            capture_output=True, text=True, timeout=15,
        )
        if commit.returncode != 0:
            return info
        info["commit"] = commit.stdout.strip() or "unavailable"
        status = subprocess.run(
            ["git", "-C", repo_dir, "status", "--porcelain"],
            capture_output=True, text=True, timeout=30,
        )
        if status.returncode == 0:
            info["dirty"] = bool(status.stdout.strip())
    except (OSError, subprocess.TimeoutExpired):
        pass
    return info


def has_v2_signature_block(path):
    """Cheap scan for the APK Signing Block magic (v2/v3/v3.1/v4)."""
    needle = APK_SIG_BLOCK_MAGIC
    with open(path, "rb") as handle:
        prev_tail = b""
        while True:
            chunk = handle.read(SHA256_CHUNK)
            if not chunk:
                return False
            if needle in prev_tail + chunk:
                return True
            prev_tail = chunk[-len(needle):]


def extract_embedded_vcs_revision(zf, names):
    """Best-effort read of META-INF/version-control-info.textproto."""
    if VCS_INFO_ENTRY not in names:
        return None
    entry = zf.getinfo(VCS_INFO_ENTRY)
    if entry.file_size > VCS_INFO_MAX_BYTES:
        return None
    try:
        text = zf.read(entry).decode("utf-8", "replace")
    except (OSError, zipfile.BadZipFile):
        return None
    # textproto like: repositories { root_path: "..." revision: "abc..." }
    revision = re.search(r'revision:\s*"([0-9a-fA-F]{7,64})"', text)
    return revision.group(1) if revision else None


class DexFormatError(ValueError):
    pass


def _uleb128(data, pos):
    result = shift = 0
    while True:
        if pos >= len(data) or shift > 28:
            raise DexFormatError("bad uleb128 at %d" % pos)
        byte = data[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if byte < 0x80:
            return result, pos
        shift += 7


def _u32(data, off):
    if off < 0 or off + 4 > len(data):
        raise DexFormatError("offset out of range: %d" % off)
    return struct.unpack_from("<I", data, off)[0]


def _u16(data, off):
    if off < 0 or off + 2 > len(data):
        raise DexFormatError("offset out of range: %d" % off)
    return struct.unpack_from("<H", data, off)[0]


class DexFile:
    """Just enough of the dex format to answer the web engine checks."""

    def __init__(self, data):
        if len(data) < 0x70 or not data.startswith(b"dex\n"):
            raise DexFormatError("not a dex file")
        self.data = data
        self._strings = {}
        self.string_ids_off = _u32(data, 0x3C)
        self.type_ids_off = _u32(data, 0x44)
        self.proto_ids_off = _u32(data, 0x4C)
        self.method_ids_off = _u32(data, 0x5C)
        class_defs_size, class_defs_off = _u32(data, 0x60), _u32(data, 0x64)
        self.classes = {}
        for i in range(class_defs_size):
            off = class_defs_off + 32 * i
            name = self.type_name(_u32(data, off))
            self.classes[name] = off

    def string(self, idx):
        cached = self._strings.get(idx)
        if cached is None:
            off = _u32(self.data, self.string_ids_off + 4 * idx)
            _, start = _uleb128(self.data, off)
            end = self.data.find(b"\0", start)
            if end < 0:
                raise DexFormatError("unterminated string %d" % idx)
            cached = self.data[start:end].decode("utf-8", "replace")
            self._strings[idx] = cached
        return cached

    def type_name(self, type_idx):
        return self.string(_u32(self.data, self.type_ids_off + 4 * type_idx))

    def class_info(self, name):
        """Return {access, superclass, annotations, constructors, direct_methods} or None."""
        off = self.classes.get(name)
        if off is None:
            return None
        data = self.data
        superclass_idx = _u32(data, off + 8)
        info = {
            "access": _u32(data, off + 4),
            "superclass": None if superclass_idx == NO_INDEX else self.type_name(superclass_idx),
            "annotations": self._class_annotations(_u32(data, off + 20)),
        }
        direct = self._direct_methods(_u32(data, off + 24))
        info["direct_methods"] = sorted({name for name, _, _ in direct})
        info["constructors"] = [(acc, params) for name, acc, params in direct if name == "<init>"]
        return info

    def _class_annotations(self, directory_off):
        if not directory_off:
            return []
        set_off = _u32(self.data, directory_off)
        if not set_off:
            return []
        names = []
        for i in range(_u32(self.data, set_off)):
            item_off = _u32(self.data, set_off + 4 + 4 * i)
            type_idx, _ = _uleb128(self.data, item_off + 1)  # skip visibility byte
            names.append(self.type_name(type_idx))
        return names

    def _direct_methods(self, class_data_off):
        """[(name, access_flags, parameter_count)] of the direct methods."""
        if not class_data_off:
            return []
        data = self.data
        pos = class_data_off
        sizes = []
        for _ in range(4):
            value, pos = _uleb128(data, pos)
            sizes.append(value)
        static_fields, instance_fields, direct_methods, _ = sizes
        for _ in range(2 * (static_fields + instance_fields)):
            _, pos = _uleb128(data, pos)
        found = []
        method_idx = 0
        for _ in range(direct_methods):
            diff, pos = _uleb128(data, pos)
            access, pos = _uleb128(data, pos)
            _, pos = _uleb128(data, pos)  # code_off
            method_idx += diff
            method_off = self.method_ids_off + 8 * method_idx
            proto_off = self.proto_ids_off + 12 * _u16(data, method_off + 2)
            params_off = _u32(data, proto_off + 8)
            found.append((self.string(_u32(data, method_off + 4)), access,
                          _u32(data, params_off) if params_off else 0))
        return found


def check_web_engine(dex_files):
    """Run WEB_ENGINE_CHECKS over the APK's dex files; return (results, problems)."""
    def lookup(name):
        for dex in dex_files:
            info = dex.class_info(name)
            if info is not None:
                return info
        return None

    results = {}
    problems = []

    server = lookup(NIO_SERVER_CHANNEL)
    results[WEB_ENGINE_CHECKS[0]] = bool(
        server and not server["access"] & ACC_ABSTRACT
        and any(acc & ACC_PUBLIC and params == 0 for acc, params in server["constructors"]))

    child = lookup(NIO_CHILD_CHANNEL)
    results[WEB_ENGINE_CHECKS[1]] = bool(
        child and not child["access"] & (ACC_ABSTRACT | ACC_INTERFACE) and child["constructors"])

    initializer = lookup(CHANNEL_INITIALIZER)
    results[WEB_ENGINE_CHECKS[2]] = bool(initializer and SHARABLE in initializer["annotations"])

    encoder = lookup(DIRECT_ENCODER)
    base = lookup(MESSAGE_TO_BYTE_ENCODER)
    results[WEB_ENGINE_CHECKS[3]] = bool(
        encoder and base
        and encoder["superclass"] == MESSAGE_TO_BYTE_ENCODER
        and DEX_SIGNATURE in encoder["annotations"]
        and DEX_SIGNATURE in base["annotations"])

    multimap = lookup(SKIP_LIST_MULTIMAP)  # not shipped → nothing looks it up
    results[WEB_ENGINE_CHECKS[4]] = multimap is None or all(
        name in multimap["direct_methods"] for name in SKIP_LIST_FENCES)

    for check, passed in results.items():
        if not passed:
            problems.append("web engine: %s missing in dex (R8 keep rules)" % check)
    return results, problems


def inspect_dex(zf, names):
    """Load classes*.dex (in memory, bounded) and run the web engine checks."""
    dex_names = sorted(n for n in names if DEX_ENTRY_RE.match(n))
    if not dex_names:
        return None, ["no classes*.dex entries"]
    dex_files = []
    try:
        for name in dex_names:
            entry = zf.getinfo(name)
            if entry.file_size > DEX_MAX_BYTES:
                return None, ["%s larger than %d bytes" % (name, DEX_MAX_BYTES)]
            dex_files.append(DexFile(zf.read(entry)))
        return check_web_engine(dex_files)
    except (DexFormatError, struct.error, OSError, zipfile.BadZipFile) as exc:
        return None, ["cannot parse dex: %s" % exc]


def basename_assets(names):
    assets = {}
    for name in names:
        if not name.startswith("static/assets/"):
            continue
        base = name.rsplit("/", 1)[-1]
        assets.setdefault(name, base)
    return assets


def inspect_apk(path):
    problems = []
    record = {
        "path": os.path.abspath(path),
        "file_name": os.path.basename(path),
    }
    try:
        record["size_bytes"] = os.path.getsize(path)
        record["sha256"] = sha256_file(path)
        record["mtime"] = file_mtime_iso(path)
    except OSError as exc:
        record["problems"] = ["cannot read file: %s" % exc]
        record["ok"] = False
        return record

    try:
        zf = zipfile.ZipFile(path)
    except zipfile.BadZipFile:
        record["problems"] = ["not a zip/apk file"]
        record["ok"] = False
        return record

    with zf:
        names = zf.namelist()
        record["entry_count"] = len(names)

        abi_libs = {}
        for name in names:
            parts = name.split("/")
            if len(parts) == 3 and parts[0] == "lib" and parts[2]:
                abi_libs.setdefault(parts[1], []).append(parts[2])
        record["abis"] = sorted(abi_libs)
        if not abi_libs:
            problems.append("no lib/<abi>/ entries: APK is incomplete (native libs missing)")
        pty = {}
        for abi in sorted(abi_libs):
            pty[abi] = WORKSPACE_PTY in abi_libs[abi]
            for lib in RUNTIME_LIBS:
                if lib not in abi_libs[abi]:
                    problems.append("missing lib/%s/%s" % (abi, lib))
        record["workspace_pty"] = pty

        assets = basename_assets(names)
        found = {}
        for pattern in ASSET_PATTERNS:
            hits = sorted(n for n, b in assets.items() if fnmatch.fnmatchcase(b, pattern))
            found[pattern] = hits
            if not hits:
                problems.append("missing static/assets/%s" % pattern)
        record["static_assets"] = found

        record["embedded_vcs_revision"] = extract_embedded_vcs_revision(zf, set(names))

        web_engine, dex_problems = inspect_dex(zf, names)
        record["web_engine"] = web_engine
        problems.extend(dex_problems)

        v1_files = [n for n in names
                    if n.startswith("META-INF/")
                    and n.upper().endswith((".SF", ".RSA", ".DSA", ".EC"))]
        record["v1_signature_files"] = len(v1_files)

    record["v2_signature_block"] = has_v2_signature_block(path)
    record["signed"] = bool(record["v2_signature_block"] or record["v1_signature_files"])
    if not record["signed"]:
        problems.append("no signature found (v1 META-INF/*.SF or v2+ signing block)")

    record["problems"] = problems
    record["ok"] = not problems
    return record


def default_repo_dir():
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def build_report(apk_paths, repo_dir=None, use_git=True):
    report = {
        "schema": SCHEMA,
        "collected_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "collector": os.path.basename(__file__),
        "source": collect_git(repo_dir or default_repo_dir(), enabled=use_git),
        "files": [inspect_apk(p) for p in apk_paths],
    }
    report["ok"] = all(f["ok"] for f in report["files"])
    return report


def write_report(report, apk_paths, output, to_stdout):
    payload = json.dumps(report, indent=2, ensure_ascii=False, sort_keys=True) + "\n"
    if to_stdout:
        sys.stdout.write(payload)
        return []
    written = []
    if output:
        out_abs = os.path.abspath(output)
        if out_abs.lower().endswith(".apk"):
            raise SystemExit("refusing to write report over an .apk file: %s" % output)
        for p in apk_paths:
            if os.path.abspath(p) == out_abs:
                raise SystemExit("output path must not be an input file: %s" % output)
        os.makedirs(os.path.dirname(out_abs) or ".", exist_ok=True)
        with open(out_abs, "w", encoding="utf-8") as handle:
            handle.write(payload)
        written.append(out_abs)
    else:
        for p in apk_paths:
            sidecar = os.path.abspath(p) + ".build-info.json"
            single = dict(report)
            single["files"] = [f for f in report["files"]
                               if f["path"] == os.path.abspath(p)]
            single["ok"] = single["files"][0]["ok"] if single["files"] else report["ok"]
            with open(sidecar, "w", encoding="utf-8") as handle:
                handle.write(json.dumps(single, indent=2, ensure_ascii=False,
                                        sort_keys=True) + "\n")
            written.append(sidecar)
    return written


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Collect build-info/hash/acceptance diagnostics for signed APKs "
                    "(offline, stdlib only, never modifies inputs).",
    )
    parser.add_argument("apks", nargs="+", metavar="APK",
                        help="APK file(s) to inspect (must already be built+signed)")
    parser.add_argument("-o", "--output", metavar="FILE",
                        help="write a single combined JSON report to FILE "
                             "(default: one <apk>.build-info.json sidecar per input)")
    parser.add_argument("--stdout", action="store_true",
                        help="print the combined report to stdout instead of writing files")
    parser.add_argument("--check-only", action="store_true",
                        help="run checks, print a summary, write nothing "
                             "(exit code still reflects check results)")
    parser.add_argument("--repo", metavar="DIR", default=None,
                        help="git repo used for source commit/dirty metadata "
                             "(default: the repo containing this script)")
    parser.add_argument("--no-git", action="store_true",
                        help="skip git metadata collection entirely")
    args = parser.parse_args(argv)

    missing = [p for p in args.apks if not os.path.isfile(p)]
    if missing:
        for p in missing:
            print("error: input not found: %s" % p, file=sys.stderr)
        return 2

    report = build_report(args.apks, repo_dir=args.repo, use_git=not args.no_git)

    # Keep stdout clean for --stdout JSON output: summary goes to stderr then.
    summary_stream = sys.stderr if args.stdout else sys.stdout
    for item in report["files"]:
        state = "OK  " if item["ok"] else "FAIL"
        print("%s %s  sha256=%s" % (state, item["file_name"],
                                    item.get("sha256", "unavailable")),
              file=summary_stream)
        for problem in item.get("problems", []):
            print("      - %s" % problem, file=summary_stream)

    written = []
    if not args.check_only:
        written = write_report(report, args.apks, args.output, args.stdout)
        for path in written:
            print("wrote %s" % path, file=sys.stderr)

    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
