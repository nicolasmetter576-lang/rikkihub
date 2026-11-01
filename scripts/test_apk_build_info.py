#!/usr/bin/env python3
"""Offline unit tests for apk_build_info.py.

Builds throwaway zip files in a temp dir that mimic APK structure; no real
APK, no Gradle, no network. Run:

    python3 scripts/test_apk_build_info.py        # or: python3 -m unittest ...
"""

import hashlib
import io
import json
import os
import struct
import sys
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr, redirect_stdout

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import apk_build_info as abi  # noqa: E402


RUNTIME = ("libworkspace_pty.so", "libproot_exec.so", "libproot_loader.so", "libtermux.so")

FULL_ENTRIES = (
    [f"lib/{a}/{lib}" for a in ("arm64-v8a", "x86_64") for lib in RUNTIME]
    + ["static/assets/workspaces-AAA.js", "static/assets/workspaces._id-BBB.js",
     "static/assets/storage-CCC.js", "static/assets/translator-DDD.js",
     "classes.dex", "META-INF/CERT.SF", "META-INF/CERT.RSA"]
)


def _uleb(value):
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def build_dex(classes):
    """Minimal dex with the tables apk_build_info reads.

    classes: [{"name", "access", "superclass", "annotations", "ctors"}], ctors as
    [(access_flags, parameter_count)].
    """
    strings = {"<init>", "V", "VL", "Ljava/lang/Object;"}
    for c in classes:
        strings.update(name for _, name in c.get("methods", ()))
        strings.update([c["name"], c.get("superclass") or "Ljava/lang/Object;"])
        strings.update(c.get("annotations", ()))
    strings = sorted(strings)
    sidx = {v: i for i, v in enumerate(strings)}
    types = sorted(v for v in strings if v.startswith("L"))
    tidx = {v: i for i, v in enumerate(types)}
    protos = [("V", 0), ("VL", 1)]  # ()V, (Object)V
    methods = []  # (class_idx, name_idx, proto_idx, access)
    for c in classes:
        for access, nparams in c.get("ctors", ()):
            methods.append((tidx[c["name"]], sidx["<init>"], nparams, access | 0x10000))
        for access, name in c.get("methods", ()):
            methods.append((tidx[c["name"]], sidx[name], 0, access))
    methods.sort()

    header_size = 0x70
    string_ids_off = header_size
    type_ids_off = string_ids_off + 4 * len(strings)
    proto_ids_off = type_ids_off + 4 * len(types)
    method_ids_off = proto_ids_off + 12 * len(protos)
    class_defs_off = method_ids_off + 8 * len(methods)
    data_off = class_defs_off + 32 * len(classes)
    data = bytearray()

    def put(blob, align=4):
        while (data_off + len(data)) % align:
            data.append(0)
        off = data_off + len(data)
        data.extend(blob)
        return off

    string_offs = [put(_uleb(len(v)) + v.encode() + b"\0", 1) for v in strings]
    param_list_off = put(struct.pack("<IH", 1, tidx["Ljava/lang/Object;"]))
    class_defs = bytearray()
    for c in classes:
        ann_dir = 0
        if c.get("annotations"):
            items = [put(b"\x01" + _uleb(tidx[a]) + _uleb(0), 1) for a in c["annotations"]]
            set_off = put(struct.pack("<I", len(items)) + b"".join(struct.pack("<I", i) for i in items))
            ann_dir = put(struct.pack("<IIII", set_off, 0, 0, 0))
        mine = [i for i, m in enumerate(methods) if m[0] == tidx[c["name"]]]
        class_data = 0
        if mine:
            blob = _uleb(0) + _uleb(0) + _uleb(len(mine)) + _uleb(0)
            prev = 0
            for i in mine:
                blob += _uleb(i - prev) + _uleb(methods[i][3]) + _uleb(0)
                prev = i
            class_data = put(blob, 1)
        superclass = c.get("superclass", "Ljava/lang/Object;")
        class_defs += struct.pack(
            "<8I", tidx[c["name"]], c.get("access", 0x1),
            0xFFFFFFFF if superclass is None else tidx[superclass],
            0, 0xFFFFFFFF, ann_dir, class_data, 0)

    header = bytearray(header_size)
    header[0:8] = b"dex\n038\0"
    struct.pack_into("<II", header, 0x38, len(strings), string_ids_off)
    struct.pack_into("<II", header, 0x40, len(types), type_ids_off)
    struct.pack_into("<II", header, 0x48, len(protos), proto_ids_off)
    struct.pack_into("<II", header, 0x58, len(methods), method_ids_off)
    struct.pack_into("<II", header, 0x60, len(classes), class_defs_off)
    body = b"".join(struct.pack("<I", o) for o in string_offs)
    body += b"".join(struct.pack("<I", sidx[t]) for t in types)
    for shorty, nparams in protos:
        body += struct.pack("<III", sidx[shorty], tidx.get("V", 0), param_list_off if nparams else 0)
    for class_idx, name_idx, proto_idx, _ in methods:
        body += struct.pack("<HHI", class_idx, proto_idx, name_idx)
    return bytes(header + body + class_defs + data)


SIG = abi.DEX_SIGNATURE


def web_engine_classes(**overrides):
    """Classes as a correctly kept release (or any debug) build has them."""
    classes = {
        "server": {"name": abi.NIO_SERVER_CHANNEL, "access": 0x1, "ctors": [(0x1, 0), (0x1, 1)]},
        "child": {"name": abi.NIO_CHILD_CHANNEL, "access": 0x1, "ctors": [(0x1, 1)]},
        "initializer": {"name": abi.CHANNEL_INITIALIZER, "access": 0x401, "annotations": [abi.SHARABLE, SIG]},
        "base": {"name": abi.MESSAGE_TO_BYTE_ENCODER, "access": 0x401, "annotations": [SIG]},
        "encoder": {"name": abi.DIRECT_ENCODER, "access": 0x11,
                    "superclass": abi.MESSAGE_TO_BYTE_ENCODER, "annotations": [SIG], "ctors": [(0x1, 0)]},
        "sharable": {"name": abi.SHARABLE, "access": 0x2601},
        "multimap": {"name": abi.SKIP_LIST_MULTIMAP, "access": 0x1,
                     "methods": [(0x8, "acquireFence"), (0x8, "acquireFenceFallback")]},
    }
    for key, value in overrides.items():
        if value is None:
            classes.pop(key)
        else:
            classes[key] = dict(classes[key], **value)
    return list(classes.values())


GOOD_DEX = build_dex(web_engine_classes())


def make_apk(path, entries, v2_block=False, dex=None):
    """dex: {entry name: bytes}; classes.dex defaults to GOOD_DEX."""
    dex = dict({"classes.dex": GOOD_DEX}, **(dex or {}))
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as zf:
        for name in entries:
            zf.writestr(name, dex.get(name, "payload:" + name))
    if v2_block:
        with open(path, "ab") as handle:
            handle.write(b"\x00" * 8 + abi.APK_SIG_BLOCK_MAGIC + b"\x00" * 8)
    return path


class InspectTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = self.tmp.name

    def tearDown(self):
        self.tmp.cleanup()

    def apk(self, name, entries, **kw):
        return make_apk(os.path.join(self.dir, name), entries, **kw)

    def test_complete_apk_passes(self):
        rec = abi.inspect_apk(self.apk("good.apk", FULL_ENTRIES))
        self.assertTrue(rec["ok"], rec["problems"])
        self.assertEqual(rec["abis"], ["arm64-v8a", "x86_64"])
        self.assertEqual(rec["workspace_pty"],
                         {"arm64-v8a": True, "x86_64": True})
        self.assertTrue(rec["signed"])
        self.assertGreaterEqual(len(rec["static_assets"]["workspaces*.js"]), 1)
        # sha256 matches an independent digest of the file bytes
        with open(rec["path"], "rb") as handle:
            self.assertEqual(rec["sha256"],
                             hashlib.sha256(handle.read()).hexdigest())

    def test_missing_pty_on_one_abi_fails_only_that_abi(self):
        entries = [e for e in FULL_ENTRIES
                   if e != "lib/x86_64/libworkspace_pty.so"]
        rec = abi.inspect_apk(self.apk("half.apk", entries))
        self.assertFalse(rec["ok"])
        self.assertTrue(rec["workspace_pty"]["arm64-v8a"])
        self.assertFalse(rec["workspace_pty"]["x86_64"])
        self.assertEqual(["missing lib/x86_64/libworkspace_pty.so"], rec["problems"])

    def test_missing_proot_fails(self):
        entries = [e for e in FULL_ENTRIES if e != "lib/arm64-v8a/libproot_exec.so"]
        rec = abi.inspect_apk(self.apk("noproot.apk", entries))
        self.assertFalse(rec["ok"])
        self.assertEqual(["missing lib/arm64-v8a/libproot_exec.so"], rec["problems"])

    def test_no_lib_entries_is_incomplete(self):
        entries = [e for e in FULL_ENTRIES if not e.startswith("lib/")]
        rec = abi.inspect_apk(self.apk("nolib.apk", entries))
        self.assertFalse(rec["ok"])
        self.assertEqual(rec["abis"], [])
        self.assertTrue(any("incomplete" in p for p in rec["problems"]))

    def test_missing_js_assets_reported(self):
        entries = [e for e in FULL_ENTRIES
                   if not ("storage-" in e or "translator-" in e)]
        rec = abi.inspect_apk(self.apk("nojs.apk", entries))
        self.assertFalse(rec["ok"])
        self.assertTrue(any("storage*.js" in p for p in rec["problems"]))
        self.assertTrue(any("translator*.js" in p for p in rec["problems"]))
        self.assertTrue(rec["static_assets"]["workspaces*.js"])

    def test_unsigned_apk_flagged(self):
        entries = [e for e in FULL_ENTRIES if not e.startswith("META-INF/")]
        rec = abi.inspect_apk(self.apk("unsigned.apk", entries))
        self.assertFalse(rec["ok"])
        self.assertFalse(rec["signed"])
        self.assertTrue(any("signature" in p for p in rec["problems"]))

    def test_v2_block_counts_as_signed(self):
        entries = [e for e in FULL_ENTRIES if not e.startswith("META-INF/")]
        rec = abi.inspect_apk(self.apk("v2.apk", entries, v2_block=True))
        self.assertTrue(rec["signed"])
        self.assertTrue(rec["v2_signature_block"])
        self.assertTrue(rec["ok"], rec["problems"])

    def test_not_a_zip(self):
        path = os.path.join(self.dir, "junk.apk")
        with open(path, "wb") as handle:
            handle.write(b"definitely not a zip")
        rec = abi.inspect_apk(path)
        self.assertFalse(rec["ok"])
        self.assertTrue(any("not a zip" in p for p in rec["problems"]))

    def test_zip_slip_names_are_never_extracted(self):
        # Listing must not write anything outside the zip; the tool never
        # extracts at all.
        rec = abi.inspect_apk(self.apk(
            "slip.apk",
            FULL_ENTRIES + ["../evil.txt", "lib/x86_64/../../escape.so"]))
        # ../escape.so lands in lib/<x86_64>/.. structure -> split gives
        # parts of len>3 or len==3 with abi '..'; ensure it is not counted
        # as a packaged ABI.
        self.assertNotIn("..", rec["abis"])
        self.assertFalse(os.path.exists(os.path.join(self.dir, "evil.txt")))
        self.assertTrue(rec["ok"], rec["problems"])

    def test_web_engine_checks_pass_on_kept_classes(self):
        rec = abi.inspect_apk(self.apk("web.apk", FULL_ENTRIES))
        self.assertTrue(rec["ok"], rec["problems"])
        self.assertEqual(rec["web_engine"], {check: True for check in abi.WEB_ENGINE_CHECKS})

    def test_r8_stripped_web_engine_fails(self):
        # The 2.6.0-2.8.1 release shape: abstract stub without members, no
        # @Sharable, no signatures, child channel and encoder removed.
        stripped = build_dex(web_engine_classes(
            server={"access": 0x401, "ctors": []},
            child=None,
            initializer={"annotations": []},
            base=None,
            encoder=None,
            multimap={"methods": [(0x8, "acquireFence")]},
        ))
        rec = abi.inspect_apk(self.apk("r8.apk", FULL_ENTRIES, dex={"classes.dex": stripped}))
        self.assertFalse(rec["ok"])
        self.assertEqual(rec["web_engine"], {check: False for check in abi.WEB_ENGINE_CHECKS})
        self.assertEqual(len(rec["problems"]), 5)

    def test_each_web_engine_check_is_independent(self):
        cases = {
            "NioServerSocketChannel public <init>()": {"server": {"ctors": [(0x1, 1)]}},
            "NioSocketChannel concrete": {"child": {"access": 0x401}},
            "ChannelInitializer @Sharable": {"initializer": {"annotations": [SIG]}},
            "NettyDirectEncoder generic signature": {"encoder": {"annotations": []}},
            "ConcurrentSkipListIntObjMultimap fence methods": {"multimap": {"methods": [(0x8, "acquireFence")]}},
        }
        for check, override in cases.items():
            with self.subTest(check=check):
                dex = build_dex(web_engine_classes(**override))
                rec = abi.inspect_apk(self.apk("one.apk", FULL_ENTRIES, dex={"classes.dex": dex}))
                self.assertEqual(["web engine: %s missing in dex (R8 keep rules)" % check], rec["problems"])

    def test_private_no_arg_constructor_does_not_count(self):
        dex = build_dex(web_engine_classes(server={"ctors": [(0x2, 0)]}))
        rec = abi.inspect_apk(self.apk("priv.apk", FULL_ENTRIES, dex={"classes.dex": dex}))
        self.assertFalse(rec["web_engine"]["NioServerSocketChannel public <init>()"])

    def test_multimap_not_shipped_passes(self):
        dex = build_dex(web_engine_classes(multimap=None))
        rec = abi.inspect_apk(self.apk("nomap.apk", FULL_ENTRIES, dex={"classes.dex": dex}))
        self.assertTrue(rec["ok"], rec["problems"])

    def test_merged_encoder_base_fails(self):
        # R8 merging MessageToByteEncoder away breaks TypeParameterMatcher.
        dex = build_dex(web_engine_classes(encoder={"superclass": "Ljava/lang/Object;"}))
        rec = abi.inspect_apk(self.apk("merged.apk", FULL_ENTRIES, dex={"classes.dex": dex}))
        self.assertFalse(rec["web_engine"]["NettyDirectEncoder generic signature"])

    def test_classes_split_across_dex_files(self):
        classes = web_engine_classes()
        entries = FULL_ENTRIES + ["classes2.dex"]
        dex = {"classes.dex": build_dex(classes[:3]), "classes2.dex": build_dex(classes[3:])}
        rec = abi.inspect_apk(self.apk("multi.apk", entries, dex=dex))
        self.assertTrue(rec["ok"], rec["problems"])

    def test_missing_or_corrupt_dex_reported(self):
        entries = [e for e in FULL_ENTRIES if e != "classes.dex"]
        rec = abi.inspect_apk(self.apk("nodex.apk", entries))
        self.assertEqual(["no classes*.dex entries"], rec["problems"])
        rec = abi.inspect_apk(self.apk("bad.apk", FULL_ENTRIES, dex={"classes.dex": b"dex\n038\0" + b"\xff" * 200}))
        self.assertFalse(rec["ok"])
        self.assertTrue(rec["problems"][0].startswith("cannot parse dex"), rec["problems"])

    def test_embedded_vcs_revision_extracted(self):
        path = os.path.join(self.dir, "vcs.apk")
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as zf:
            for name in FULL_ENTRIES:
                zf.writestr(name, "x")
            zf.writestr(
                abi.VCS_INFO_ENTRY,
                'repositories {\n  system: GIT\n  root_path: "$projectDir"\n'
                '  revision: "39b4c44deadbeef"\n}\n')
        rec = abi.inspect_apk(path)
        self.assertEqual(rec["embedded_vcs_revision"], "39b4c44deadbeef")


class CliTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = self.tmp.name

    def tearDown(self):
        self.tmp.cleanup()

    def apk(self, name, entries=FULL_ENTRIES, **kw):
        return make_apk(os.path.join(self.dir, name), entries, **kw)

    def run_cli(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = abi.main(list(argv))
        return code, out.getvalue(), err.getvalue()

    def test_sidecar_written_and_apk_untouched(self):
        apk = self.apk("app-debug.apk")
        before = (os.path.getsize(apk), abi.sha256_file(apk),
                  os.stat(apk).st_mtime_ns)
        code, out, _ = self.run_cli("--no-git", apk)
        self.assertEqual(code, 0)
        self.assertEqual(before, (os.path.getsize(apk), abi.sha256_file(apk),
                                  os.stat(apk).st_mtime_ns))
        sidecar = apk + ".build-info.json"
        self.assertTrue(os.path.isfile(sidecar))
        with open(sidecar, encoding="utf-8") as fh:
            doc = json.load(fh)
        self.assertEqual(doc["schema"], abi.SCHEMA)
        self.assertTrue(doc["ok"])
        self.assertEqual(len(doc["files"]), 1)
        self.assertEqual(doc["source"]["commit"], "unavailable")

    def test_combined_output_file(self):
        a, b = self.apk("a.apk"), self.apk("b.apk", v2_block=True)
        out_path = os.path.join(self.dir, "info.json")
        code, _, _ = self.run_cli("--no-git", "-o", out_path, a, b)
        self.assertEqual(code, 0)
        with open(out_path, encoding="utf-8") as fh:
            doc = json.load(fh)
        self.assertEqual(len(doc["files"]), 2)

    def test_stdout_is_valid_json(self):
        apk = self.apk("std.apk")
        code, out, _ = self.run_cli("--no-git", "--stdout", apk)
        self.assertEqual(code, 0)
        doc = json.loads(out)  # summary must not pollute stdout
        self.assertTrue(doc["ok"])

    def test_refuses_apk_output_path(self):
        apk = self.apk("c.apk")
        target = self.apk("target.apk")
        with self.assertRaises(SystemExit):
            self.run_cli("--no-git", "-o", target, apk)

    def test_refuses_output_equal_input(self):
        apk = self.apk("d.apk")
        with self.assertRaises(SystemExit):
            self.run_cli("--no-git", "-o", apk, apk)

    def test_check_only_writes_nothing(self):
        apk = self.apk("e.apk")
        code, _, _ = self.run_cli("--no-git", "--check-only", apk)
        self.assertEqual(code, 0)
        self.assertFalse(os.path.exists(apk + ".build-info.json"))

    def test_failing_apk_exit_code(self):
        bad = self.apk("bad.apk", ["classes.dex"])
        code, _, _ = self.run_cli("--no-git", "--stdout", bad)
        self.assertEqual(code, 1)

    def test_missing_input_exit_code(self):
        code, _, _ = self.run_cli(os.path.join(self.dir, "nope.apk"))
        self.assertEqual(code, 2)


if __name__ == "__main__":
    unittest.main()
