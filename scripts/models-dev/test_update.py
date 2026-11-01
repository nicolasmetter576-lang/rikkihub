import json
import tempfile
import unittest
from pathlib import Path

import update


def model(image_in=False, image_out=False, tool=False, reasoning=False, context=None, canonical=None):
    value = {
        "modalities": {
            "input": ["text"] + (["image"] if image_in else []),
            "output": ["text"] + (["image"] if image_out else []),
        },
        "tool_call": tool,
        "reasoning": reasoning,
    }
    if context is not None:
        value["limit"] = {"context": context}
    if canonical:
        value["canonical_model_id"] = canonical
    return value


class BuildEntriesTest(unittest.TestCase):
    def test_flags_and_context(self):
        api = {"p": {"models": {"m": model(image_in=True, tool=True, reasoning=True, context=200000)}}}
        self.assertEqual(update.build_entries(api)["m"], [update.IMAGE_INPUT | update.TOOL_CALL | update.REASONING, 200000])

    def test_image_output_flag(self):
        api = {"p": {"models": {"m": model(image_in=True, image_out=True)}}}
        self.assertEqual(update.build_entries(api)["m"][0], update.IMAGE_INPUT | update.IMAGE_OUTPUT)

    def test_keys_are_lowercase_and_include_the_part_after_the_last_slash(self):
        api = {"p": {"models": {"Anthropic/Claude-Sonnet-4.5": model()}}}
        entries = update.build_entries(api)
        self.assertIn("anthropic/claude-sonnet-4.5", entries)
        self.assertIn("claude-sonnet-4.5", entries)

    def test_canonical_id_is_an_alias(self):
        api = {"p": {"models": {"sonnet": model(tool=True, canonical="anthropic/claude-sonnet-4-5")}}}
        entries = update.build_entries(api)
        self.assertEqual(entries["claude-sonnet-4-5"], entries["sonnet"])
        self.assertIn("anthropic/claude-sonnet-4-5", entries)

    def test_majority_vote_and_a_tie_counts_as_supported(self):
        api = {
            "a": {"models": {"x": model(tool=True)}},
            "b": {"models": {"x": model(tool=True)}},
            "c": {"models": {"x": model(tool=False)}},
            "d": {"models": {"y": model(tool=True)}},
            "e": {"models": {"y": model(tool=False)}},
            "f": {"models": {"z": model(tool=False)}},
            "g": {"models": {"z": model(tool=False)}},
            "h": {"models": {"z": model(tool=True)}},
        }
        entries = update.build_entries(api)
        self.assertTrue(entries["x"][0] & update.TOOL_CALL)
        self.assertTrue(entries["y"][0] & update.TOOL_CALL)
        self.assertFalse(entries["z"][0] & update.TOOL_CALL)

    def test_context_takes_the_most_common_value_and_the_larger_on_a_tie(self):
        api = {
            "a": {"models": {"x": model(context=128000), "t": model(context=100000)}},
            "b": {"models": {"x": model(context=128000), "t": model(context=200000)}},
            "c": {"models": {"x": model(context=64000)}},
        }
        entries = update.build_entries(api)
        self.assertEqual(entries["x"][1], 128000)
        self.assertEqual(entries["t"][1], 200000)

    def test_unknown_context_is_zero_and_garbage_is_ignored(self):
        api = {"p": {"models": {"a": model(), "b": model(context=-5), "c": {"limit": {"context": "big"}}, "d": "oops"}}}
        entries = update.build_entries(api)
        self.assertEqual(entries["a"][1], 0)
        self.assertEqual(entries["b"][1], 0)
        self.assertEqual(entries["c"][1], 0)
        self.assertNotIn("d", entries)

    def test_non_dict_providers_and_missing_fields_do_not_crash(self):
        api = {"p": "nope", "q": {}, "r": {"models": {"m": {}}}}
        self.assertEqual(update.build_entries(api)["m"], [0, 0])


class RenderTest(unittest.TestCase):
    def test_output_is_valid_json_sorted_and_stable(self):
        entries = update.build_entries({"p": {"models": {"b": model(tool=True), "a": model(image_in=True, context=8000)}}})
        text = update.render(entries)
        document = json.loads(text)
        self.assertEqual(document["version"], update.FORMAT_VERSION)
        self.assertEqual(list(document["entries"]), ["a", "b"])
        self.assertEqual(text, update.render(entries))

    def test_main_refuses_a_tiny_snapshot_and_keeps_the_old_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "api.json"
            source.write_text(json.dumps({"p": {"models": {"m": model()}}}), encoding="utf-8")
            output = Path(tmp) / "out.json"
            output.write_text("old", encoding="utf-8")
            self.assertEqual(update.main(["--input", str(source), "--output", str(output)]), 1)
            self.assertEqual(output.read_text(encoding="utf-8"), "old")

    def test_check_detects_a_stale_file(self):
        models = {f"m{i}": model(tool=True) for i in range(update.MIN_ENTRIES + 5)}
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "api.json"
            source.write_text(json.dumps({"p": {"models": models}}), encoding="utf-8")
            output = Path(tmp) / "out.json"
            self.assertEqual(update.main(["--input", str(source), "--output", str(output), "--check"]), 1)
            self.assertEqual(update.main(["--input", str(source), "--output", str(output)]), 0)
            self.assertEqual(update.main(["--input", str(source), "--output", str(output), "--check"]), 0)


if __name__ == "__main__":
    unittest.main()
