import type {
  ConversationDto,
  ConversationNodeUpdateEventDto,
  MessageNodeDto,
} from "../../app/types";

export function node(id = "n1", completion = 10): MessageNodeDto {
  return {
    id,
    selectIndex: 0,
    isFavorite: false,
    messages: [
      {
        id: `m-${id}`,
        role: "ASSISTANT",
        parts: [{ type: "text", text: "reply" }],
        createdAt: "2026-11-02T00:00:00Z",
        finishedAt: "2026-11-02T00:00:01Z",
        usage: {
          promptTokens: 100,
          completionTokens: completion,
          totalTokens: 100 + completion,
          cachedTokens: 20,
        },
        generationTimeMs: 1000,
      },
    ],
  };
}

export function conversation(id = "A", completion = 10): ConversationDto {
  return {
    id,
    assistantId: "assistant",
    title: id,
    messages: [node("n1", completion), node("n2", completion)],
    chatSuggestions: [],
    isPinned: false,
    createAt: 0,
    updateAt: 1,
    isGenerating: true,
  };
}

export function snapshot(seq = 1, detail = conversation()) {
  return { type: "snapshot" as const, seq, conversation: detail, serverTime: 1000 + seq };
}

export function update(
  seq: number,
  nodeId = "n1",
  nodeIndex = 0,
  completion = seq,
): ConversationNodeUpdateEventDto {
  return {
    type: "node_update",
    seq,
    conversationId: "A",
    nodeId,
    nodeIndex,
    node: node(nodeId, completion),
    updateAt: seq,
    isGenerating: true,
    serverTime: 1000 + seq,
  };
}

export function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  // Removed request paths leave the promise unused; still allow injecting a late rejection.
  void promise.catch(() => {});
  return { promise, resolve, reject };
}
