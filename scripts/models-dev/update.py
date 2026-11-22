#!/usr/bin/env python3
"""Rebuild app/src/main/assets/models_dev.json from https://models.dev/api.json.

The app ships this snapshot and never fetches it at run time; run this script and release to update.
Python 3 standard library only.

    python3 scripts/models-dev/update.py                 # download, write the snapshot
    python3 scripts/models-dev/update.py --input api.json  # use a saved copy instead of downloading
    python3 scripts/models-dev/update.py --check         # exit 1 if the snapshot on disk is not what it would write

Format: {"version": 1, "source": ..., "entries": {"<model id>": [flags, context(, input, output, cache_read)]}}
  flags   bit 0 image input, bit 1 image output, bit 2 tool calls, bit 3 reasoning
  context context window in tokens, 0 when unknown
  input / output / cache_read  reference price in USD per million tokens (cache_read may be null);
          left out when no provider lists a price. The model maker's own price wins; otherwise the
          price most providers agree on. The app only offers it as a one-tap "reference price".

The same model is listed by many providers under different spellings (`claude-sonnet-4-5`,
`anthropic/claude-sonnet-4.5`), so every record is filed under its full id, the part after the last
`/`, and its canonical id; records that share a key are merged by majority vote. Providers more often
forget to mark a capability than to mark one that is not there, so a tie counts as supported.
"""
import argparse
import json
import math
import sys
import urllib.request
from pathlib import Path

API_URL = "https://models.dev/api.json"
DEFAULT_OUTPUT = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "assets" / "models_dev.json"
FORMAT_VERSION = 1

IMAGE_INPUT = 1
IMAGE_OUTPUT = 2
TOOL_CALL = 4
REASONING = 8

# The model makers' own listings: their price is the reference price
OFFICIAL_PROVIDERS = {
    "anthropic", "openai", "google", "deepseek", "xai", "mistral", "moonshotai", "zhipuai",
    "alibaba", "minimax", "cohere",
}

# A snapshot this small means the download was cut off or the format changed; keep the old one.
MIN_ENTRIES = 1000


def keys_for(model_id, model):
    keys = set()
    for raw in (model_id, model.get("canonical_model_id")):
        if not isinstance(raw, str):
            continue
        lowered = raw.strip().lower()
        if lowered:
            keys.add(lowered)
            keys.add(lowered.rsplit("/", 1)[-1])
    keys.discard("")
    return keys


class Votes:
    def __init__(self):
        self.total = 0
        self.image_input = 0
        self.image_output = 0
        self.tool = 0
        self.reasoning = 0
        self.contexts = {}
        self.official_prices = {}
        self.prices = {}

    def add_price(self, model, official):
        cost = model.get("cost") or {}
        if not isinstance(cost, dict):
            return
        values = []
        for field in ("input", "output", "cache_read"):
            value = cost.get(field)
            values.append(float(value) if isinstance(value, (int, float)) and not isinstance(value, bool)
                          and math.isfinite(value) and 0 <= value <= 1_000_000 else None)
        if values[0] is None or values[1] is None:
            return
        price = tuple(round(v, 6) if v is not None else None for v in values)
        bucket = self.official_prices if official else self.prices
        bucket[price] = bucket.get(price, 0) + 1

    def price(self):
        for bucket in (self.official_prices, self.prices):
            if bucket:
                # most votes; on a tie the higher input price (resellers undercut, rarely overcharge)
                return list(max(bucket.items(), key=lambda item: (item[1], item[0][0], item[0][1]))[0])
        return None

    def add(self, model):
        modalities = model.get("modalities") or {}
        self.total += 1
        if "image" in (modalities.get("input") or []):
            self.image_input += 1
        if "image" in (modalities.get("output") or []):
            self.image_output += 1
        if model.get("tool_call") is True:
            self.tool += 1
        if model.get("reasoning") is True:
            self.reasoning += 1
        context = (model.get("limit") or {}).get("context")
        if isinstance(context, int) and not isinstance(context, bool) and context > 0:
            self.contexts[context] = self.contexts.get(context, 0) + 1

    def _majority(self, count):
        return count * 2 >= self.total

    def entry(self):
        flags = 0
        if self._majority(self.image_input):
            flags |= IMAGE_INPUT
        if self._majority(self.image_output):
            flags |= IMAGE_OUTPUT
        if self._majority(self.tool):
            flags |= TOOL_CALL
        if self._majority(self.reasoning):
            flags |= REASONING
        # most providers agree on one window; on a tie take the larger
        context = max(self.contexts.items(), key=lambda item: (item[1], item[0]))[0] if self.contexts else 0
        price = self.price()
        if price is None:
            return [flags, context]
        return [flags, context] + [int(v) if v is not None and v == int(v) else v for v in price]


def build_entries(api):
    votes = {}
    for provider_id, provider in api.items():
        if not isinstance(provider, dict):
            continue
        official = provider_id in OFFICIAL_PROVIDERS
        for model_id, model in (provider.get("models") or {}).items():
            if not isinstance(model, dict):
                continue
            for key in keys_for(model_id, model):
                vote = votes.setdefault(key, Votes())
                vote.add(model)
                vote.add_price(model, official)
    return {key: votes[key].entry() for key in sorted(votes)}


def render(entries):
    # one entry per line: small diffs when the snapshot is refreshed
    lines = [json.dumps(key, ensure_ascii=False) + ":" + json.dumps(value, separators=(",", ":")) for key, value in entries.items()]
    return (
        '{"version":%d,"source":%s,"entries":{\n' % (FORMAT_VERSION, json.dumps(API_URL))
        + ",\n".join(lines)
        + "\n}}\n"
    )


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--input", type=Path, help="read this api.json instead of downloading")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true", help="do not write; exit 1 if the file differs")
    args = parser.parse_args(argv)

    if args.input:
        api = json.loads(args.input.read_text(encoding="utf-8"))
    else:
        with urllib.request.urlopen(API_URL, timeout=60) as response:
            api = json.load(response)

    entries = build_entries(api)
    if len(entries) < MIN_ENTRIES:
        print(f"only {len(entries)} entries, expected at least {MIN_ENTRIES}; keeping the existing snapshot", file=sys.stderr)
        return 1
    text = render(entries)

    if args.check:
        current = args.output.read_text(encoding="utf-8") if args.output.exists() else ""
        if current != text:
            print(f"{args.output} is out of date", file=sys.stderr)
            return 1
        return 0
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(text, encoding="utf-8")
    print(f"wrote {len(entries)} entries to {args.output} ({len(text.encode('utf-8')) // 1024} KiB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
