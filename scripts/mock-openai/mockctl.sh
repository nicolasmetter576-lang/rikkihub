#!/bin/bash
# start|stop the offline OpenAI-compatible mock (port 18080) used by
# app/src/androidTest/.../e2e/LocalMockEndToEndTest (emulator reaches it at 10.0.2.2:18080).
# Runtime files (mock.pid, mock.log, mock-requests.jsonl) stay next to this script and are gitignored.
D="$(cd "$(dirname "$0")" && pwd)"; PIDF="$D/mock.pid"; LOGF="$D/mock.log"
case "$1" in
  stop) [ -f "$PIDF" ] && kill "$(cat "$PIDF")" 2>/dev/null; rm -f "$PIDF";;
  start) "$0" stop; : > "$D/mock-requests.jsonl"; setsid nohup python3 "$D/mock_openai.py" 18080 > "$LOGF" 2>&1 < /dev/null & echo $! > "$PIDF";;
  *) echo "usage: $0 start|stop" >&2; exit 2;;
esac
