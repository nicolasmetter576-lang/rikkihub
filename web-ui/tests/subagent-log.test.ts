import test from "node:test";
import assert from "node:assert/strict";

import {
  formatElapsedSeconds,
  formatTokenCount,
  isSubagentLogPath,
  isSubagentRunningStatus,
  mergeSubagentMetadata,
  parseSubagentMetadata,
  parseSubagentRunLog,
  resolveSubagentLogUrl,
  subagentElapsedMs,
} from "../app/lib/subagent-log";

test("parseSubagentMetadata reads camelCase Tool.metadata", () => {
  const meta = parseSubagentMetadata({
    title: "Fix tests",
    status: "running",
    logPath: "/subagent_runs/abc.json",
    stepCount: 3,
    modelId: "gpt-5",
    elapsedMs: 1234,
    totalTokens: 5678,
    startedAtEpochMs: 1000,
  });
  assert.equal(meta?.title, "Fix tests");
  assert.equal(meta?.status, "running");
  assert.equal(meta?.logPath, "/subagent_runs/abc.json");
  assert.equal(meta?.stepCount, 3);
  assert.equal(meta?.modelId, "gpt-5");
  assert.equal(meta?.totalTokens, 5678);
});

test("parseSubagentMetadata reads snake_case output JSON", () => {
  const meta = parseSubagentMetadata({
    status: "ok",
    summary: "done",
    tools_used: ["bash"],
    edited_paths: ["/workspace/a.txt"],
    truncated: true,
    log_path: "/subagent_runs/x.json",
    step_count: 2,
    model_id: "claude-4",
    elapsed_ms: 999,
    total_tokens: null,
    completed_at_epoch_ms: 5,
  });
  assert.equal(meta?.status, "ok");
  assert.deepEqual(meta?.toolsUsed, ["bash"]);
  assert.equal(meta?.truncated, true);
  assert.equal(meta?.stepCount, 2);
  assert.equal(meta?.totalTokens, undefined);
  assert.equal(meta?.completedAtEpochMs, 5);
});

test("parseSubagentMetadata rejects non-objects", () => {
  assert.equal(parseSubagentMetadata(null), undefined);
  assert.equal(parseSubagentMetadata("x"), undefined);
  assert.equal(parseSubagentMetadata([]), undefined);
  assert.equal(parseSubagentMetadata({}), undefined); // all-empty -> undefined
});

test("mergeSubagentMetadata overrides only defined fields", () => {
  const merged = mergeSubagentMetadata(
    parseSubagentMetadata({ log_path: "/subagent_runs/a.json", status: "ok" }),
    parseSubagentMetadata({ status: "running", stepCount: 4 }),
  );
  assert.equal(merged?.status, "running");
  assert.equal(merged?.stepCount, 4);
  assert.equal(merged?.logPath, "/subagent_runs/a.json");
});

test("isSubagentLogPath allowlists only flat json under the two dirs", () => {
  assert.ok(isSubagentLogPath("/subagent_runs/u.json"));
  assert.ok(isSubagentLogPath("subagent_runs/u.json"));
  assert.ok(isSubagentLogPath("/tool_outputs/legacy.json"));
  assert.ok(!isSubagentLogPath("/subagent_runs/../secret.json"));
  assert.ok(!isSubagentLogPath("/subagent_runs/nested/u.json"));
  assert.ok(!isSubagentLogPath("/subagent_runs/u.txt"));
  assert.ok(!isSubagentLogPath("https://evil.com/x.json"));
  assert.ok(!isSubagentLogPath("/etc/passwd"));
  assert.ok(!isSubagentLogPath(undefined));
  assert.ok(!isSubagentLogPath("/subagent_runs/u.json/extra"));
  assert.ok(!isSubagentLogPath("/subagent_runs/%2e%2e%2fsecret.json"));
  assert.ok(!isSubagentLogPath("/subagent_runs/.json"));
  assert.ok(!isSubagentLogPath("/subagent_runs/u?token=x.json"));
});

test("resolveSubagentLogUrl returns a credential-free api path or null", () => {
  const url = resolveSubagentLogUrl("/subagent_runs/u-1.json");
  assert.ok(url?.startsWith("/api/files/path/subagent_runs/u-1.json"));
  assert.equal(resolveSubagentLogUrl("/bad/x.json"), null);
  assert.equal(
    resolveSubagentLogUrl("/subagent_runs/u.json"),
    "/api/files/path/subagent_runs/u.json",
  );
  // No credential material may ever appear in the resolved URL.
  assert.ok(!url?.includes("token"));
  assert.ok(!url?.includes("?"));
});

test("parseSubagentRunLog parses v2 messages + transcript", () => {
  const log = parseSubagentRunLog({
    version: 2,
    prompt: "do stuff",
    status: "running",
    step_count: 1,
    model_id: "m",
    messages: [
      { role: "user", parts: [{ type: "text", text: "hi" }] },
      {
        role: "assistant",
        parts: [
          { type: "reasoning", reasoning: "think" },
          {
            type: "tool",
            toolCallId: "c1",
            toolName: "bash",
            input: '{"command":"ls"}',
            output: [{ type: "text", text: "file.txt" }],
            approvalState: { type: "auto" },
          },
          { type: "text", text: "answer" },
        ],
        usage: { promptTokens: 1, completionTokens: 2, cachedTokens: 0, totalTokens: 3 },
      },
    ],
    transcript: [{ role: "USER", text: "hi", tools: [{ name: "bash", input: "{}", output: "o" }] }],
  });
  assert.ok(log);
  assert.equal(log.messages.length, 2);
  const tool = log.messages[1].parts[1];
  assert.equal(tool.type, "tool");
  if (tool.type === "tool") {
    assert.equal(tool.toolName, "bash");
    assert.equal(tool.output[0].type, "text");
  }
  assert.equal(log.messages[1].usage?.totalTokens, 3);
  assert.equal(log.transcript[0].tools[0].output, "o");
});

test("parseSubagentRunLog tolerates v1 transcript-only and junk", () => {
  const legacy = parseSubagentRunLog({ transcript: [{ role: "USER", text: "t" }] });
  assert.ok(legacy);
  assert.equal(legacy.messages.length, 0);
  assert.equal(parseSubagentRunLog("nope"), null);
  assert.equal(parseSubagentRunLog(42), null);
});

test("subagentElapsedMs live vs frozen", () => {
  const running = parseSubagentMetadata({ status: "running", startedAtEpochMs: 1000 });
  assert.equal(subagentElapsedMs(running, 3500), 2500);
  const stale = parseSubagentMetadata({ status: "running", elapsedMs: 1200, startedAtEpochMs: 1 });
  assert.equal(subagentElapsedMs(stale, 999999, false), 1200); // stopped/disconnected
  assert.equal(subagentElapsedMs(stale, 3500, true), 3499); // live between metadata updates
  const done = parseSubagentMetadata({
    status: "ok",
    elapsedMs: 1200,
    startedAtEpochMs: 1,
    completedAtEpochMs: 9,
  });
  assert.equal(subagentElapsedMs(done, 999999), 1200);
  const doneNoElapsed = parseSubagentMetadata({
    status: "ok",
    startedAtEpochMs: 100,
    completedAtEpochMs: 800,
  });
  assert.equal(subagentElapsedMs(doneNoElapsed, 999999), 700);
  assert.equal(subagentElapsedMs(undefined, 0), undefined);
});

test("formatters", () => {
  assert.equal(formatElapsedSeconds(undefined), "— s");
  assert.equal(formatElapsedSeconds(500), "0 s");
  assert.equal(formatElapsedSeconds(12_345), "12 s");
  assert.equal(formatElapsedSeconds(-500), "0 s");
  assert.equal(formatTokenCount(undefined), "— k tok");
  assert.equal(formatTokenCount(-1), "— k tok");
  assert.equal(formatTokenCount(0), "0.0 k tok");
  assert.equal(formatTokenCount(999), "1.0 k tok");
  assert.equal(formatTokenCount(12_345), "12.3 k tok");
  assert.equal(formatTokenCount(2_500_000), "2500.0 k tok");
});

test("isSubagentRunningStatus", () => {
  assert.ok(isSubagentRunningStatus("running"));
  assert.ok(!isSubagentRunningStatus("ok"));
  assert.ok(!isSubagentRunningStatus(undefined));
});
