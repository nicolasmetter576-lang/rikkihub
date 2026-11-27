#!/usr/bin/env python3
"""Offline OpenAI-compatible mock for local Android smoke tests (no real/paid model).

POST /v1/chat/completions (stream or not):
  * If the conversation's last user text contains "[[script:NAME]]", replies follow
    the scripted turn list SCRIPTS[NAME] (tool calls then text), keyed by how many
    assistant tool rounds already happened in the request.
  * Step args may say "{{provider:NAME}}" / "{{proposal:TEXT}}": the id of a PROVIDER / APPLIED proposal
    found in an earlier creator tool result of the same request (creator_provider_* scripts).
  * "[[mock:auth_echo_401]]" in the last user text: HTTP 401 whose message quotes the Authorization header.
  * "[[probe:creator]]" replies with the creator tool names sent and whether a
    <creator_proposal_updates> note with an APPLIED result was in the request.
  * A 推敲 (tuiqiao) prompt ("需要推敲的原文：") gets N JSON lines rewriting the selection
    (N from "只输出 N 行"; markup kept). Put "[[tuiqiao:broken]]" in the document to get
    candidates with the Markdown markers stripped, "[[tuiqiao:plain]]" for plain lines,
    "[[tuiqiao:slow]]" to hold the regular answer back ~4 s (closing the panel mid-generation).
    A 推敲 role prompt ("给出 1 个结果") gets one line after ~1.5 s: the selection plus
    "（<first 6 chars of the role request>）". A role request containing "[[tuiqiao:role-fail]]"
    gets HTTP 500 instead (one failing role card).
  * A 再造 (zaizao) conversation (first user message has "需要再造的原文：") gets "<result>BASE（TYPE：REQ｜第N轮｜后文M字｜避开K稿）</result>",
    where BASE is the selection in round 1 and the previous <result> afterwards (so the chain shows), N is the
    number of user turns, M the length of the after-text sent; 重写 also gets a <plan> first. In the round's
    requirement: "[[zaizao:slow]]" streams over ~6 s (stop / close mid-way), "[[zaizao:fail]]" returns HTTP 500,
    "[[zaizao:notag]]" answers without tags, "[[zaizao:think]]" adds reasoning, "[[zaizao:empty]]" only a <plan>.
  * A 润色 (prompt polish) prompt ("待润色的提示词：") gets "【已润色】<input>" streamed over ~2 s,
    plus "（参考了 N 条上下文）" when a <conversation> block was sent. In the input,
    "[[polish:slow]]" holds it back ~5 s (cancel mid-way), "[[polish:fail]]" returns HTTP 500,
    "[[polish:empty]]" returns only whitespace.
  * A 去八股 AI-refine request (the user message has "【需要重写的句子】") gets a JSON object {"N": sentence} where each
    flagged phrase of sentence N is replaced by "〔精修〕"; a workspace file's single sentence gets three rewrites
    {"1".."3"} with the phrases replaced by "〔改法N〕". In the reply text sent as context: "[[qubagu:fail]]" returns
    HTTP 500, "[[qubagu:bad]]" answers without JSON, "[[qubagu:slow]]" holds the answer back ~5 s.
  * A compression request (last user message is the structured-summary instruction "Stop the current
    task and write a structured summary ...") gets "[Summary of previous conversation]" plus a short
    section listing how many earlier user/assistant messages were sent. Put "[[compress:slow]]" in the
    additional instructions to hold it back ~4 s, "[[compress:tool]]" to answer with a tool call instead
    of a summary, "[[compress:fail]]" for HTTP 500.
  * "[[overflow]]" in the last user message returns HTTP 400 context_length_exceeded (OpenAI wording) until the
    request carries a compression summary ("[Summary of previous conversation]"), for compress-and-retry.
  * A memory dreaming request (the tools include "conversation_read") writes /topics/tea.md when the new
    turns contain "[[dream:tea]]" (memory_write with if_version "new", or memory_append with the listed
    version when the file exists), then answers "dream done". "[[dream:fail]]" in the new turns returns
    HTTP 500; "[[dream:slow]]" holds every step back ~4 s. A full review (manual "Dream now") answers
    "dream done (full review)". Nothing to record: answers "nothing to remember" without tool calls.
  * Otherwise echoes "mock reply #N: <last user text>".
  * A step with "slow_args": {"chunk": N, "delay": S} streams its tool arguments N characters at a time,
    S seconds apart (script "write_file_stream": a 40-line workspace_write_file for the streaming card).
  * A step with "null_noise" streams relay noise first (`data: null`, null elements in
    reasoning_details / annotations); "with_text" sends that text together with a tool call.
POST /v1/messages (Claude Messages API, stream or not): replies with a redacted_thinking block, a
    thinking block whose signature arrives in two signature_delta pieces, and the text
    "mock claude reply: <N> redacted_thinking replayed" where N counts the redacted_thinking blocks the
    request sent back (they must be replayed verbatim on the next turn).
POST /v1/responses (Responses API, stream only): echoes "mock responses reply #N: <last user text>".
POST /v1/images/generations and /v1/images/edits: one tiny PNG as b64_json.
POST /mcp: a minimal MCP server (Streamable HTTP, JSON responses) with four tools: "echo",
"silent" (returns no content), "slow" (answers after 3 s; script "mcp_slow" shows the tool timer) and
"odd_schema" (root inputSchema type ["object","null"], which
the official Kotlin SDK 0.15.0 could not decode, failing the whole tools/list).
GET /v1/models -> one model "mock-chat".
Every request is appended to mock-requests.jsonl next to this file (for assertions), with the
Authorization and X-Mock request headers.
"""
import json, re, socket, struct, sys, time, threading, os
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

LOG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mock-requests.jsonl")
COUNTER = {"n": 0}
LOCK = threading.Lock()
RESET_DONE = set()

QUBAGU_SKILL_PACK = {"id": "skill_test", "name": "技能测试", "description": "mock 技能写入的包", "rules": [
    {"kind": "replace", "id": "throat", "name": "喉结滚动", "sample": "他喉结滚动。", "find": "喉结(?:上下)?滚动", "regex": True,
     "replacements": ["顿了顿", "沉默片刻"], "refinePrompt": "写出人物此刻具体在做什么，不要用生理反应代替情绪"}]}

SCRIPTS = {
    # workspace_write_file whose arguments arrive slowly (~10 s), to watch the write-file card fill up
    "write_file_stream": [
        {"tool": "workspace_write_file", "slow_args": {"chunk": 24, "delay": 0.2},
         "args": {"path": "/workspace/write/stream-demo.md",
                  "text": "\n".join(f"第 {i} 行：流式写入演示，line {i}" for i in range(1, 41))}},
        {"text": "written"},
    ],
    # two large shell outputs under the same call id ("call_id" fixes the id; like Chat Completions
    # relays that send none and get response:tool-N), then list /tool_outputs: both full outputs must be there
    "tool_output_reuse": [
        {"tool": "workspace_shell", "call_id": "call_reused", "args": {"command": "yes AAAA | head -c 40000"}},
        {"tool": "workspace_shell", "call_id": "call_reused", "args": {"command": "yes BBBB | head -c 40000"}},
        {"tool": "workspace_shell", "args": {"command": "for f in /tool_outputs/call_reused*; do echo \"$f $(grep -o -m1 -E 'AAAA|BBBB' \"$f\" | head -1) $(wc -c < \"$f\")\"; done"}},
        {"text": "listed"},
    ],
    # a book workspace: list, write a new chapter, put it in the contents, read and search (no rootfs needed)
    "book_tools": [
        {"tool": "book_contents", "args": {}},
        {"tool": "workspace_write_file", "args": {"path": "/workspace/第3章 归途.md", "text": "# 归途\n\n阿青推开门，雨停了。\n"}},
        {"tool": "book_update", "args": {"operations": [
            {"op": "add", "path": "/workspace/第3章 归途.md", "synopsis": "阿青回到旧屋"},
            {"op": "set", "chapter": 1, "status": "final"}]}},
        {"tool": "book_read", "args": {"chapter": 2, "to": 3}},
        {"tool": "book_search", "args": {"query": "阿青"}},
        {"tool": "workspace_edit_file", "args": {"path": "/workspace/第3章 归途.md", "old_text": "雨停了", "new_text": "雪停了"}},
        {"tool": "workspace_read_file", "args": {"path": "/workspace/第3章 归途.md"}},
        {"text": "book done"},
    ],
    # a book workspace (2.57.1): the file tools refuse .book/ except .book/branches/
    "book_structure_guard": [
        {"tool": "workspace_write_file", "args": {"path": "/workspace/.book/book.json", "text": "{}"}},
        {"tool": "workspace_edit_file", "args": {"path": "/workspace/./.book/book.json", "old_text": "rikkihub-book", "new_text": "x"}},
        {"tool": "workspace_write_file", "args": {"path": "/workspace/.book/branches/c1/另一稿.md", "text": "另一稿"}},
        {"text": "guard done"},
    ],
    # a book workspace (2.58.0): two writes to one chapter in one turn keep one version (what was there before)
    "book_history": [
        {"tool": "workspace_write_file", "args": {"path": "/workspace/第1章.md", "text": "# 起风\n\n阿青下山。\n\nAI 改的第一稿。\n"}},
        {"tool": "workspace_edit_file", "args": {"path": "/workspace/第1章.md", "old_text": "第一稿", "new_text": "第二稿"}},
        {"text": "history done"},
    ],
    # final locks (2.59.0): a write to a locked chapter and an edit of a locked passage are refused, an edit elsewhere
    # goes through, taking a locked chapter out of the contents is refused
    "book_lock_write": [
        {"tool": "workspace_write_file", "args": {"path": "/workspace/第1章.md", "text": "# 起风\n\nAI 想整章重写。\n"}},
        {"tool": "workspace_edit_file", "args": {"path": "/workspace/第2章.md", "old_text": "眉眼低垂", "new_text": "眉目低垂"}},
        {"tool": "workspace_edit_file", "args": {"path": "/workspace/第2章.md", "old_text": "雨停了。", "new_text": "雨终于停了。"}},
        {"tool": "book_update", "args": {"operations": [{"op": "remove", "chapter": 1}]}},
        {"text": "lock done"},
    ],
    # a command changes a locked passage and the paragraph before it: the passage is put back after the turn
    "book_lock_shell": [
        {"tool": "workspace_shell", "args": {"timeout": 30, "command": "sed -i 's/眉眼低垂/眉目低垂/; s/雨终于停了。/雨早就停了。/' /workspace/第2章.md && echo EDITED"}},
        {"text": "shell lock done"},
    ],
    # per-change review (2.60.0): one reply edits two paragraphs of a chapter and creates a new chapter file
    "book_turn_edit": [
        {"tool": "workspace_edit_file", "args": {"path": "/workspace/第1章.md", "old_text": "山路很长。", "new_text": "山路又长又陡。"}},
        {"tool": "workspace_edit_file", "args": {"path": "/workspace/第1章.md", "old_text": "她没有回头。", "new_text": "她始终没有回头。"}},
        {"tool": "workspace_write_file", "args": {"path": "/workspace/第3章.md", "text": "# 新的一章\n\nAI 新写的一章。\n"}},
        {"text": "turn edit done"},
    ],
    # a later reply edits another chapter; another one only creates a file
    "book_turn_edit2": [
        {"tool": "workspace_edit_file", "args": {"path": "/workspace/第2章.md", "old_text": "屋里一片漆黑。", "new_text": "屋里黑漆漆的。"}},
        {"text": "turn edit2 done"},
    ],
    "book_turn_create": [
        {"tool": "workspace_write_file", "args": {"path": "/workspace/第4章.md", "text": "# 第四章\n\n又一章。\n"}},
        {"text": "turn create done"},
    ],
    # a command edits a chapter and deletes a note file in the same reply
    "book_turn_shell": [
        {"tool": "workspace_shell", "args": {"timeout": 30, "command": "sed -i 's/雨停了。/雨早就停了。/' /workspace/第2章.md && rm /workspace/笔记.md && echo TURN_SHELL"}},
        {"text": "turn shell done"},
    ],
    # plot points of a chapter: one quote from the text, one the text does not have; then a comparison with a plan
    "book_outline": [
        {"text": '{"synopsis":"She waits."}\n{"text":"She waits for the boat","quote":"She waited at the pier"}\n{"text":"A letter comes","quote":"no such words here"}\n'},
    ],
    "book_compare": [
        {"text": '{"beat":1,"state":"written","points":[1]}\n{"beat":2,"state":"changed","points":[2],"note":"A letter, not a boat"}\n{"beat":9,"state":"written","points":[1]}\n'},
    ],
    # beat writing: a chapter split into beats (in its synopsis), one finished beat, one slow enough to stop
    "book_beats": [
        {"text": '{"text":"She waits","words":300}\n{"text":"The letter","words":500}\n{"text":"North","words":200}\n'},
    ],
    "book_beat": [
        {"text": "She stood at the pier.\n\nNobody came. (beat)"},
    ],
    "book_beat_slow": [
        {"text": "Slowly the train went north. " * 20, "chunk_size": 12, "chunk_delay": 0.3},
    ],
    # nicknames of a renamed character (one occurs in the book, one does not)
    "book_aliases": [
        {"text": "{\"alias\":\"Little Lin\",\"to\":\"Little Su\"}\n{\"alias\":\"Nobody\",\"to\":\"X\"}\n"},
    ],
    # a shell call that runs ~20 s (unbind the assistant's workspace while it runs)
    "slow_shell": [
        {"tool": "workspace_shell", "args": {"timeout": 60, "command": "sleep 20; echo SLOW_DONE"}},
        {"text": "shell finished"},
    ],
    # Alpine rootfs (busybox ash, no bash): the persistent bash tool keeps variables and cwd between calls
    "alpine_bash": [
        {"tool": "bash", "args": {"command": "head -2 /etc/os-release; readlink /bin/sh; X=persisted; cd /tmp; echo ALPINE_STEP1"}},
        {"tool": "bash", "args": {"command": "echo \"var=$X cwd=$(pwd)\"; echo 'it'\"'\"'s ok'"}},
        {"text": "probed"},
    ],
    # one reasoning block and one text part (the reasoning can be edited with the message)
    "think_reply": [
        {"reasoning": "original thinking", "text": "answer after thinking"},
    ],
    # images written as workspace paths: absolute, relative to the working directory, in the Linux part
    # and outside a confined working directory (/workspace/other/…)
    "workspace_images": [
        {"text": "![chart](/workspace/chart.png)\n\nrelative:\n\n![rel](chart.png)\n\nspaced:\n\n![sp](</workspace/my pic.png>)\n\nlinux:\n\n![tmp](/tmp/plot.png)\n\nother:\n\n![out](/workspace/other/secret.png)\n\nremote:\n\n![r](https://example.invalid/x.png)"},
    ],
    # a reply streamed over ~8 s (delete or edit earlier messages while it streams)
    "slow_reply": [
        {"text": "streaming " * 30 + "done", "chunk_size": 10, "chunk_delay": 0.25},
    ],
    # a long reply with reasoning streamed over ~45 s (background floating window: bubble, panel preview, quick reply)
    "floating_long": [
        {"reasoning": "thinking about the floating window",
         "text": "\n".join(f"第 {i} 行 line {i} floating preview" for i in range(1, 61)),
         "chunk_size": 12, "chunk_delay": 0.3},
    ],
    # the user asks the assistant to remember something: it writes memory in the main chat
    "remember_short": [
        {"tool": "memory_write", "args": {"path": "/preferences.md",
                                         "content": "- [stated] Prefers short answers\n", "if_version": "new"}},
        {"text": "Noted."},
    ],
    # relay noise around a normal reply: `data: null` lines and null array elements
    "null_lines": [
        {"text": "null-tolerant reply", "null_noise": True},
    ],
    # CRLF line endings around a fenced code block: the paragraph after it must not render as code
    "crlf_code": [
        {"text": "Code:\r\n\r\n```kotlin\r\nval a = 1\r\n```\r\n\r\nafter the code block\r\n"},
    ],
    # highlighted code block, inline code and a table (theme / AMOLED contrast checks)
    "code_table": [
        {"reasoning": "checking the palette",
         "text": "Use `toAmoledStrong()` here:\n\n```kotlin\n// tone the text down on pure black\nfun tone(x: Double): Double {\n    val name = \"amoled\"\n    return x.coerceIn(86.0, 90.0)\n}\n```\n\n| Role | Tone |\n|---|---|\n| onSurface | 86-90 |\n| onSurfaceVariant | 70-78 |\n"},
    ],
    # basic commands in the workspace rootfs (PRoot / distro smoke check)
    "shell_probe": [
        {"tool": "workspace_shell", "args": {"command":
            "head -2 /etc/os-release; ls / | head -3; uname -m; echo hi > /tmp/p.txt && cat /tmp/p.txt; echo PROBE_OK"}},
        {"text": "probed"},
    ],
    # reinstall Ubuntu 26.04's Rust coreutils through dpkg (PRoot link2symlink temp names), then run
    # basic commands: an old PRoot answered them with "Security violation: Requested utility ..."
    "apt_coreutils": [
        {"tool": "workspace_shell", "args": {"timeout": 600, "command":
            "apt-get update -qq >/dev/null 2>&1; apt-get install -y --reinstall rust-coreutils >/tmp/apt.log 2>&1; "
            "echo APT=$?; ls / | head -2; basename /a/b; echo DONE"}},
        {"text": "reinstalled"},
    ],
    # basic commands only (after apt_coreutils)
    "coreutils_check": [
        {"tool": "workspace_shell", "args": {"command": "ls / | head -2; basename /a/b; echo DONE"}},
        {"text": "checked"},
    ],
    # a reply that calls a tool needing no approval together with ask_user (needs an answer):
    # after the answer both run, in order, and the model gets both results
    # reasoning followed by three ask_user calls that all wait for an answer: the collapsed thinking
    # chain must keep every pending call visible, not just the last two steps
    "multi_pending": [
        {"reasoning": "need three answers", "calls": [
            {"tool": "ask_user", "args": {"questions": [{"id": "q1", "question": "First question?"}]}},
            {"tool": "ask_user", "args": {"questions": [{"id": "q2", "question": "Second question?"}]}},
            {"tool": "ask_user", "args": {"questions": [{"id": "q3", "question": "Third question?"}]}},
        ]},
        {"text": "all three answered"},
    ],
    "mixed_approval": [
        {"calls": [
            {"tool": "get_time_info", "args": {}},
            {"tool": "ask_user", "args": {"questions": [{"id": "q1", "question": "Which colour?"}]}},
        ]},
        {"text": "both tools done"},
    ],
    # two steps whose text an assistant regex rewrites ("tick" -> "tick✓"); it must apply once per step
    # several articles via tool steps, then the third request overflows the context until a compression
    # summary is in the request (overflow on a later step of a multi-step turn → compress-and-retry)
    "articles_overflow": [
        {"tool": "get_time_info", "args": {}, "with_text": "ARTICLE-ONE written."},
        {"tool": "get_time_info", "args": {}, "with_text": "ARTICLE-TWO written."},
        {"overflow_unless_summary": True, "text": "ARTICLE-THREE written, all done."},
    ],
    # same two article steps, then a slow third step (~25 s) to kill the app mid-turn
    "articles_slow": [
        {"tool": "get_time_info", "args": {}, "with_text": "ARTICLE-ONE written."},
        {"tool": "get_time_info", "args": {}, "with_text": "ARTICLE-TWO written."},
        {"text": "ARTICLE-THREE written, all done.", "delay": 25.0},
    ],
    # two complete article tool calls in one response, then the connection is reset before the finish
    # chunk (once per conversation text): the app's network retry replays the whole response
    "articles_reset": [
        {"calls": [{"tool": "get_time_info", "args": {}}, {"tool": "get_time_info", "args": {}}],
         "with_text": "Drafting ARTICLE-ONE and ARTICLE-TWO.", "reset_once": 4.0},
        {"text": "ARTICLE-THREE written, all done."},
    ],
    "regex_steps": [
        {"tool": "get_time_info", "args": {}, "with_text": "tick one"},
        {"text": "tick two"},
    ],
    # two steps that both carry stock phrases (去八股 must clean each step's text once, before the next request)
    "qubagu_steps": [
        {"tool": "get_time_info", "args": {}, "with_text": "他喉结滚动，瞳孔骤缩。那不是愤怒，而是执念。"},
        {"text": "她瞳孔骤缩，喉结滚动。夜还很长。"},
    ],
    # <think> at the start of the second step (the first step already has a reasoning part)
    "think_steps": [
        {"tool": "get_time_info", "args": {}, "reasoning": "first thoughts"},
        {"text": "<think>second thoughts</think>final answer"},
    ],
    # an MCP tool that returns no content: runs once, then the model answers
    # (the app exposes MCP tools as mcp__<server>__<tool>; name the POST /mcp server "mockmcp")
    "mcp_silent": [
        {"tool": "mcp__mockmcp__silent", "args": {}},
        {"text": "silent tool finished"},
    ],
    # an MCP tool that takes about 3 s: the tool step shows a running timer, then the final time
    "mcp_slow": [
        {"tool": "mcp__mockmcp__slow", "args": {}},
        {"text": "slow tool finished"},
    ],
    # an MCP tool with a non-standard root schema type (["object","null"]) is listed and callable
    "mcp_odd": [
        {"tool": "mcp__mockmcp__odd_schema", "args": {"q": "hello"}},
        {"text": "odd tool finished"},
    ],
    # one search citation badge
    # per-turn title: the reply ends with <rk-title>, streamed 3 chars at a time so half-written tags show up mid-stream
    "title_reply": [
        {"text": "这是带标题的回复。\n第二行正文。\n<rk-title>天气查询</rk-title>", "chunk_size": 3, "chunk_delay": 0.2},
    ],
    # the same reply 2 chars every 0.8 s: slow enough to screenshot each half-written tag state
    "title_reply_slow": [
        {"text": "这是带标题的回复。\n第二行正文。\n<rk-title>天气查询</rk-title>", "chunk_size": 2, "chunk_delay": 0.8},
    ],
    "citation": [
        {"text": "See the source [citation,example.com](abc123) for details."},
    ],
    # text sent together with a tool call, then the final text (the two texts stay separate parts)
    "tool_with_text": [
        {"tool": "creator_describe", "args": {"kind": "QUICK_MESSAGE"}, "with_text": "before the tool."},
        {"text": "after the tool."},
    ],
    # mermaid: one fenced mermaid block, for the diagram render/export check
    "mermaid": [
        {"text": "Diagram:\n\n```mermaid\ngraph TD\n    A[Start] --> B{Check}\n    B -->|yes| C[Export]\n    B -->|no| D[Retry]\n```\n"},
    ],
    # creator: list quick messages, then propose creating one, then finish
    "creator_create_qm": [
        {"tool": "creator_list_objects", "args": {"kind": "QUICK_MESSAGE"}},
        {"tool": "creator_preview_change", "args": {"operations": [
            {"kind": "QUICK_MESSAGE", "command": "Create",
             "params": {"fields": {"title": "Mock greeting", "content": "Hello from the creator smoke test"}}}]}},
        {"text": "I proposed creating the quick message. Please review and approve it."},
    ],
    # creator: look up a format, then propose two Settings changes in one call (one combined proposal)
    "creator_batch": [
        {"tool": "creator_describe", "args": {"kind": "MODE_INJECTION"}},
        {"tool": "creator_preview_change", "args": {"operations": [
            {"kind": "QUICK_MESSAGE", "command": "Create",
             "params": {"fields": {"title": "Mock batch greeting", "content": "Hello from the batch test"}}},
            {"kind": "MODE_INJECTION", "command": "Create",
             "params": {"fields": {"name": "Mock batch mode", "content": "Be brief.",
                                   "libraryAnchor": "AFTER_SYSTEM", "role": "USER"}}}]}},
        {"text": "I proposed both changes. Please review and approve them."},
    ],
    # creator: a lorebook with bulk entries, a global regex script and tavern settings in one proposal
    "creator_native_book": [
        {"tool": "creator_describe", "args": {"kind": "LOREBOOK_ENTRY"}},
        {"tool": "creator_preview_change", "args": {"operations": [
            {"kind": "LOREBOOK", "command": "Create", "params": {"fields": {"name": "Mock Aurora", "description": "native smoke"}}},
            {"kind": "LOREBOOK_ENTRY", "command": "Create", "params": {"bookId": "$0", "items": [
                {"comment": "Capital", "key": ["capital", "Aurum"], "content": "Aurum floats."},
                {"comment": "Dragons", "key": ["dragon"], "keysecondary": ["fire"], "selective": True, "content": "Dragons breathe fire.", "probability": 60},
                {"comment": "Weather", "key": ["rain"], "content": "It rains on Tuesdays.", "constant": False}]}},
            {"kind": "REGEX_SCRIPT", "command": "Create", "params": {"fields": {
                "scriptName": "Mock cat to dog", "findRegex": "/cat/g", "replaceString": "dog", "placement": [2], "markdownOnly": True}}},
            {"kind": "TAVERN_SETTINGS", "command": "Patch", "targetId": "tavern", "params": {"fields": {
                "worldInfo": {"world_info_depth": 5}, "authorsNote": {"prompt": "Keep it light.", "depth": 2}}}}]}},
        {"text": "I proposed the lorebook, its entries, the regex script and the settings. Please review and approve."},
    ],
    # creator: search and read what the previous script created
    "creator_native_read": [
        {"tool": "creator_list_objects", "args": {"kind": "LOREBOOK_ENTRY", "filter": "dragon"}},
        {"tool": "creator_list_objects", "args": {"kind": "REGEX_SCRIPT", "filter": "global"}},
        {"tool": "creator_read_object", "args": {"kind": "TAVERN_SETTINGS", "id": "tavern"}},
        {"text": "Read the entries, the regex script and the tavern settings."},
    ],
    # creator: a character with its card fields, then (next script) read and patch it
    "creator_character": [
        {"tool": "creator_describe", "args": {"kind": "CHARACTER"}},
        {"tool": "creator_preview_change", "args": {"operations": [
            {"kind": "CHARACTER", "command": "Create", "params": {"fields": {
                "name": "Mock Pilot", "description": "{{char}} flies the mail boat between the islands.",
                "personality": "calm, dry", "scenario": "{{char}} is waiting for {{user}} at the pier.",
                "first_mes": "Climb aboard, {{user}}.", "tags": ["mock", "boat"], "alternate_greetings": ["Mind the rope."],
                "depth_prompt": {"prompt": "[{{char}} checks the horizon.]", "depth": 2, "role": "system"}}}}]}},
        {"text": "I proposed the character. Please review and approve it."},
    ],
    "creator_character_patch": [
        {"tool": "creator_list_objects", "args": {"kind": "CHARACTER", "filter": "pilot"}},
        {"tool": "creator_read_object", "args": {"kind": "CHARACTER", "id": "{{character:Mock Pilot}}"}},
        {"tool": "creator_preview_change", "args": {"operations": [
            {"kind": "CHARACTER", "command": "Patch", "targetId": "{{character:Mock Pilot}}", "params": {"fields": {
                "personality": "calm, dry, a little superstitious", "fav": True}}}]}},
        {"text": "I proposed the change. Please review and approve it."},
    ],
    # creator: create a provider pointing back at this mock, plus its model, in one proposal
    "creator_provider": [
        {"tool": "creator_describe", "args": {"kind": "PROVIDER"}},
        {"tool": "creator_preview_change", "args": {"operations": [
            {"kind": "PROVIDER", "command": "Create", "params": {"fields": {
                "type": "openai", "name": "Mock relay", "baseUrl": "http://10.0.2.2:18080/v1",
                "apiKey": "sk-mock-relay-key-123456", "customHeaders": [{"name": "X-Mock", "value": "hdr-secret-1"}]}}},
            {"kind": "MODEL_DEFINITION", "command": "Create", "params": {"providerId": "$0", "fields": {
                "modelId": "mock-chat", "displayName": "Mock relay chat", "abilities": ["TOOL"]}}}]}},
        {"text": "I proposed the Mock relay provider with one model. Please approve it."},
    ],
    # creator (after approval): find the provider, send a test message, read it back
    "creator_provider_test": [
        {"tool": "creator_list_objects", "args": {"kind": "PROVIDER", "filter": "Mock relay"}},
        {"tool": "creator_test_provider", "args": {"providerId": "{{provider:Mock relay}}", "message": "ping from creator", "listModels": True}},
        {"tool": "creator_read_object", "args": {"kind": "PROVIDER", "id": "{{provider:Mock relay}}"}},
        {"text": "Tested the Mock relay provider."},
    ],
    # creator: replace the key and rename (header sent without value keeps its stored value)
    "creator_provider_patch": [
        {"tool": "creator_list_objects", "args": {"kind": "PROVIDER", "filter": "Mock relay"}},
        {"tool": "creator_preview_change", "args": {"operations": [
            {"kind": "PROVIDER", "command": "Patch", "targetId": "{{provider:Mock relay}}", "params": {"fields": {
                "name": "Mock relay 2", "apiKey": "sk-mock-relay-key-NEW-789", "customHeaders": [{"name": "X-Mock"}]}}}]}},
        {"text": "I proposed the new key. Please approve it."},
    ],
    # creator: undo the applied key change (a new proposal the user approves)
    "creator_provider_undo": [
        {"tool": "creator_get_change_status", "args": {}},
        {"tool": "creator_propose_undo", "args": {"proposalId": "{{proposal:Mock relay 2}}"}},
        {"text": "I proposed undoing the key change."},
    ],
    # creator: a test the server rejects (its error quotes the key, which must come back masked), then a delete
    "creator_provider_fail": [
        {"tool": "creator_list_objects", "args": {"kind": "PROVIDER", "filter": "Mock relay"}},
        {"tool": "creator_test_provider", "args": {"providerId": "{{provider:Mock relay}}", "model": "mock-chat",
                                                   "message": "[[mock:auth_echo_401]]"}},
        {"tool": "creator_preview_change", "args": {"operations": [
            {"kind": "PROVIDER", "command": "Delete", "targetId": "{{provider:Mock relay}}"}]}},
        {"text": "Tried to delete it."},
    ],
    # creator: attempt an out-of-namespace tool to verify rejection
    "creator_forged_tool": [
        {"tool": "workspace_bash", "args": {"command": "id"}},
        {"text": "done"},
    ],
    # concatenated tool arguments ({A}{B}): the app splits them into two independent calls
    "concat_args": [
        {"tool": "creator_describe", "raw_args": '{"kind": "QUICK_MESSAGE"}{"kind": "MODE_INJECTION"}'},
        {"text": "described both kinds"},
    ],
    # second call streamed on the same index without an id (name re-sent): arguments get
    # concatenated by the stream ledger, then split back by the app
    "concat_same_index": [
        {"tool": "creator_describe", "same_index": [{"kind": "QUICK_MESSAGE"}, {"kind": "MODE_INJECTION"}]},
        {"text": "described both kinds"},
    ],
    # unsplittable invalid arguments: the app returns an invalid_arguments error to the model
    "concat_invalid": [
        {"tool": "creator_describe", "raw_args": '{"kind": "QUICK_MESSAGE"}{"kind": '},
        {"text": "saw the invalid_arguments error"},
    ],
    # several read-only calls in one reply (each its own index/id): the app runs them in parallel
    "parallel_describe": [
        {"calls": [
            {"tool": "creator_describe", "args": {"kind": "QUICK_MESSAGE"}},
            {"tool": "creator_describe", "args": {"kind": "MODE_INJECTION"}},
            {"tool": "creator_describe", "args": {"kind": "LOREBOOK"}},
        ]},
        {"text": "described three kinds"},
    ],
    # two subagents in one reply; each subagent's own request is answered after 2 s, so the
    # request timestamps in mock-requests.jsonl overlap only if the app runs them concurrently
    "parallel_subagents": [
        {"calls": [
            {"tool": "subagent", "args": {"prompt": "Report A [[script:slow_report]]", "description": "A"}},
            {"tool": "subagent", "args": {"prompt": "Report B [[script:slow_report]]", "description": "B"}},
        ]},
        {"text": "both subagents reported"},
    ],
    "slow_report": [
        {"text": "report done", "delay": 2.0},
    ],
    # formula edge cases (upstream 3.1.1 eb530b0f): \\[4pt] line-break spacing, and "<" in a formula next to HTML
    "latex_linebreak": [
        {"text": "Spacing: $$a=1\\\\[4pt]b=2$$ and then \\[c=3\\]"},
    ],
    "latex_spacing_only": [
        {"text": "Only spacing: $$a=1\\\\[4pt]b=2$$ done."},
    ],
    # HTML inside a reply, for the TTS check (tags must not be read aloud)
    "html_reply": [
        {"text": "Hello <b>bold</b> world<br/>second line <span style='color:red'>red</span> end."},
    ],
    "html_entity_reply": [
        {"text": "Use the &lt;div&gt; element, then a &amp; b."},
    ],
    "latex_lt_plain": [
        {"text": "No html: $0<y<1$ and $$a<b$$ end."},
    ],
    "latex_lt_html": [
        {"text": "Plain: $0<x<1$ ok.\n\nWith html <b>bold</b> and $0<y<1$ and $$a<b$$ end."},
    ],
    # a remote image (GET /tiny.png) for the image-preview save check
    "remote_image": [
        {"text": "Look: ![tiny](http://10.0.2.2:18080/tiny.png)"},
    ],
    # one chart_display call, then a closing text
    "chart_line": [
        {"tool": "chart_display", "args": {"style": "line", "title": "E2E chart",
            "x_axis": {"title": "step", "data": ["a", "b", "c"]}, "y_axis": {"format": ".1f"},
            "series": [{"name": "s1", "values": [1, 2.5, 3]}]}},
        {"text": "chart shown"},
    ],
    # the built-in qubagu-rules skill: load it, read the rules, test a candidate pack, then write it (needs approval)
    "qubagu_skill": [
        {"tool": "use_skill", "args": {"name": "qubagu-rules"}},
        {"tool": "qubagu_rules_read", "args": {}},
        {"tool": "qubagu_rules_test", "args": {"text": "他喉结滚动，没有说话。她的手在抖。", "packs": [QUBAGU_SKILL_PACK]}},
        {"tool": "qubagu_rules_write", "args": {"packs": [QUBAGU_SKILL_PACK]}},
        {"text": "已写入「技能测试」包。"},
    ],
    # the built-in usage guide: load SKILL.md, then a reference file and a settings map by path
    "guide_skill": [
        {"tool": "use_skill", "args": {"name": "rikkihub-guide"}},
        {"tool": "use_skill", "args": {"name": "rikkihub-guide", "path": "guide/troubleshooting.md"}},
        {"tool": "use_skill", "args": {"name": "rikkihub-guide", "path": "settings-map/en.md"}},
        {"text": "按指南查过了。"},
    ],
    # a write the engine rejects (broken regex): the error comes back, nothing is saved
    "qubagu_skill_bad": [
        {"tool": "qubagu_rules_write", "args": {"packs": [{"id": "broken", "name": "坏包", "rules": [
            {"kind": "replace", "id": "bad", "find": "(", "regex": True}]}]}},
        {"text": "写入被拒绝。"},
    ],
    # a conversation confined to /workspace/a/b probes for files outside it
    "scoped_probe": [
        {"tool": "workspace_shell", "args": {"command":
            "pwd; echo ROOT:; ls -A /workspace; cat /workspace/other/secret.txt; "
            "find / \\( -path /proc -o -path /sys -o -path /dev \\) -prune -o -name 'secret*' -print 2>/dev/null; "
            "echo HERE:; ls -A"}},
        {"tool": "workspace_read_file", "args": {"path": "/workspace/other/secret.txt"}},
        {"tool": "workspace_write_file", "args": {"path": "/workspace/other/new.txt", "text": "x"}},
        {"tool": "workspace_write_file", "args": {"path": "/workspace/a/b/made.txt", "text": "made"}},
        {"text": "probed"},
    ],
}


def tool_rounds(messages):
    # Completed tool rounds since the last user turn = tool results seen. Counting
    # assistant messages is wrong when a client merges several calls into one message.
    count = 0
    for m in reversed(messages):
        if m.get("role") == "user":
            break
        if m.get("role") == "tool":
            count += 1
    return count


def tool_arguments(step, args=None):
    # "raw_args" sends the arguments string verbatim (e.g. malformed/concatenated JSON);
    # "same_index" streams each entry as its own chunk on index 0 (see SCRIPTS).
    if args is not None:
        return json.dumps(args)
    if "raw_args" in step:
        return step["raw_args"]
    if "same_index" in step:
        return "".join(json.dumps(a) for a in step["same_index"])
    return json.dumps(step["args"])


def tuiqiao_reply(text):
    """Scripted 推敲 answer: one JSON object per line, streamed slowly enough to watch."""
    marker = "需要推敲的原文：\n<selection>"
    at = text.rfind(marker)
    if at < 0:
        return None
    start = at + len(marker)
    selection = text[start:text.find("</selection>", start)]
    import re
    if "给出 1 个结果" in text:
        task = text[text.find("任务：\n") + 4:].split("\n", 1)[0]
        if "[[tuiqiao:role-fail]]" in task:
            return {"http_error": 500}
        obj = {"text": f"{selection}（{task[:6]}）"}
        if "text、why" in text:
            obj["why"] = "角色要求的写法。"
        return {"text": json.dumps(obj, ensure_ascii=False), "chunk_delay": 0.05, "delay": 1.5}
    m = re.search(r"只输出 (\d+) 行", text)
    count = int(m.group(1)) if m else 3
    why = "text、tone、why" in text
    tones = ["书面", "口语", "冷峻", "舒缓", "利落"]
    lines = []
    for i in range(count):
        cand = f"{selection}（候选{i + 1}）"
        if "[[tuiqiao:broken]]" in text:
            cand = cand.replace("**", "").replace("~~", "")
        if "[[tuiqiao:plain]]" in text:
            lines.append(f"{i + 1}. {cand}")
            continue
        obj = {"text": cand, "tone": tones[i % len(tones)]}
        if why:
            obj["why"] = f"第{i + 1}种写法的理由。"
        lines.append(json.dumps(obj, ensure_ascii=False))
    reply = {"text": "\n".join(lines), "chunk_delay": 0.05}
    if "[[tuiqiao:slow]]" in text:
        reply["delay"] = 4.0
    return reply


def zaizao_reply(messages):
    """Scripted 再造 answer that echoes what the app sent: round count, type, requirement, after-text, drafts to avoid."""
    def content(m):
        c = m.get("content")
        return c if isinstance(c, str) else " ".join(p.get("text", "") for p in (c or []) if isinstance(p, dict))
    users = [content(m) for m in messages if m.get("role") == "user"]
    if not users or "需要再造的原文：\n<selection>" not in users[0]:
        return None
    import re
    first, last = users[0], users[-1]
    marker = "需要再造的原文：\n<selection>"
    start = first.rfind(marker) + len(marker)
    selection = first[start:first.find("</selection>", start)]
    doc_end = first.find("\n</document>")
    after = first[first.find("</selection>") + len("</selection>"):doc_end]
    type_match = re.search(r"本轮类型：(\S+)", last)
    kind = type_match.group(1) if type_match else "?"
    req = last[last.find("<requirement>\n") + len("<requirement>\n"):last.find("\n</requirement>")]
    base = selection
    for m in reversed(messages):
        if m.get("role") == "assistant":
            out = content(m)
            if "<result>" in out:
                base = out[out.find("<result>") + 8:out.find("</result>") if "</result>" in out else len(out)]
            break
    if "[[zaizao:fail]]" in req:
        return {"http_error": 500}
    note = f"（{kind}：{req.replace('[[zaizao:slow]]', '').replace('[[zaizao:think]]', '').replace('[[zaizao:notag]]', '')}" \
        f"｜第{len(users)}轮｜后文{len(after)}字｜避开{last.count('<draft>')}稿）"
    result = base + note
    if "[[zaizao:notag]]" in req:
        text = result
    elif "[[zaizao:empty]]" in req:
        text = "<plan>只写了构思</plan>"
    elif kind == "重写":
        text = f"<plan>事件：{selection[:12]}…\n已用写法：原文的写法\n本稿写法：换一种呈现</plan>\n<result>{result}</result>"
    else:
        text = f"<result>{result}</result>"
    reply = {"text": text, "chunk_size": 4, "chunk_delay": 0.05, "delay": 0.3}
    if "[[zaizao:slow]]" in req:
        reply["chunk_delay"] = 0.4
        reply["chunk_size"] = 2
    if "[[zaizao:think]]" in req:
        reply["reasoning"] = "先想一想：要求是什么，怎么融进原文。"
    return reply


def resolve_refs(step, messages):
    """In step args: "{{provider:NAME}}" -> id of the first PROVIDER whose title starts with NAME in an earlier
    creator_list_objects result; "{{proposal:TEXT}}" -> id of the first APPLIED proposal whose summary contains
    TEXT in an earlier creator_get_change_status list."""
    def results():
        for m in messages:
            if m.get("role") != "tool":
                continue
            try:
                yield json.loads(m.get("content") or "")
            except (TypeError, ValueError):
                continue
    def provider_id(name):
        for result in results():
            for item in result.get("items", []) if isinstance(result, dict) else []:
                if item.get("kind") == "PROVIDER" and item.get("title", "").startswith(name):
                    return item["id"]
        return "missing-" + name
    def character_id(name):
        for result in results():
            for item in result.get("items", []) if isinstance(result, dict) else []:
                if item.get("kind") == "CHARACTER" and item.get("title", "").startswith(name):
                    return item["id"]
        return "missing-" + name
    def proposal_id(text):
        for result in results():
            for item in result if isinstance(result, list) else []:
                if item.get("status") == "APPLIED" and text in item.get("summary", ""):
                    return item["proposalId"]
        return "missing-" + text
    def walk(v):
        if isinstance(v, dict):
            return {k: walk(x) for k, x in v.items()}
        if isinstance(v, list):
            return [walk(x) for x in v]
        if isinstance(v, str) and v.startswith("{{provider:") and v.endswith("}}"):
            return provider_id(v[len("{{provider:"):-2])
        if isinstance(v, str) and v.startswith("{{character:") and v.endswith("}}"):
            return character_id(v[len("{{character:"):-2])
        if isinstance(v, str) and v.startswith("{{proposal:") and v.endswith("}}"):
            return proposal_id(v[len("{{proposal:"):-2])
        return v
    return walk(step) if "args" in step else step


COMPRESS_INSTRUCTION = "Stop the current task and write a structured summary"
STAGE_COMPRESS_INSTRUCTION = "Write an updated running summary for staged context compression."
FINAL_CHUNK_COMPRESS_INSTRUCTION = "Write the final structured summary of the conversation"


def _message_text(m):
    c = m.get("content")
    if isinstance(c, str):
        return c
    if isinstance(c, list):
        return "\n".join(p.get("text", "") for p in c if isinstance(p, dict))
    return ""


def _segment_item_count(prompt_text):
    start = prompt_text.find("<conversation_segment>")
    if start < 0:
        return 0
    end = prompt_text.find("</conversation_segment>", start)
    seg = prompt_text[start:end] if end >= 0 else prompt_text[start:]
    return seg.count("[Message #") + seg.count("[Fragment ")


def compress_reply(messages, text):
    """Context compression: single-pass prefix-preserving or standalone staged/final requests."""
    stripped = text.lstrip()
    is_single = stripped.startswith(COMPRESS_INSTRUCTION)
    is_stage = stripped.startswith(STAGE_COMPRESS_INSTRUCTION)
    is_final_chunk = stripped.startswith(FINAL_CHUNK_COMPRESS_INSTRUCTION)
    if not (is_single or is_stage or is_final_chunk):
        return None
    if "[[compress:fail]]" in text:
        return {"http_error": 500}
    if "[[compress:tool]]" in text:
        return {"tool": "creator_describe", "args": {"kind": "QUICK_MESSAGE"}, "with_text": "Let me look first."}
    all_text = "\n".join(_message_text(m) for m in messages)
    if "[[compress:chunk_overflow]]" in all_text:
        overflow_count = (
            len([m for m in messages[:-1] if m.get("role") in ("user", "assistant")])
            if is_single else _segment_item_count(text)
        )
        if overflow_count > 2:
            return {
                "http_error": 400,
                "error_body": {
                    "error": {
                        "message": "This model's maximum context length is 500 tokens. Please reduce the length of the messages.",
                        "type": "invalid_request_error",
                        "code": "context_length_exceeded",
                    }
                },
            }
    if is_stage:
        seg_items = _segment_item_count(text)
        has_prev = "yes" if "\n<previous_summary>\n" in text else "no"
        step = {"text": f"Stage summary ({seg_items} msgs, prev={has_prev})"}
    elif is_final_chunk:
        seg_items = _segment_item_count(text)
        has_prev = "yes" if "\n<previous_summary>\n" in text else "no"
        step = {"text": f"[Summary of previous conversation]\n\n## Current State\nchunked summary ready ({seg_items} msgs, prev={has_prev})"}
    else:
        earlier = [m for m in messages[:-1] if m.get("role") in ("user", "assistant")]
        users = sum(1 for m in earlier if m.get("role") == "user")
        step = {"text": "[Summary of previous conversation]\n\n## Current State\n"
                        f"summary ready ({users} user / {len(earlier) - users} assistant messages)"}
    if "[[compress:timeout]]" in text:
        step["delay"] = 35.0
    elif "[[compress:slow]]" in text:
        step["delay"] = 4.0
    return step


TEA_FILE = "---\nname: tea\ndescription: Tea the user drinks\n---\n\n- [stated] Favorite tea is oolong\n"


def dream_reply(req, messages, text):
    """Background memory consolidation ("dreaming"): only that request carries conversation_read."""
    names = {t.get("function", {}).get("name") for t in req.get("tools", [])}
    if "conversation_read" not in names:
        return None
    system = " ".join(m.get("content") or "" for m in messages if m.get("role") == "system" and isinstance(m.get("content"), str))
    first_user = next((m.get("content") for m in messages if m.get("role") == "user"), "") or ""
    if isinstance(first_user, list):
        first_user = " ".join(p.get("text", "") for p in first_user if isinstance(p, dict))
    new_turns = first_user[first_user.find("<new_turns>"):] if "<new_turns>" in first_user else ""
    if "[[dream:fail]]" in new_turns:
        return {"http_error": 500}
    delay = 4.0 if "[[dream:slow]]" in new_turns else 0
    full = "## Full review" in system
    rounds = tool_rounds(messages)
    if "[[dream:tea]]" in new_turns and rounds == 0:
        import re
        m = re.search(r"^/topics/tea\.md \|", first_user, re.M)
        if m:
            # the listing has no version; read it first
            return {"tool": "memory_read", "args": {"path": ["/topics/tea.md"]}, "delay": delay}
        return {"tool": "memory_write", "args": {"path": "/topics/tea.md", "content": TEA_FILE, "if_version": "new"}, "delay": delay}
    if "[[dream:tea]]" in new_turns and rounds == 1:
        last = messages[-1]
        content = last.get("content") or ""
        if isinstance(content, str) and '"files"' in content:
            version = json.loads(content)["files"][0]["version"]
            return {"tool": "memory_append", "args": {"path": "/topics/tea.md", "content": "- [stated] Also likes green tea",
                                                      "if_version": version}, "delay": delay}
    if rounds == 0 and "[[dream:tea]]" not in new_turns:
        return {"text": "dream done (full review)" if full else "nothing to remember", "delay": delay}
    return {"text": "dream done (full review)" if full else "dream done", "delay": delay}


def polish_reply(text):
    """Scripted 润色 answer for the built-in prompt (the input follows the marker to the end)."""
    marker = "待润色的提示词：\n"
    at = text.rfind(marker)
    if at < 0:
        return None
    source = text[at + len(marker):]
    if "[[polish:fail]]" in source:
        return {"http_error": 500}
    if "[[polish:empty]]" in source:
        return {"text": "   "}
    result = f"【已润色】{source}"
    if "<conversation>" in text:
        block = text[text.find("<conversation>"):text.find("</conversation>")]
        count = block.count("[USER]: ") + block.count("[ASSISTANT]: ")
        result += f"（参考了 {count} 条上下文）"
    reply = {"text": result, "chunk_delay": 0.15, "chunk_size": 3, "delay": 0.5}
    if "[[polish:slow]]" in source:
        reply["delay"] = 5.0
    return reply


def qubagu_reply(text):
    """Scripted 去八股 AI-refine answer: a JSON object numbering each listed sentence with its flagged phrases swapped out."""
    marker = "【需要重写的句子】"
    at = text.rfind(marker)
    if at < 0:
        return None
    if "[[qubagu:fail]]" in text:
        return {"http_error": 500}
    if "[[qubagu:bad]]" in text:
        return {"text": "抱歉，我没法重写。"}
    single = re.match(r"【需要重写的句子】\n([\s\S]*?)(?:\n套话：(.*))?$", text[at:])
    if single and "原句：" not in text[at:]:
        # a workspace file's sentence: three rewrites, the flagged phrases swapped for 〔改法N〕
        sentence, phrases = single.group(1), re.findall(r"「(.*?)」", single.group(2) or "")
        options = {}
        for number in ("1", "2", "3"):
            option = sentence
            for phrase in phrases:
                option = option.replace(phrase, f"〔改法{number}〕")
            options[number] = option.replace("\n", "")
        return {"text": json.dumps(options, ensure_ascii=False), "delay": 5.0 if "[[qubagu:slow]]" in text else 0.3}
    rewrites = {}
    for number, sentence, phrases in re.findall(r"\n(\d+)\. 原句：(.*)\n   套话：(.*)", text[at:]):
        for phrase in re.findall(r"「(.*?)」", phrases):
            sentence = sentence.replace(phrase, "〔精修〕")
        rewrites[number] = sentence
    return {"text": json.dumps(rewrites, ensure_ascii=False), "delay": 5.0 if "[[qubagu:slow]]" in text else 0.3}


# 16x16 opaque red PNG
TINY_PNG = "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAFklEQVR4nGO4Y2NDEmIY1TCqYfhqAABhl1QQ50OvrwAAAABJRU5ErkJggg=="


def responses_user_text(items):
    for item in reversed(items or []):
        if isinstance(item, dict) and item.get("role") == "user":
            c = item.get("content")
            if isinstance(c, list):
                return " ".join(p.get("text", "") for p in c if isinstance(p, dict))
            return c or ""
    return ""


def mock_usage(messages):
    """Usage that grows with the request: 100 prompt tokens per message (half of them "cached"),
    5 completion tokens. Lets multi-step replies show summed vs. last-request usage."""
    prompt = 100 * len(messages)
    return {"prompt_tokens": prompt, "completion_tokens": 5, "total_tokens": prompt + 5,
            "prompt_tokens_details": {"cached_tokens": prompt // 2}}


def last_user_text(messages):
    for m in reversed(messages):
        if m.get("role") == "user":
            c = m.get("content")
            if isinstance(c, list):
                return " ".join(p.get("text", "") for p in c if isinstance(p, dict))
            return c or ""
    return ""


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _claude(self, n, req):
        replayed = sum(1 for m in req.get("messages", []) if m.get("role") == "assistant"
                       for b in (m.get("content") if isinstance(m.get("content"), list) else [])
                       if b.get("type") == "redacted_thinking")
        redacted = f"opaque-redacted-{n}"
        text = f"mock claude reply: {replayed} redacted_thinking replayed"
        usage = {"input_tokens": 10, "output_tokens": 5}
        if not req.get("stream"):
            return self._json(200, {"id": f"msg_{n}", "type": "message", "role": "assistant", "model": req.get("model"),
                                    "content": [{"type": "redacted_thinking", "data": redacted},
                                                {"type": "thinking", "thinking": "visible thought", "signature": f"sig-{n}"},
                                                {"type": "text", "text": text}],
                                    "stop_reason": "end_turn", "usage": usage})
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        def ev(obj):
            self.wfile.write(b"event: " + obj["type"].encode() + b"\ndata: " + json.dumps(obj).encode() + b"\n\n"); self.wfile.flush()
        ev({"type": "message_start", "message": {"id": f"msg_{n}", "type": "message", "role": "assistant",
                                                 "model": req.get("model"), "content": [], "usage": usage}})
        ev({"type": "content_block_start", "index": 0, "content_block": {"type": "redacted_thinking", "data": redacted}})
        ev({"type": "content_block_stop", "index": 0})
        ev({"type": "content_block_start", "index": 1, "content_block": {"type": "thinking", "thinking": ""}})
        ev({"type": "content_block_delta", "index": 1, "delta": {"type": "thinking_delta", "thinking": "visible thought"}})
        ev({"type": "content_block_delta", "index": 1, "delta": {"type": "signature_delta", "signature": f"sig-{n}-a"}})
        ev({"type": "content_block_delta", "index": 1, "delta": {"type": "signature_delta", "signature": "-b"}})
        ev({"type": "content_block_stop", "index": 1})
        ev({"type": "content_block_start", "index": 2, "content_block": {"type": "text", "text": ""}})
        ev({"type": "content_block_delta", "index": 2, "delta": {"type": "text_delta", "text": text}})
        ev({"type": "content_block_stop", "index": 2})
        ev({"type": "message_delta", "delta": {"stop_reason": "end_turn"}, "usage": {"output_tokens": 5}})
        ev({"type": "message_stop"})
        self.close_connection = True

    def _responses(self, n, req):
        """Minimal Responses API stream: one message item with streamed output_text."""
        text = f"mock responses reply #{n}: {responses_user_text(req.get('input'))[:200]}"
        if "[[responses:fco]]" in responses_user_text(req.get("input")) and not any(
                isinstance(i, dict) and i.get("type") == "function_call_output" for i in (req.get("input") or [])):
            return self._responses_fco(n)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        def ev(obj):
            self.wfile.write(b"event: " + obj["type"].encode() + b"\ndata: " + json.dumps(obj).encode() + b"\n\n"); self.wfile.flush()
        rid, mid = f"resp_{n}", f"msg_{n}"
        ev({"type": "response.created", "response": {"id": rid, "status": "in_progress", "model": "mock-chat"}})
        ev({"type": "response.output_item.added", "output_index": 0, "item": {"type": "message", "id": mid, "role": "assistant"}})
        ev({"type": "response.content_part.added", "item_id": mid, "output_index": 0, "content_index": 0, "part": {"type": "output_text", "text": ""}})
        for i in range(0, len(text), 16):
            ev({"type": "response.output_text.delta", "item_id": mid, "output_index": 0, "content_index": 0, "delta": text[i:i + 16]})
        ev({"type": "response.output_text.done", "item_id": mid, "output_index": 0, "content_index": 0, "text": text})
        ev({"type": "response.output_item.done", "output_index": 0, "item": {"type": "message", "id": mid, "role": "assistant",
            "content": [{"type": "output_text", "text": text}]}})
        ev({"type": "response.completed", "response": {"id": rid, "status": "completed", "model": "mock-chat",
            "usage": {"input_tokens": 10, "output_tokens": 5, "total_tokens": 15}}})
        self.close_connection = True

    def _responses_fco(self, n):
        """A gateway that executes the function tool itself: function_call + function_call_output in one response."""
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        def ev(obj):
            self.wfile.write(b"event: " + obj["type"].encode() + b"\ndata: " + json.dumps(obj).encode() + b"\n\n"); self.wfile.flush()
        rid = f"resp_{n}"
        call = {"type": "function_call", "id": "fc_1", "call_id": "call_srv_1", "name": "get_time_info", "arguments": "{}"}
        out = {"type": "function_call_output", "id": "fco_1", "call_id": "call_srv_1", "output": "SERVER-EXECUTED-RESULT"}
        ev({"type": "response.created", "response": {"id": rid, "status": "in_progress", "model": "mock-chat"}})
        ev({"type": "response.output_item.added", "output_index": 0, "item": {**call, "arguments": ""}})
        ev({"type": "response.function_call_arguments.done", "item_id": "fc_1", "output_index": 0, "arguments": "{}"})
        ev({"type": "response.output_item.done", "output_index": 0, "item": call})
        ev({"type": "response.output_item.added", "output_index": 1, "item": out})
        ev({"type": "response.output_item.done", "output_index": 1, "item": out})
        ev({"type": "response.completed", "response": {"id": rid, "status": "completed", "model": "mock-chat",
            "usage": {"input_tokens": 10, "output_tokens": 5, "total_tokens": 15}}})
        self.close_connection = True

    def _mcp(self, req):
        method = req.get("method")
        rid = req.get("id")
        if rid is None:  # notification
            self.send_response(202)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if method == "initialize":
            result = {"protocolVersion": (req.get("params") or {}).get("protocolVersion", "2025-06-18"),
                      "capabilities": {"tools": {"listChanged": False}},
                      "serverInfo": {"name": "mock-mcp", "version": "1.0"}}
        elif method == "tools/list":
            result = {"tools": [
                {"name": "echo", "description": "Echo the text back",
                 "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}}},
                {"name": "silent", "description": "Does nothing and returns no content",
                 "inputSchema": {"type": "object", "properties": {}}},
                {"name": "slow", "description": "Answers after three seconds",
                 "inputSchema": {"type": "object", "properties": {}}},
                {"name": "odd_schema", "description": "Tool whose schema root type is a list",
                 "inputSchema": {"type": ["object", "null"], "properties": {"q": {"type": "string"}}}},
            ]}
        elif method == "tools/call":
            params = req.get("params") or {}
            name = params.get("name")
            args = params.get("arguments") or {}
            if name == "silent":
                result = {"content": []}
            elif name == "slow":
                time.sleep(3)
                result = {"content": [{"type": "text", "text": "slow ok"}]}
            elif name == "odd_schema":
                result = {"content": [{"type": "text", "text": f"odd ok: {json.dumps(args)}"}]}
            else:
                result = {"content": [{"type": "text", "text": f"echo: {args.get('text', '')}"}]}
        elif method == "ping":
            result = {}
        else:
            return self._json(200, {"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": "not found"}})
        body = json.dumps({"jsonrpc": "2.0", "id": rid, "result": result}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Mcp-Session-Id", "mock-session")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_DELETE(self):
        self._json(200, {})

    def do_GET(self):
        if self.path.startswith("/mcp"):
            # no server-initiated stream
            self.send_response(405)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if self.path.startswith("/tiny.png"):
            import base64
            body = base64.b64decode(TINY_PNG)
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path.rstrip("/").endswith("/models"):
            return self._json(200, {"object": "list", "data": [{"id": "mock-chat", "object": "model"}]})
        self._json(404, {"error": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        req = json.loads(self.rfile.read(length) or b"{}")
        with LOCK:
            COUNTER["n"] += 1
            n = COUNTER["n"]
            with open(LOG, "a") as f:
                f.write(json.dumps({"n": n, "t": time.time(), "path": self.path, "body": req,
                                    "auth": self.headers.get("Authorization"), "x_mock": self.headers.get("X-Mock")}) + "\n")
        if self.path.startswith("/mcp"):
            return self._mcp(req)
        if self.path.rstrip("/").endswith(("/images/generations", "/images/edits")):
            if "[[img:emptyb64]]" in str(req.get("prompt", "")):
                # relay that returns both a url and an empty b64_json (upstream bdb2ddc7)
                return self._json(200, {"created": int(time.time()),
                                        "data": [{"url": "http://10.0.2.2:18080/tiny.png", "b64_json": ""}]})
            return self._json(200, {"created": int(time.time()), "data": [{"b64_json": TINY_PNG}]})
        if self.path.rstrip("/").endswith("/responses"):
            return self._responses(n, req)
        if self.path.rstrip("/").endswith("/messages"):
            return self._claude(n, req)
        messages = req.get("messages", [])
        text = last_user_text(messages)
        # dreaming first: its input quotes the chat, script markers included
        step = dream_reply(req, messages, text)
        for name, turns in SCRIPTS.items():
            if step is not None:
                break
            if f"[[script:{name}]]" in text:
                idx = min(tool_rounds(messages), len(turns) - 1)
                step = resolve_refs(turns[idx], messages)
        if step is None and "[[mock:auth_echo_401]]" in text:
            return self._json(401, {"error": {"message": f"invalid key: {self.headers.get('Authorization')}",
                                              "type": "invalid_request_error"}})
        if step is None and "[[probe:creator]]" in text:
            # Reports what the app sent: the creator tool names and whether a proposal update note arrived.
            names = sorted(t.get("function", {}).get("name", "") for t in req.get("tools", []))
            def content(m):
                c = m.get("content")
                return c if isinstance(c, str) else " ".join(p.get("text", "") for p in (c or []) if isinstance(p, dict))
            notes = [content(m) for m in messages if m.get("role") == "user"
                     and content(m).lstrip().startswith("<creator_proposal_updates>")]
            updates = "present" if any("APPLIED" in n for n in notes) else "absent"
            step = {"text": f"probe tools={','.join(n for n in names if n.startswith('creator_'))}; updates={updates}"}
        if step is None:
            step = compress_reply(messages, text)
        if step is None and "[[overflow]]" in text and not any(
                "[Summary of previous conversation]" in last_user_text([m]) for m in messages):
            # Context overflow until a compression checkpoint is in the request (compress-and-retry).
            return self._json(400, {"error": {
                "message": "This model's maximum context length is 1000 tokens. However, your messages resulted in 1500 tokens.",
                "type": "invalid_request_error", "code": "context_length_exceeded"}})
        if step is None:
            step = zaizao_reply(messages)
        if step is None:
            step = tuiqiao_reply(text)
        if step is None:
            step = polish_reply(text)
        if step is None:
            step = qubagu_reply(text)
        if step is None:
            step = {"text": f"mock reply #{n}: {text[:200]}"}
        if step.get("overflow_unless_summary") and not any(
                "[Summary of previous conversation]" in last_user_text([m]) for m in messages):
            return self._json(400, {"error": {
                "message": "This model's maximum context length is 1000 tokens. However, your messages resulted in 1500 tokens.",
                "type": "invalid_request_error", "code": "context_length_exceeded"}})
        # "delay" holds the reply back (e.g. slow subagents for overlap checks)
        if step.get("delay"):
            time.sleep(float(step["delay"]))
        if step.get("http_error"):
            return self._json(
                int(step["http_error"]),
                step.get("error_body") or {"error": {"message": "mock failure", "type": "server_error"}},
            )
        if req.get("stream"):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "close")
            self.end_headers()
            def ev(obj):
                self.wfile.write(b"data: " + json.dumps(obj).encode() + b"\n\n"); self.wfile.flush()
            base = {"id": f"chatcmpl-{n}", "object": "chat.completion.chunk", "created": int(time.time()), "model": "mock-chat"}
            if step.get("null_noise"):
                self.wfile.write(b"data: null\n\n"); self.wfile.flush()
                ev({**base, "choices": [{"index": 0, "delta": {"content": None, "reasoning_details": [None]}, "finish_reason": None}]})
                ev({**base, "choices": [{"index": 0, "delta": {"content": "", "annotations": [None, {"type": "url_citation", "url_citation": None}]}, "finish_reason": None}]})
            if step.get("reasoning"):
                ev({**base, "choices": [{"index": 0, "delta": {"role": "assistant", "reasoning_content": step["reasoning"]}, "finish_reason": None}]})
            if step.get("with_text"):
                ev({**base, "choices": [{"index": 0, "delta": {"role": "assistant", "content": step["with_text"]}, "finish_reason": None}]})
            if "calls" in step:
                # one chunk per call, each on its own index with its own id
                for k, call_step in enumerate(step["calls"]):
                    call = {"index": k, "id": f"call_{n}_{k}", "type": "function",
                            "function": {"name": call_step["tool"], "arguments": tool_arguments(call_step)}}
                    ev({**base, "choices": [{"index": 0, "delta": {"role": "assistant", "tool_calls": [call]}, "finish_reason": None}]})
                reset_key = text + "|" + str(tool_rounds(messages))
                if step.get("reset_once") and reset_key not in RESET_DONE:
                    # hold the complete calls on screen, then RST the socket (no finish chunk, no [DONE])
                    RESET_DONE.add(reset_key)
                    time.sleep(float(step["reset_once"]))
                    self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
                    self.connection.close()
                    self.close_connection = True
                    return
                ev({**base, "choices": [{"index": 0, "delta": {}, "finish_reason": "tool_calls"}], "usage": mock_usage(messages)})
            elif "tool" in step and step.get("slow_args"):
                full, size, delay = tool_arguments(step), int(step["slow_args"]["chunk"]), float(step["slow_args"]["delay"])
                for i in range(0, len(full), size):
                    call = {"index": 0, "function": {"arguments": full[i:i + size]}}
                    if i == 0:
                        call.update(id=f"call_{n}", type="function")
                        call["function"]["name"] = step["tool"]
                    ev({**base, "choices": [{"index": 0, "delta": {"role": "assistant", "tool_calls": [call]}, "finish_reason": None}]})
                    time.sleep(delay)
                ev({**base, "choices": [{"index": 0, "delta": {}, "finish_reason": "tool_calls"}], "usage": mock_usage(messages)})
            elif "tool" in step:
                for k, args in enumerate(step.get("same_index") or [None]):
                    call = {"index": 0, "type": "function",
                            "function": {"name": step["tool"], "arguments": tool_arguments(step, args)}}
                    if k == 0:
                        call["id"] = step.get("call_id", f"call_{n}")
                    ev({**base, "choices": [{"index": 0, "delta": {"role": "assistant", "tool_calls": [call]}, "finish_reason": None}]})
                ev({**base, "choices": [{"index": 0, "delta": {}, "finish_reason": "tool_calls"}], "usage": mock_usage(messages)})
            else:
                size = int(step.get("chunk_size", 16))
                for i in range(0, len(step["text"]), size):
                    ev({**base, "choices": [{"index": 0, "delta": {"content": step["text"][i:i + size]}, "finish_reason": None}]})
                    if step.get("chunk_delay"):
                        time.sleep(float(step["chunk_delay"]))
                ev({**base, "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
                    "usage": mock_usage(messages)})
            self.wfile.write(b"data: [DONE]\n\n"); self.wfile.flush()
            self.close_connection = True
        else:
            if "calls" in step:
                msg = {"role": "assistant", "content": None, "tool_calls": [
                    {"id": f"call_{n}_{k}", "type": "function",
                     "function": {"name": c["tool"], "arguments": tool_arguments(c)}}
                    for k, c in enumerate(step["calls"])]}
                fr = "tool_calls"
            elif "tool" in step:
                msg = {"role": "assistant", "content": step.get("with_text"), "tool_calls": [{"id": f"call_{n}", "type": "function",
                       "function": {"name": step["tool"], "arguments": tool_arguments(step)}}]}
                fr = "tool_calls"
            else:
                msg = {"role": "assistant", "content": step["text"]}
                if step.get("null_noise"):
                    msg["annotations"] = [None, {"type": "url_citation", "url_citation": None}]
                fr = "stop"
            self._json(200, {"id": f"chatcmpl-{n}", "object": "chat.completion", "model": "mock-chat",
                             "choices": [{"index": 0, "message": msg, "finish_reason": fr}],
                             "usage": mock_usage(messages)})


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18080
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
