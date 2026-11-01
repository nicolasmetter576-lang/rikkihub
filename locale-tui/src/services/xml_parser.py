"""Android strings.xml parsing and atomic, in-place resource updates."""

import hashlib
import os
from pathlib import Path
import tempfile

from lxml import etree
from services.validation import parse_fragment

_UNSET = object()


class StringsXmlParser:
    """Only string nodes requested by the caller are changed; other resources stay."""

    @staticmethod
    def fingerprint(file_path: Path) -> str | None:
        return hashlib.sha256(file_path.read_bytes()).hexdigest() if file_path.exists() else None

    @staticmethod
    def _load(file_path: Path):
        parser = etree.XMLParser(resolve_entities=False, no_network=True, remove_blank_text=False)
        tree = etree.parse(str(file_path), parser)
        if tree.docinfo.doctype or tree.getroot().tag != "resources":
            raise ValueError(f"Unsupported resource document: {file_path}")
        names = [elem.get("name") for elem in tree.getroot().findall("string")]
        if any(not name for name in names) or len(names) != len(set(names)):
            raise ValueError(f"Missing or duplicate string names: {file_path}")
        return tree

    @staticmethod
    def parse(file_path: Path, *, translatable_only: bool = False) -> dict[str, str]:
        """Missing files are empty; malformed existing files raise (never overwrite)."""
        if not file_path.exists():
            return {}
        root = StringsXmlParser._load(file_path).getroot()
        return {elem.get("name"): StringsXmlParser._get_text_content(elem)
                for elem in root.findall("string")
                if not translatable_only or elem.get("translatable") != "false"}

    @staticmethod
    def _get_text_content(elem) -> str:
        # Plain text is decoded by lxml; inline tags and tails are retained.
        return (elem.text or "") + "".join(etree.tostring(child, encoding="unicode", with_tail=True)
                                          for child in elem)

    @staticmethod
    def _set_value(elem, value: str) -> None:
        fragment = parse_fragment(value)
        for child in list(elem):
            elem.remove(child)
        elem.text = fragment.text
        for child in list(fragment):
            elem.append(child)

    @staticmethod
    def _atomic_save(file_path: Path, tree, expected) -> None:
        """Serialize before touching disk, fsync temp, check conflict, atomic replace."""
        if file_path.is_symlink():
            raise ValueError(f"Refusing to replace symlink: {file_path}")
        content = etree.tostring(tree, encoding="utf-8", xml_declaration=True)
        content = content.rstrip(b"\n") + b"\n"
        file_path.parent.mkdir(parents=True, exist_ok=True)
        temp_path = None
        try:
            with tempfile.NamedTemporaryFile(dir=file_path.parent, prefix=".strings-", suffix=".tmp", delete=False) as f:
                temp_path = Path(f.name)
                if file_path.exists():
                    os.fchmod(f.fileno(), file_path.stat().st_mode & 0o777)
                f.write(content)
                f.flush()
                os.fsync(f.fileno())
            if StringsXmlParser.fingerprint(file_path) != expected:
                raise ValueError(f"File changed since loading; reload before saving: {file_path}")
            os.replace(temp_path, file_path)
        finally:
            if temp_path is not None:
                temp_path.unlink(missing_ok=True)

    @staticmethod
    def write(file_path: Path, entries: dict[str, str]) -> None:
        """Compatibility entry point: patch existing files, never discard resources."""
        StringsXmlParser.update_entries(file_path, entries)

    @staticmethod
    def update_entry(file_path: Path, key: str, value: str) -> None:
        StringsXmlParser.update_entries(file_path, {key: value})

    @staticmethod
    def update_entries(file_path: Path, entries: dict[str, str], *, delete_keys=(), expected_fingerprint=_UNSET) -> None:
        """Patch selected values preserving order, attributes and unrelated nodes.

        The optional fingerprint guards the caller's read/translate/save interval.
        Atomicity is per-file, not across multiple language files.
        """
        before = StringsXmlParser.fingerprint(file_path)
        if expected_fingerprint is not _UNSET and before != expected_fingerprint:
            raise ValueError(f"File changed since loading: {file_path}")
        tree = StringsXmlParser._load(file_path) if file_path.exists() else etree.ElementTree(etree.Element("resources"))
        root = tree.getroot()
        pending = dict(entries)
        for key, value in pending.items():
            if not isinstance(key, str) or not key or not isinstance(value, str):
                raise ValueError("String keys and values must be strings; keys must be non-empty")
        changed = False
        for elem in list(root.findall("string")):
            key = elem.get("name")
            if key in delete_keys:
                root.remove(elem)
                pending.pop(key, None)
                changed = True
            elif key in pending:
                value = pending.pop(key)
                if StringsXmlParser._get_text_content(elem) != value:
                    StringsXmlParser._set_value(elem, value)
                    changed = True
        for key, value in pending.items():
            elem = etree.SubElement(root, "string", name=key)
            StringsXmlParser._set_value(elem, value)
            elem.tail = "\n"
            changed = True
        if changed:
            StringsXmlParser._atomic_save(file_path, tree, before)

    @staticmethod
    def delete_entry(file_path: Path, key: str) -> bool:
        if key not in StringsXmlParser.parse(file_path):
            return False
        StringsXmlParser.update_entries(file_path, {}, delete_keys=(key,))
        return True
