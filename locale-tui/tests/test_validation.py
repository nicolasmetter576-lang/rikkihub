"""Offline validation boundaries, including mixed XML and Java placeholders."""

import pytest
from services.validation import escape_android_quotes, extract_placeholders, validate_translation


@pytest.mark.parametrize("source,target", [
    ("Hello %1$s %2$d", "%2$d 好 %1$s"),
    ("%.2f%%", "值 %.2f%%"), ("%1$tY-%1$tm", "%1$tm/%1$tY"),
    ("%b %h %o %X %c %n", "%b %h %o %X %c %n"),
    ("a\\nb", "甲\\n乙"), ("a\nb", "甲\n乙"),
    ('Hello <b>%1$s</b>!', '你好 <b>%1$s</b>!'),
    ('<xliff:g xmlns:xliff="urn:xliff" id="name">%s</xliff:g>', '<xliff:g xmlns:xliff="urn:xliff" id="name">%s</xliff:g>'),
    ("A & B < 3", "甲 & 乙 < 3"),
])
def test_valid(source, target):
    assert validate_translation(source, target) is None


@pytest.mark.parametrize("source,target,error", [
    ("A", "", "empty"), ("A", " \n ", "empty"), ("A", None, "empty"),
    ("%s %d", "%d %s", "order"), ("%s", "%1$s", "placeholder"),
    ("%1$s %1$s", "%1$s", "placeholder"), ("%%", "", "empty"),
    ("%1$tY", "%1$ty", "placeholder"), ("%n", "n", "placeholder"),
    ("a\\nb", "ab", "line break"), ("a\nb", "a\\nb", "line break"),
    ('<b>A</b>', 'A', "markup"), ('A', '<b>A</b>', "markup"),
    ('<font color="red">A</font>', '<font color="blue">甲</font>', "attribute"),
    ('<b>A</b>', '<b>甲', "invalid XML"), ('A', 'A\x00', "invalid XML"),
    ('A', '<!DOCTYPE x>A', "invalid XML"), ('A', '<?bad?>A', "invalid XML"),
])
def test_invalid(source, target, error):
    assert error in validate_translation(source, target)


@pytest.mark.parametrize("value,expected", [
    ("don't", "don\\'t"), ("don\\'t", "don\\'t"),
    ('say "hi"', 'say \\"hi\\"'),
    ("\\\\'", "\\\\\\'"), ("\\\\\\'", "\\\\\\'"),
    ('<font color="red">don\'t</font>', '<font color="red">don\\\'t</font>'),
])
def test_quote_escaping(value, expected):
    assert escape_android_quotes(value) == expected
    assert escape_android_quotes(expected) == expected


def test_placeholder_multiplicity_and_full_formatter():
    assert extract_placeholders("%1$s %1$s %2$tY %.2f%% %n %B") == sorted(["%1$s", "%1$s", "%2$tY", "%.2f", "%%", "%n", "%B"])
