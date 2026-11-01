"""Atomic-save, corruption, preservation and optimistic conflict tests."""

import os

import pytest
from lxml import etree
from services.xml_parser import StringsXmlParser as XML

RICH = '''<?xml version="1.0" encoding="utf-8"?>
<!--before-->
<resources xmlns:xliff="urn:xliff">
  <!--keep comment-->
  <string name="z" translatable="false">Brand</string>
  <plurals name="count"><item quantity="one">One</item><item quantity="other">Many</item></plurals>
  <string-array name="arr"><item>A</item></string-array>
  <string name="a" formatted="false">Hello <b>World</b> &amp; tail</string>
  <string name="g"><xliff:g id="name">%s</xliff:g> tail</string>
</resources>
<!--after-->
'''


@pytest.fixture
def path(tmp_path):
    p = tmp_path / "values-zh" / "strings.xml"
    p.parent.mkdir()
    p.write_text(RICH)
    return p


def test_parse_mixed_and_nontranslatable(path):
    value = XML.parse(path)["a"]
    assert value.startswith("Hello <b") and "World</b> &amp; tail" in value
    assert "xliff:g" in XML.parse(path)["g"] and XML.parse(path)["g"].endswith(" tail")
    assert "z" in XML.parse(path) and "z" not in XML.parse(path, translatable_only=True)


def test_patch_preserves_unrelated_nodes_order_attributes_comments(path):
    old_root = etree.parse(str(path)).getroot()
    untouched = {tag: etree.tostring(old_root.find(tag)) for tag in ["plurals", "string-array"]}
    XML.update_entries(path, {"a": "你好 <b>世界</b> & 尾", "new": "New"})
    root = etree.parse(str(path)).getroot()
    assert [e.get("name") for e in root.findall("string")] == ["z", "a", "g", "new"]
    assert root.find("string[@name='a']").get("formatted") == "false"
    assert root.find("string[@name='z']").get("translatable") == "false"
    for tag, value in untouched.items():
        assert etree.tostring(root.find(tag)) == value
    assert "<!--keep comment-->" in path.read_text() and "<!--before-->" in path.read_text() and "<!--after-->" in path.read_text()
    assert "<b>世界</b> &amp; 尾" in path.read_text()


def test_noop_byte_identical_write_is_patch(path):
    before = path.read_bytes()
    XML.write(path, XML.parse(path))
    assert path.read_bytes() == before
    XML.write(path, {"new": "x"})
    assert set(XML.parse(path)) == {"z", "a", "g", "new"}
    assert "plurals" in path.read_text()


@pytest.mark.parametrize("content", ["<resources><string", "<oops/>", '<resources><string name="x">a</string><string name="x">b</string></resources>', '<resources><string>a</string></resources>', '<!DOCTYPE resources [<!ENTITY x SYSTEM "file:///etc/passwd">]><resources/>'])
def test_malformed_or_unsafe_never_overwritten(path, content):
    path.write_text(content)
    before = path.read_bytes()
    with pytest.raises((ValueError, etree.XMLSyntaxError)):
        XML.parse(path)
    with pytest.raises((ValueError, etree.XMLSyntaxError)):
        XML.update_entries(path, {"new": "bad"})
    assert path.read_bytes() == before


def test_fingerprint_conflict_preserves_external_change(path):
    fingerprint = XML.fingerprint(path)
    path.write_text(path.read_text().replace("Brand", "User Brand"))
    before = path.read_bytes()
    with pytest.raises(ValueError, match="changed"):
        XML.update_entries(path, {"a": "overwrite"}, expected_fingerprint=fingerprint)
    assert path.read_bytes() == before


def test_missing_file_creation_conflict(tmp_path):
    path = tmp_path / "new" / "strings.xml"
    assert XML.fingerprint(path) is None and XML.parse(path) == {}
    XML.write(path, {"x": "external"})
    with pytest.raises(ValueError, match="changed"):
        XML.update_entries(path, {"x": "overwrite"}, expected_fingerprint=None)
    assert XML.parse(path) == {"x": "external"}


def test_atomic_replace_failure_keeps_file_and_cleans_temp(path, monkeypatch):
    before = path.read_bytes()
    def fail(*args):
        raise OSError("simulated full disk")
    monkeypatch.setattr(os, "replace", fail)
    with pytest.raises(OSError, match="full disk"):
        XML.update_entries(path, {"new": "N"})
    assert path.read_bytes() == before and not list(path.parent.glob(".strings-*.tmp"))


def test_conflict_during_temp_write(path, monkeypatch):
    real_fingerprint = XML.fingerprint
    calls = 0
    def fingerprint(p):
        nonlocal calls
        calls += 1
        if calls == 2:
            path.write_text(path.read_text().replace("Brand", "Concurrent"))
        return real_fingerprint(p)
    monkeypatch.setattr(XML, "fingerprint", fingerprint)
    with pytest.raises(ValueError, match="changed"):
        XML.update_entries(path, {"new": "N"})
    assert "Concurrent" in path.read_text() and "name=\"new\"" not in path.read_text()
    assert not list(path.parent.glob(".strings-*.tmp"))


def test_invalid_value_serializes_before_disk_change(path):
    before = path.read_bytes()
    with pytest.raises(ValueError):
        XML.update_entries(path, {"a": "invalid\x00"})
    assert path.read_bytes() == before


def test_atomic_mode_retained_and_deletion(path):
    path.chmod(0o640)
    XML.update_entry(path, "a", "plain")
    assert path.stat().st_mode & 0o777 == 0o640
    assert "<b>" not in path.read_text()
    assert XML.delete_entry(path, "a") and not XML.delete_entry(path, "absent")
    assert "plurals" in path.read_text() and "a" not in XML.parse(path)


def test_symlink_refused(path, tmp_path):
    link = tmp_path / "linked.xml"
    link.symlink_to(path)
    before = path.read_bytes()
    with pytest.raises(ValueError, match="symlink"):
        XML.update_entry(link, "a", "unsafe")
    assert path.read_bytes() == before and link.is_symlink()
