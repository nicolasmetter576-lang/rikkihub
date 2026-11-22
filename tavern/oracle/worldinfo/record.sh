#!/usr/bin/env bash
# 用法：tavern/oracle/worldinfo/record.sh matching   → 写 tavern/src/test/resources/golden/worldinfo/matching.json
# 需要 oracle 在运行（ST_ORACLE 指向 oracle.py，先 `python3 "$ST_ORACLE" start`）
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
: "${ST_ORACLE:?set ST_ORACLE to the oracle.py path}"
name="$1"
out="$here/../../src/test/resources/golden/worldinfo/$name.json"
tmp="$(mktemp --suffix=.js)"
trap 'rm -f "$tmp"' EXIT
cat "$here/lib.js" "$here/record_$name.js" > "$tmp"
python3 "$ST_ORACLE" eval "$tmp" > "$out.new"
mv "$out.new" "$out"
echo "wrote $out ($(wc -c < "$out") bytes)"
