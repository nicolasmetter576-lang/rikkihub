#!/usr/bin/env bash
# 录提示词组装 golden：oracle.py eval 执行 lib.js + record_*.js（拼成一个 async 函数体），
# 输出直接写进 tavern/src/test/resources/golden/prompt/<name>.json。
# 用法：ST_ORACLE=/path/to/oracle.py tavern/oracle/prompt/record.sh <name>...
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
# ST_ORACLE: path to the oracle.py that evaluates a script against a running SillyTavern page.
: "${ST_ORACLE:?set ST_ORACLE to the oracle.py path (it runs the recording script against SillyTavern 1.19)}"
ORACLE="$ST_ORACLE"
DIR="$here"
OUT="$here/../../src/test/resources/golden/prompt"
mkdir -p "$OUT"

for name in "$@"; do
  tmp=$(mktemp --suffix=.js)
  cat "$DIR/lib.js" "$DIR/record_${name}.js" > "$tmp"
  python3 "$ORACLE" eval "$tmp" > "$OUT/${name}.json"
  rm -f "$tmp"
  python3 - "$OUT/${name}.json" <<'EOF'
import json, sys
d = json.load(open(sys.argv[1]))
cases = d.get('cases', [])
bad = [c['name'] for c in cases if c.get('genError') or c.get('expected') is None]
print(f"{sys.argv[1]}: {len(cases)} cases" + (f" BAD={bad}" if bad else ""))
EOF
done
