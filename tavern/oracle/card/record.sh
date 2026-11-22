#!/usr/bin/env bash
# 用法：ST_ORACLE=/path/to/oracle.py tavern/oracle/card/record.sh book
# 把 books.json 注入成 BOOKS，在真 ST 页面里执行 record_book.js，结果写进 tavern/src/test/resources/golden/card/convert-book.json。
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
: "${ST_ORACLE:?set ST_ORACLE to the oracle.py path}"
case "${1:-}" in
  book)
    tmp="$(mktemp --suffix=.js)"; trap 'rm -f "$tmp"' EXIT
    { printf 'const BOOKS = '; cat "$here/books.json"; printf ';\n'; cat "$here/record_book.js"; } > "$tmp"
    out="$here/../../src/test/resources/golden/card/convert-book.json"
    python3 "$ST_ORACLE" eval "$tmp" > "$out.new"
    mv "$out.new" "$out"
    echo "wrote $out ($(wc -c < "$out") bytes)"
    ;;
  back)
    # 把 convert-book.json 里上游转出的条目，再交给上游的 convertWorldInfoToCharacterBook 转回卡内世界书（node，不需要 oracle 在运行，需要 ST_DIR）
    : "${ST_DIR:?set ST_DIR to the SillyTavern 1.19.0 source root}"
    golden="$here/../../src/test/resources/golden/card"
    python3 - "$golden" "$here/st_node.mjs" <<'PY'
import json, subprocess, sys, tempfile, os
golden, node_script = sys.argv[1], sys.argv[2]
cases = json.load(open(f"{golden}/convert-book.json"))["cases"]
out = {}
for name, case in cases.items():
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        json.dump(case["entries"], f)
    book_name = case["input"].get("name") or name
    res = subprocess.run(["node", node_script, "book-back", f.name, book_name], capture_output=True, text=True, check=True)
    os.unlink(f.name)
    out[name] = {"name": book_name, "entries": case["entries"], "output": json.loads(res.stdout)}
json.dump({"cases": out}, open(f"{golden}/book-back.json", "w"), ensure_ascii=False, indent=1)
print(f"wrote {golden}/book-back.json ({len(out)} books)")
PY
    ;;
  import)
    # 三张自造卡交给真 ST 的服务端导入，把它存下的卡记下来（需要 ST 在运行：oracle.py start；ST_URL 默认 http://127.0.0.1:18123）
    cards="$here/../../src/test/resources/cards"
    python3 "$here/st_import.py" "$here/../../src/test/resources/golden/card/st-import.json" \
      "$cards/v1-lantern-keeper.json" "$cards/v2-harbor-cartographer.json" "$cards/v3-clockwork-orchard.json"
    ;;
  *) echo "usage: $0 book|back|import" >&2; exit 2;;
esac
