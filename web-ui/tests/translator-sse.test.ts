import test from "node:test";
import assert from "node:assert/strict";

import {
  TRANSLATOR_MAX_TEXT_BYTES,
  buildTranslatorRequest,
  createRequestEpoch,
  createTranslatorSseParser,
  feedTranslatorSseBytes,
  isTranslatorTextAcceptable,
  isValidBcp47Tag,
  listTranslatorChatModels,
  parseTranslatorEventData,
  readTranslateModeId,
  streamTranslate,
  translatorTextByteLength,
  type TranslatorStreamEvent,
} from "../app/overlay/lib/translator";

// ---------- SSE 块级解析 ----------

test("sse parser: complete event in one feed", () => {
  const parser = createTranslatorSseParser();
  const blocks = parser.feed('event: text\ndata: {"text":"hello"}\n\n');
  assert.deepEqual(blocks, [{ event: "text", data: '{"text":"hello"}' }]);
});

test("sse parser: event split across chunks", () => {
  const parser = createTranslatorSseParser();
  assert.deepEqual(parser.feed('event: te'), []);
  assert.deepEqual(parser.feed('xt\nda'), []);
  const blocks = parser.feed('ta: {"text":"a"}\n\n');
  assert.equal(blocks.length, 1);
  assert.equal(blocks[0].event, "text");
  assert.equal(blocks[0].data, '{"text":"a"}');
});

test("sse parser: CRLF and lone CR endings", () => {
  const parser = createTranslatorSseParser();
  const blocks = parser.feed('event: done\r\ndata: {"text":"x"}\r\n\revent: text\ndata: {"text":"y"}\n\n');
  assert.equal(blocks.length, 2);
  assert.equal(blocks[0].event, "done");
  assert.equal(blocks[1].data, '{"text":"y"}');
});

test("sse parser: comment/heartbeat lines are ignored", () => {
  const parser = createTranslatorSseParser();
  const blocks = parser.feed(': hb\n\n: hb\nevent: text\ndata: {"text":"t"}\n\n: hb\n');
  assert.equal(blocks.length, 1);
  assert.equal(blocks[0].data, '{"text":"t"}');
});

test("sse parser: multi-line data joined with newline", () => {
  const parser = createTranslatorSseParser();
  const blocks = parser.feed('data: {"a":1,\ndata: "b":2}\n\n');
  assert.equal(blocks.length, 1);
  assert.equal(blocks[0].data, '{"a":1,\n"b":2}');
  assert.equal(blocks[0].event, "message");
});

test("sse parser: event without trailing blank line stays buffered", () => {
  const parser = createTranslatorSseParser();
  assert.deepEqual(parser.feed('data: {"text":"x"}\n'), []);
});

// ---------- 多字节字符分片 ----------

test("utf8: multi-byte char split across byte chunks", () => {
  const parser = createTranslatorSseParser();
  const decoder = new TextDecoder();
  const full = 'event: text\ndata: {"text":"你好🌏"}\n\n';
  const bytes = new TextEncoder().encode(full);
  // 在「好」和 emoji 中间切开（emoji 是 4 字节）
  const mid = full.indexOf("🌏");
  const byteSplit = new TextEncoder().encode(full.slice(0, mid)).length + 2; // emoji 4 字节切一半
  const part1 = bytes.slice(0, byteSplit);
  const part2 = bytes.slice(byteSplit);
  const out1 = feedTranslatorSseBytes(parser, decoder, part1);
  const out2 = feedTranslatorSseBytes(parser, decoder, part2);
  const all = [...out1, ...out2];
  assert.equal(all.length, 1);
  assert.deepEqual(all[0], { type: "text", text: "你好🌏" });
});

test("utf8: byte-by-byte feeding still parses", () => {
  const parser = createTranslatorSseParser();
  const decoder = new TextDecoder();
  const bytes = new TextEncoder().encode('event: done\ndata: {"text":"翻訳完了"}\n\n');
  const events: TranslatorStreamEvent[] = [];
  for (const byte of bytes) {
    events.push(...feedTranslatorSseBytes(parser, decoder, Uint8Array.of(byte)));
  }
  assert.deepEqual(events, [{ type: "done", text: "翻訳完了" }]);
});

// ---------- 事件 JSON 解析 ----------

test("event data: text/done/error payloads", () => {
  assert.deepEqual(parseTranslatorEventData("text", '{"text":"a"}'), { type: "text", text: "a" });
  assert.deepEqual(parseTranslatorEventData("done", '{"text":"final"}'), { type: "done", text: "final" });
  assert.deepEqual(parseTranslatorEventData("error", '{"message":"boom"}'), { type: "error", message: "boom" });
  assert.deepEqual(parseTranslatorEventData("error", "{}"), { type: "error", message: "" });
});

test("event data: unknown event / bad json / wrong shape ignored", () => {
  assert.equal(parseTranslatorEventData("snapshot", '{"x":1}'), undefined);
  assert.equal(parseTranslatorEventData("text", "not json"), undefined);
  assert.equal(parseTranslatorEventData("text", '{"n":1}'), undefined);
  assert.equal(parseTranslatorEventData("done", '{"text":123}'), undefined);
});

// ---------- 语言 tag 校验 ----------

test("bcp47 validation", () => {
  for (const tag of ["en", "zh-CN", "zh-Hant", "fr-CA", "pt-BR", "en-US-x-twain"]) {
    assert.ok(isValidBcp47Tag(tag), `expected valid: ${tag}`);
  }
  for (const tag of ["", " ", "und", "en-", "-US", "中文", "a".repeat(65), "en_US"]) {
    assert.ok(!isValidBcp47Tag(tag), `expected invalid: ${tag}`);
  }
});

// ---------- 输入大小限制 ----------

test("text size limit counts utf-8 bytes", () => {
  assert.equal(translatorTextByteLength("a".repeat(TRANSLATOR_MAX_TEXT_BYTES)), TRANSLATOR_MAX_TEXT_BYTES);
  assert.ok(isTranslatorTextAcceptable("a".repeat(TRANSLATOR_MAX_TEXT_BYTES)));
  // 32768 个 ASCII + 1 个 3 字节汉字 = 越界
  assert.ok(!isTranslatorTextAcceptable("a".repeat(TRANSLATOR_MAX_TEXT_BYTES) + "字"));
  // 纯多字节：10922 个汉字 = 32766 字节 OK，10923 个 = 32769 越界
  assert.ok(isTranslatorTextAcceptable("字".repeat(10922)));
  assert.ok(!isTranslatorTextAcceptable("字".repeat(10923)));
  assert.ok(!isTranslatorTextAcceptable("   "));
  assert.ok(!isTranslatorTextAcceptable(""));
});

// ---------- 模型列表（只读） ----------

function fakeSettings(providers: unknown[]): never {
  return { providers } as never;
}

test("chat model list only includes enabled providers' CHAT models", () => {
  const settings = fakeSettings([
    {
      id: "p1",
      enabled: true,
      name: "OpenAI",
      type: "openai",
      models: [
        { id: "m-chat", modelId: "gpt-4o", displayName: "GPT-4o", type: "CHAT" },
        { id: "m-img", modelId: "dall-e", displayName: "DALL-E", type: "IMAGE" },
      ],
    },
    {
      id: "p2",
      enabled: false,
      name: "DisabledCo",
      type: "openai",
      models: [{ id: "m-off", modelId: "x", displayName: "X", type: "CHAT" }],
    },
  ]);
  const options = listTranslatorChatModels(settings);
  assert.equal(options.length, 1);
  assert.equal(options[0].id, "m-chat");
  assert.equal(options[0].label, "OpenAI / GPT-4o");
  // displayName 缺失时回落 modelId
  const noName = fakeSettings([
    { id: "p", enabled: true, name: "P", models: [{ id: "m", modelId: "m-name", displayName: "", type: "CHAT" }] },
  ]);
  assert.equal(listTranslatorChatModels(noName)[0].label, "P / m-name");
  assert.equal(listTranslatorChatModels(undefined).length, 0);
});

// ---------- 请求体构造（per-request override 语义） ----------

test("buildTranslatorRequest omits modelId when selection equals default", () => {
  assert.deepEqual(
    buildTranslatorRequest("hello", " en ", "m1", "m1"),
    { text: "hello", language: "en" },
  );
  assert.deepEqual(
    buildTranslatorRequest("hello", "ja", "m2", "m1"),
    { text: "hello", language: "ja", modelId: "m2" },
  );
  assert.deepEqual(
    buildTranslatorRequest("hello", "ja", undefined, "m1"),
    { text: "hello", language: "ja" },
  );
  // 全局默认缺失但用户选了模型 → 必须带 modelId
  assert.deepEqual(
    buildTranslatorRequest("hello", "ja", "m2", undefined),
    { text: "hello", language: "ja", modelId: "m2" },
  );
});

test("readTranslateModeId narrows Settings index signature", () => {
  assert.equal(readTranslateModeId({ translateModeId: "abc" } as never), "abc");
  assert.equal(readTranslateModeId({} as never), undefined);
  assert.equal(readTranslateModeId({ translateModeId: 42 } as never), undefined);
  assert.equal(readTranslateModeId(undefined), undefined);
});

// ---------- stale 防旧响应覆盖 ----------

test("request epoch invalidates previous runs", () => {
  const epoch = createRequestEpoch();
  const first = epoch.next();
  const second = epoch.next();
  assert.ok(!epoch.isCurrent(first));
  assert.ok(epoch.isCurrent(second));
  epoch.invalidate();
  assert.ok(!epoch.isCurrent(second));
});

// ---------- streamTranslate：注入 fetch 的集成路径 ----------

function sseResponse(body: string): Response {
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(body));
      controller.close();
    },
  });
  return new Response(stream, {
    status: 200,
    headers: { "Content-Type": "text/event-stream" },
  });
}

test("streamTranslate happy path: text+done events then close", async () => {
  const events: TranslatorStreamEvent[] = [];
  let opened = false;
  let closed = false;
  const fetchImpl = (async () =>
    sseResponse(
      ': hb\n\nevent: text\ndata: {"text":"he"}\n\nevent: text\ndata: {"text":"hello"}\n\nevent: done\ndata: {"text":"hello"}\n\n',
    )) as typeof fetch;
  await streamTranslate(
    { text: "hi", language: "en" },
    {
      onEvent: (e) => events.push(e),
      onOpen: () => {
        opened = true;
      },
      onClose: () => {
        closed = true;
      },
    },
    { fetchImpl },
  );
  assert.ok(opened);
  assert.ok(closed);
  assert.deepEqual(events, [
    { type: "text", text: "he" },
    { type: "text", text: "hello" },
    { type: "done", text: "hello" },
  ]);
});

test("streamTranslate: SSE error event surfaces via onEvent", async () => {
  const events: TranslatorStreamEvent[] = [];
  const fetchImpl = (async () =>
    sseResponse('event: error\ndata: {"message":"upstream failed"}\n\n')) as typeof fetch;
  let closed = false;
  await streamTranslate(
    { text: "hi", language: "en" },
    {
      onEvent: (e) => events.push(e),
      onClose: () => {
        closed = true;
      },
    },
    { fetchImpl },
  );
  assert.deepEqual(events, [{ type: "error", message: "upstream failed" }]);
  assert.ok(closed);
});

test("streamTranslate: http 400 parses {error, code} into ApiError", async () => {
  const fetchImpl = (async () =>
    new Response(JSON.stringify({ error: "'text' must not be empty", code: 400 }), {
      status: 400,
      headers: { "Content-Type": "application/json" },
    })) as typeof fetch;
  let error: Error | undefined;
  let closed = false;
  await streamTranslate(
    { text: "", language: "en" },
    {
      onEvent: () => assert.fail("no events expected"),
      onError: (e) => {
        error = e;
      },
      onClose: () => {
        closed = true;
      },
    },
    { fetchImpl },
  );
  assert.ok(error);
  assert.equal(error?.message, "'text' must not be empty");
  assert.ok(closed);
});

test("streamTranslate: non-json error body falls back to status", async () => {
  const fetchImpl = (async () => new Response("oops", { status: 502 })) as typeof fetch;
  let error: Error | undefined;
  await streamTranslate(
    { text: "hi", language: "en" },
    {
      onEvent: () => assert.fail("no events expected"),
      onError: (e) => {
        error = e;
      },
    },
    { fetchImpl },
  );
  assert.equal(error?.message, "HTTP 502");
});

test("streamTranslate: abort stops silently without onError", async () => {
  const controller = new AbortController();
  let closed = false;
  let errorCount = 0;
  const fetchImpl = ((_url: string, init?: RequestInit) =>
    new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener("abort", () => {
        reject(new DOMException("Aborted", "AbortError"));
      });
    })) as unknown as typeof fetch;
  const promise = streamTranslate(
    { text: "hi", language: "en" },
    {
      onEvent: () => {},
      onError: () => {
        errorCount += 1;
      },
      onClose: () => {
        closed = true;
      },
    },
    { fetchImpl, signal: controller.signal },
  );
  controller.abort();
  await promise;
  assert.ok(closed);
  assert.equal(errorCount, 0);
});

test("streamTranslate: stale request events are droppable via epoch guard", async () => {
  const epoch = createRequestEpoch();
  const eventsA: string[] = [];
  const eventsB: string[] = [];

  // A 先开始，然后 B 开始使 A 失效；A 的迟到 chunk 不得覆盖 B
  const staleA = epoch.next();
  const fetchA = (async () =>
    sseResponse('event: text\ndata: {"text":"A-late"}\n\nevent: done\ndata: {"text":"A"}\n\n')) as typeof fetch;
  const promiseA = streamTranslate(
    { text: "a", language: "en" },
    {
      onEvent: (e) => {
        if (epoch.isCurrent(staleA) && e.type === "text") eventsA.push(e.text);
      },
    },
    { fetchImpl: fetchA },
  );

  const liveB = epoch.next();
  const fetchB = (async () =>
    sseResponse('event: text\ndata: {"text":"B"}\n\nevent: done\ndata: {"text":"B"}\n\n')) as typeof fetch;
  const promiseB = streamTranslate(
    { text: "b", language: "en" },
    {
      onEvent: (e) => {
        if (epoch.isCurrent(liveB) && e.type === "text") eventsB.push(e.text);
      },
    },
    { fetchImpl: fetchB },
  );

  await Promise.all([promiseA, promiseB]);
  assert.deepEqual(eventsA, []); // A 的迟到事件被 epoch 丢弃
  assert.deepEqual(eventsB, ["B"]);
});

test("streamTranslate: network failure calls onError then onClose", async () => {
  const fetchImpl = (async () => {
    throw new TypeError("fetch failed");
  }) as unknown as typeof fetch;
  let error: Error | undefined;
  let closed = false;
  await streamTranslate(
    { text: "hi", language: "en" },
    {
      onEvent: () => assert.fail("no events expected"),
      onError: (e) => {
        error = e;
      },
      onClose: () => {
        closed = true;
      },
    },
    { fetchImpl },
  );
  assert.equal(error?.message, "fetch failed");
  assert.ok(closed);
});
