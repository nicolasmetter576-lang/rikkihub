"""Offline safety checks for Android strings (not a replacement for aapt)."""

import re
from lxml import etree

# Java Formatter: indexed arguments, flags, precision, date/time, %% and %n.
_PLACEHOLDER = re.compile(r"%(?:\d+\$)?[-#+ 0,(<]*\d*(?:\.\d+)?(?:[tT][a-zA-Z]|[bBhHsScCdoxXeEfgGaAn%])")
_TAG = re.compile(r"</?[A-Za-z_][^>]*>")
_TAG_HINT = re.compile(r"</?[A-Za-z_]")
_BARE_AMP = re.compile(r"&(?!#\d+;|#x[\da-fA-F]+;|amp;|lt;|gt;|quot;|apos;)")


def extract_placeholders(value: str) -> list[str]:
    """Return all format specifiers, retaining multiplicity."""
    return sorted(_PLACEHOLDER.findall(value))


def escape_android_quotes(value: str) -> str:
    """Escape quotes in text, not XML attributes; respect backslash parity."""
    def escape(text: str) -> str:
        result = []
        slashes = 0
        for char in text:
            if char in "'\"" and slashes % 2 == 0:
                result.append("\\")
            result.append(char)
            slashes = slashes + 1 if char == "\\" else 0
        return "".join(result)

    return "".join(part if _TAG.fullmatch(part) else escape(part)
                   for part in re.split(f"({_TAG.pattern})", value))


def parse_fragment(value: str):
    """Parse mixed Android markup safely; plain '<'/'&' remain plain text."""
    if any(token in value for token in ("<!", "<?")):
        raise ValueError("XML declarations, comments and entities are not allowed in values")
    if not _TAG_HINT.search(value):
        probe = etree.Element("string")
        probe.text = value  # lxml rejects invalid XML characters here
        return probe
    parser = etree.XMLParser(resolve_entities=False, no_network=True)
    return etree.fromstring(("<string>" + _BARE_AMP.sub("&amp;", value) + "</string>").encode(), parser)


def _markup_signature(value: str):
    root = parse_fragment(value)
    return [(elem.tag, tuple(sorted(elem.attrib.items())), len(list(elem.iterancestors())))
            for elem in root.iterdescendants()]


def validate_translation(source: str, translated: str) -> str | None:
    """Return a diagnostic for an unsafe replacement, otherwise None."""
    if not isinstance(translated, str) or not translated.strip():
        return "empty or non-string translation"
    if extract_placeholders(source) != extract_placeholders(translated):
        return "placeholder mismatch"
    # Only explicit indexes permit reordering arguments. %%/%n consume no argument.
    def implicit(value):
        return [p for p in _PLACEHOLDER.findall(value) if "$" not in p and p not in ("%%", "%n")]
    if implicit(source) != implicit(translated):
        return "unindexed placeholder order mismatch"
    if source.count("\\n") != translated.count("\\n") or source.count("\n") != translated.count("\n"):
        return "line break count mismatch"
    try:
        if _markup_signature(source) != _markup_signature(translated):
            return "XML markup/attribute mismatch"
    except (ValueError, etree.XMLSyntaxError) as exc:
        return f"invalid XML value: {exc}"
    return None
