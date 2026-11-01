import * as React from "react";
import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";
import {
  AudioLines,
  BookHeart,
  BookOpen,
  BookX,
  Bot,
  Check,
  ChevronRight,
  Clipboard,
  ClipboardPaste,
  Clock3,
  Globe,
  Loader2,
  MessageCircleQuestion,
  Search,
  Send,
  Video,
  Wrench,
  X,
} from "lucide-react";

import Markdown from "~/components/markdown/markdown";
import { Button } from "~/components/ui/button";
import { Input } from "~/components/ui/input";
import {
  Drawer,
  DrawerContent,
  DrawerDescription,
  DrawerHeader,
  DrawerTitle,
} from "~/components/ui/drawer";
import { useIsMobile } from "~/hooks/use-mobile";
import { resolveFileUrl } from "~/lib/files";
import { TOOL_DURATION_MIN_MS, formatToolDuration, toolDurationMs } from "~/lib/tool-duration";
import { serverNow } from "~/lib/utils";
import {
  formatElapsedSeconds,
  formatTokenCount,
  hasSubagentMetadataShape,
  isSubagentLogPath,
  isSubagentRunningStatus,
  mergeSubagentMetadata,
  parseSubagentMetadata,
  parseSubagentRunLog,
  resolveSubagentLogUrl,
  subagentElapsedMs,
  type SubagentLogMessage,
  type SubagentMetadata,
  type SubagentRunLog,
  type SubagentTranscriptEntry,
} from "~/lib/subagent-log";
import type { TextPart as UITextPart, ToolPart as UIToolPart, UIMessagePart } from "~/types";

import { ControlledChainOfThoughtStep } from "../chain-of-thought";
import { AudioPart as AudioPartRenderer } from "./audio-part";
import { ImagePart as ImagePartRenderer } from "./image-part";
import { VideoPart as VideoPartRenderer } from "./video-part";

interface ToolPartProps {
  tool: UIToolPart;
  loading?: boolean;
  onToolApproval?: (
    toolCallId: string,
    approved: boolean,
    reason: string,
    answer?: string,
  ) => void | Promise<void>;
  isFirst?: boolean;
  isLast?: boolean;
}

const TOOL_NAMES = {
  MEMORY: "memory_tool",
  SEARCH_WEB: "search_web",
  SCRAPE_WEB: "scrape_web",
  GET_TIME_INFO: "get_time_info",
  CLIPBOARD: "clipboard_tool",
  ASK_USER: "ask_user",
  SUBAGENT: "subagent",
} as const;

// Exported so the message-part dispatcher can route to AskUserToolPart before hooks run.
export const ASK_USER_TOOL_NAME = TOOL_NAMES.ASK_USER;

// 记忆库工具（2.23.0 起）；旧对话里的 memory_tool 仍按 action 显示
const MEMORY_FILE_TOOLS = {
  READ: "memory_read",
  WRITE: "memory_write",
  STR_REPLACE: "memory_str_replace",
  APPEND: "memory_append",
  DELETE: "memory_delete",
  LIST: "memory_list",
} as const;

const MEMORY_FILE_EDIT_TOOLS: ReadonlySet<string> = new Set([
  MEMORY_FILE_TOOLS.WRITE,
  MEMORY_FILE_TOOLS.STR_REPLACE,
  MEMORY_FILE_TOOLS.APPEND,
]);

const MEMORY_ACTIONS = {
  CREATE: "create",
  EDIT: "edit",
  DELETE: "delete",
} as const;

const CLIPBOARD_ACTIONS = {
  READ: "read",
  WRITE: "write",
} as const;

function safeJsonParse(input: string): unknown {
  if (!input.trim()) return {};
  try {
    return JSON.parse(input);
  } catch {
    return {};
  }
}

function toJsonString(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2);
}

function getStringField(data: unknown, key: string): string | undefined {
  if (!data || typeof data !== "object" || Array.isArray(data)) return undefined;
  const value = (data as Record<string, unknown>)[key];
  return typeof value === "string" ? value : undefined;
}

function getArrayField(data: unknown, key: string): unknown[] {
  if (!data || typeof data !== "object" || Array.isArray(data)) return [];
  const value = (data as Record<string, unknown>)[key];
  return Array.isArray(value) ? value : [];
}

function isSubagentToolName(toolName: string): boolean {
  return toolName === TOOL_NAMES.SUBAGENT;
}

function getToolIcon(toolName: string, action?: string) {
  if (toolName === MEMORY_FILE_TOOLS.READ || toolName === MEMORY_FILE_TOOLS.LIST) return BookOpen;
  if (toolName === MEMORY_FILE_TOOLS.DELETE) return BookX;
  if (MEMORY_FILE_EDIT_TOOLS.has(toolName)) return BookHeart;

  if (toolName === TOOL_NAMES.MEMORY) {
    if (action === MEMORY_ACTIONS.CREATE || action === MEMORY_ACTIONS.EDIT) {
      return BookHeart;
    }
    if (action === MEMORY_ACTIONS.DELETE) {
      return BookX;
    }
    return Wrench;
  }

  if (toolName === TOOL_NAMES.SEARCH_WEB) return Search;
  if (toolName === TOOL_NAMES.SCRAPE_WEB) return Globe;
  if (toolName === TOOL_NAMES.GET_TIME_INFO) return Clock3;

  if (toolName === TOOL_NAMES.CLIPBOARD) {
    if (action === CLIPBOARD_ACTIONS.WRITE) return ClipboardPaste;
    return Clipboard;
  }

  if (toolName === TOOL_NAMES.ASK_USER) return MessageCircleQuestion;
  if (toolName === TOOL_NAMES.SUBAGENT) return Bot;

  return Wrench;
}

function getToolTitle(toolName: string, args: unknown, t: TFunction): string {
  const action = getStringField(args, "action");

  if (toolName === MEMORY_FILE_TOOLS.READ) return t("tool_part.memory_read");
  if (toolName === MEMORY_FILE_TOOLS.LIST) return t("tool_part.memory_list");
  if (toolName === MEMORY_FILE_TOOLS.DELETE) return t("tool_part.memory_delete");
  if (toolName === MEMORY_FILE_TOOLS.WRITE && getStringField(args, "if_version") === "new") {
    return t("tool_part.memory_create");
  }
  if (MEMORY_FILE_EDIT_TOOLS.has(toolName)) return t("tool_part.memory_edit");

  if (toolName === TOOL_NAMES.MEMORY) {
    if (action === MEMORY_ACTIONS.CREATE) return t("tool_part.memory_create");
    if (action === MEMORY_ACTIONS.EDIT) return t("tool_part.memory_edit");
    if (action === MEMORY_ACTIONS.DELETE) return t("tool_part.memory_delete");
  }

  if (toolName === TOOL_NAMES.SEARCH_WEB) {
    const query = getStringField(args, "query") ?? "";
    return query ? t("tool_part.search_web_with_query", { query }) : t("tool_part.search_web");
  }

  if (toolName === TOOL_NAMES.SCRAPE_WEB) return t("tool_part.scrape_web");
  if (toolName === TOOL_NAMES.GET_TIME_INFO) return t("tool_part.get_time_info");

  if (toolName === TOOL_NAMES.CLIPBOARD) {
    if (action === CLIPBOARD_ACTIONS.READ) return t("tool_part.clipboard_read");
    if (action === CLIPBOARD_ACTIONS.WRITE) return t("tool_part.clipboard_write");
  }

  if (toolName === TOOL_NAMES.ASK_USER) return t("tool_part.ask_user_title");
  if (toolName === TOOL_NAMES.SUBAGENT) {
    const title = getStringField(args, "description") ?? getStringField(args, "prompt") ?? "";
    return title ? t("tool_part.subagent_with_title", { title }) : t("tool_part.subagent");
  }

  return t("tool_part.tool_call_with_name", { toolName });
}

function JsonBlock({ value }: { value: unknown }) {
  return (
    <pre className="max-h-64 overflow-auto rounded-md border bg-muted/30 p-3 text-xs">
      {toJsonString(value)}
    </pre>
  );
}

function SearchWebPreview({ args, content }: { args: unknown; content: unknown }) {
  const { t } = useTranslation("message");
  const query = getStringField(args, "query") ?? "";
  const answer = getStringField(content, "answer");
  const items = getArrayField(content, "items");

  return (
    <div className="space-y-3">
      <div className="text-sm">
        {t("tool_part.search_query_label", { query: query || t("tool_part.empty") })}
      </div>
      {answer && (
        <div className="rounded-lg border bg-primary/5 p-3">
          <Markdown content={answer} className="text-sm" />
        </div>
      )}

      {items.length > 0 ? (
        <div className="space-y-2">
          {items.map((item, index) => {
            if (!item || typeof item !== "object" || Array.isArray(item)) {
              return null;
            }

            const record = item as Record<string, unknown>;
            const url = typeof record.url === "string" ? record.url : "";
            const title = typeof record.title === "string" ? record.title : "";
            const text = typeof record.text === "string" ? record.text : "";

            if (!url) return null;

            return (
              <a
                key={`${url}-${index}`}
                className="block rounded-lg border border-muted bg-card p-3 hover:bg-muted/40"
                href={url}
                rel="noreferrer"
                target="_blank"
              >
                <div className="line-clamp-1 font-medium text-sm">{title || url}</div>
                {text && (
                  <div className="mt-1 line-clamp-3 text-muted-foreground text-xs">{text}</div>
                )}
                <div className="mt-2 line-clamp-1 text-primary text-xs">{url}</div>
              </a>
            );
          })}
        </div>
      ) : (
        <JsonBlock value={content} />
      )}
    </div>
  );
}

const SUBAGENT_LOG_POLL_MS = 800;

function useSubagentStatusText(status: string | undefined): string | undefined {
  const { t } = useTranslation("message");
  if (!status) return undefined;
  const key = `tool_part.subagent_statuses.${status}`;
  const translated = t(key);
  return translated === key ? status : translated;
}

/** Run time of one tool call, ticking while it runs. Nothing for untimed calls or sub-100 ms runs. */
function ToolDuration({ tool, loading }: { tool: UIToolPart; loading: boolean }) {
  const running = loading && !!tool.startedAt && !tool.finishedAt;
  const [now, setNow] = React.useState(() => serverNow());

  React.useEffect(() => {
    setNow(serverNow());
    if (!running) return;
    const timer = window.setInterval(() => setNow(serverNow()), 100);
    return () => window.clearInterval(timer);
  }, [running]);

  const ms = toolDurationMs(tool, now, running);
  if (ms === null || ms < TOOL_DURATION_MIN_MS) return null;
  return (
    <span className="text-muted-foreground text-xs tabular-nums" data-tool-duration>
      {formatToolDuration(ms)}
    </span>
  );
}

/** Live ticking elapsed seconds — mounted only for subagent steps (never on generic tools). */
function useSubagentNow(meta: SubagentMetadata | undefined, enabled: boolean): number {
  const [now, setNow] = React.useState(() => Date.now());
  const running =
    enabled &&
    meta?.status === "running" &&
    meta.startedAtEpochMs !== undefined &&
    meta.completedAtEpochMs === undefined;

  React.useEffect(() => {
    setNow(Date.now());
    if (!running) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [running]);

  return now;
}

function SubagentStatsLine({
  meta,
  now,
  running,
  className,
}: {
  meta: SubagentMetadata | undefined;
  now: number;
  running: boolean;
  className?: string;
}) {
  const { t } = useTranslation("message");
  const model = meta?.modelId ?? "—";
  const steps =
    meta?.stepCount !== undefined ? t("tool_part.subagent_steps", { count: meta.stepCount }) : "—";
  const elapsed = formatElapsedSeconds(subagentElapsedMs(meta, now, running));
  const tokens = formatTokenCount(meta?.totalTokens);
  return <span className={className}>{[model, steps, elapsed, tokens].join(" · ")}</span>;
}

/** Card label: `任务名 · 模型id · 步数 · x s · xx k tok`. Title truncates, stats stay. */
function SubagentLabel({
  meta,
  title,
  running,
}: {
  meta: SubagentMetadata | undefined;
  title: string;
  running: boolean;
}) {
  const now = useSubagentNow(meta, running);
  return (
    <span className="text-sm [overflow-wrap:anywhere]">
      {meta?.presetName || meta?.presetKey ? (
        <span data-subagent-role className="mr-2 rounded border px-1 text-xs font-normal text-muted-foreground" title={meta.presetKey}>
          {meta.presetName || meta.presetKey}
        </span>
      ) : null}
      <span className="font-medium text-foreground" title={title}>
        {title.length > 28 ? `${title.slice(0, 28)}…` : title}
      </span>
      <span className="font-normal text-muted-foreground text-xs">
        {" · "}
        <SubagentStatsLine meta={meta} now={now} running={running} />
      </span>
    </span>
  );
}

function SubagentSummary({
  args,
  meta,
  running,
}: {
  args: unknown;
  meta: SubagentMetadata | undefined;
  running: boolean;
}) {
  const { t } = useTranslation("message");
  const status = useSubagentStatusText(
    !running && meta?.status === "running" ? "interrupted" : meta?.status,
  );
  const summary = meta?.summary ?? getStringField(args, "prompt") ?? "";
  return (
    <div className="space-y-1">
      {status || running ? (
        <div className="text-muted-foreground text-xs">
          {status
            ? t("tool_part.subagent_status", { status })
            : t("tool_part.subagent_status", {
                status: t("tool_part.subagent_statuses.running"),
              })}
        </div>
      ) : null}
      {summary ? <div className="line-clamp-3 text-muted-foreground text-xs">{summary}</div> : null}
      {meta?.truncated === true ? (
        <div className="text-muted-foreground text-xs">{t("tool_part.subagent_truncated")}</div>
      ) : null}
      {meta?.error ? (
        <div className="line-clamp-2 text-destructive text-xs">{meta.error}</div>
      ) : null}
    </div>
  );
}

interface SubagentLogState {
  path?: string;
  log: SubagentRunLog | null;
  /** A fetch/parse was attempted and produced no usable log. */
  failed: boolean;
}

/**
 * Fetches the persisted run log only while the drawer is open. Polls while the
 * log itself reports `running` (backend rewrites it ~every 400 ms), refreshes
 * once more after terminal state, and never clears an already-loaded log on
 * transient fetch failures.
 */
function useSubagentLog(
  logPath: string | undefined,
  enabled: boolean,
  running: boolean,
  completedAt: number | undefined,
): SubagentLogState {
  const [state, setState] = React.useState<SubagentLogState>({ log: null, failed: false });

  React.useEffect(() => {
    if (!enabled) return;
    if (!logPath || !isSubagentLogPath(logPath)) {
      setState({ path: logPath, log: null, failed: true });
      return;
    }
    const url = resolveSubagentLogUrl(logPath);
    if (!url) return;

    let disposed = false;
    let controller: AbortController | null = null;
    let timer: number | undefined;

    const load = async () => {
      controller?.abort();
      const current = new AbortController();
      controller = current;
      let scheduleNext = running;
      const failed = () =>
        setState((prev) => ({
          path: logPath,
          log: prev.path === logPath ? prev.log : null,
          failed: true,
        }));
      try {
        const response = await fetch(url, { signal: current.signal, cache: "no-store" });
        if (response.ok) {
          const parsed = parseSubagentRunLog(await response.json());
          if (!disposed && parsed) {
            setState({ path: logPath, log: parsed, failed: false });
            scheduleNext = running && (!parsed.status || isSubagentRunningStatus(parsed.status));
          } else if (!disposed) {
            failed();
          }
        } else if (!disposed) {
          failed();
        }
      } catch {
        if (!disposed) failed();
      }
      if (!disposed && scheduleNext) {
        timer = window.setTimeout(() => void load(), SUBAGENT_LOG_POLL_MS);
      }
    };

    void load();
    return () => {
      disposed = true;
      controller?.abort();
      if (timer !== undefined) window.clearTimeout(timer);
    };
  }, [logPath, enabled, running, completedAt]);

  return state.path === logPath ? state : { path: logPath, log: null, failed: false };
}

function CollapsibleSection({
  label,
  defaultOpen = false,
  children,
}: {
  label: React.ReactNode;
  defaultOpen?: boolean;
  children: React.ReactNode;
}) {
  const [expanded, setExpanded] = React.useState(defaultOpen);
  return (
    <details
      open={expanded}
      onToggle={(event) => setExpanded(event.currentTarget.open)}
      className="group rounded-md border bg-muted/20 text-xs"
    >
      <summary className="flex cursor-pointer list-none items-center gap-1 px-2 py-1.5 font-medium text-muted-foreground [&::-webkit-details-marker]:hidden">
        <ChevronRight className="h-3 w-3 transition-transform group-open:rotate-90" />
        {label}
      </summary>
      {expanded && <div className="border-t px-2 py-2">{children}</div>}
    </details>
  );
}

function JsonOrTextBlock({ text }: { text: string }) {
  const trimmed = text.trim();
  if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
    try {
      return <JsonBlock value={JSON.parse(trimmed)} />;
    } catch {
      // fall through to plain text
    }
  }
  return (
    <pre className="max-h-64 overflow-auto rounded-md border bg-muted/30 p-3 text-xs whitespace-pre-wrap break-words">
      {text}
    </pre>
  );
}

/** One tool call inside the subagent transcript — grouped input/output, collapsed by default. */
function SubagentToolEntry({ part }: { part: Extract<UIMessagePart, { type: "tool" }> }) {
  const { t } = useTranslation("message");
  const outputText = part.output
    .filter((output): output is UITextPart => output.type === "text")
    .map((output) => output.text)
    .join("\n");
  const mediaOutputs = part.output.filter(
    (output) => output.type === "image" || output.type === "video" || output.type === "audio",
  );
  return (
    <CollapsibleSection label={part.toolName || t("tool_part.subagent_log_tool")}>
      <div className="space-y-2">
        <div>
          <div className="mb-1 text-muted-foreground">{t("tool_part.subagent_log_input")}</div>
          <JsonOrTextBlock text={part.input} />
        </div>
        {outputText ? (
          <div>
            <div className="mb-1 text-muted-foreground">{t("tool_part.subagent_log_output")}</div>
            <JsonOrTextBlock text={outputText} />
          </div>
        ) : null}
        {mediaOutputs.length > 0 ? (
          <div className="flex flex-wrap gap-1">
            {mediaOutputs.map((output, index) =>
              output.type === "image" ? (
                <img
                  key={index}
                  alt=""
                  className="h-16 w-auto rounded border border-muted object-contain"
                  src={resolveFileUrl(output.url)}
                />
              ) : (
                <span
                  key={index}
                  className="inline-flex items-center rounded border border-muted bg-muted/30 px-2 py-1 text-muted-foreground"
                >
                  {output.type}
                </span>
              ),
            )}
          </div>
        ) : null}
      </div>
    </CollapsibleSection>
  );
}

/** Renders a v2 `messages[]` UIMessage — Markdown text, collapsible reasoning + tool IO. */
function SubagentLogMessageView({ message }: { message: SubagentLogMessage }) {
  const { t } = useTranslation("message");
  const roleLabel =
    message.role === "user"
      ? t("tool_part.subagent_log_role_user")
      : message.role === "assistant"
        ? t("tool_part.subagent_log_role_assistant")
        : message.role;
  return (
    <div className="space-y-1.5">
      <div className="flex items-baseline gap-2">
        <span className="font-medium text-muted-foreground text-xs">{roleLabel}</span>
        {message.usage ? (
          <span className="text-muted-foreground/70 text-xs">
            {formatTokenCount(message.usage.totalTokens)}
          </span>
        ) : null}
      </div>
      {message.parts.map((part, index) => {
        switch (part.type) {
          case "text":
            return part.text.trim() ? (
              <Markdown key={index} content={part.text} className="text-sm" />
            ) : null;
          case "reasoning":
            return part.reasoning.trim() ? (
              <CollapsibleSection key={index} label={t("tool_part.subagent_log_reasoning")}>
                <div className="whitespace-pre-wrap break-words text-muted-foreground">
                  {part.reasoning}
                </div>
              </CollapsibleSection>
            ) : null;
          case "tool":
            return <SubagentToolEntry key={part.toolCallId || index} part={part} />;
          case "image":
            return (
              <img
                key={index}
                alt=""
                className="max-h-40 w-auto rounded border border-muted object-contain"
                src={resolveFileUrl(part.url)}
              />
            );
          default:
            return null;
        }
      })}
    </div>
  );
}

function SubagentTranscriptEntryView({ entry }: { entry: SubagentTranscriptEntry }) {
  const { t } = useTranslation("message");
  return (
    <div className="space-y-1.5">
      <div className="font-medium text-muted-foreground text-xs">{entry.role}</div>
      {entry.reasoning ? (
        <CollapsibleSection label={t("tool_part.subagent_log_reasoning")}>
          <div className="whitespace-pre-wrap break-words text-muted-foreground">
            {entry.reasoning}
          </div>
        </CollapsibleSection>
      ) : null}
      {entry.text.trim() ? <Markdown content={entry.text} className="text-sm" /> : null}
      {entry.tools.map((tool, index) => (
        <CollapsibleSection key={index} label={tool.name}>
          <div className="space-y-2">
            <div>
              <div className="mb-1 text-muted-foreground">{t("tool_part.subagent_log_input")}</div>
              <JsonOrTextBlock text={tool.input} />
            </div>
            {tool.output ? (
              <div>
                <div className="mb-1 text-muted-foreground">
                  {t("tool_part.subagent_log_output")}
                </div>
                <JsonOrTextBlock text={tool.output} />
              </div>
            ) : null}
          </div>
        </CollapsibleSection>
      ))}
    </div>
  );
}

function SubagentPreview({
  args,
  meta,
  open,
  running,
  outputContent,
}: {
  args: unknown;
  meta: SubagentMetadata | undefined;
  open: boolean;
  running: boolean;
  outputContent: unknown;
}) {
  const { t } = useTranslation("message");
  const { log, failed } = useSubagentLog(meta?.logPath, open, running, meta?.completedAtEpochMs);
  const detailMeta = mergeSubagentMetadata(meta, parseSubagentMetadata(log));
  const statusText = useSubagentStatusText(detailMeta?.status);
  const detailRunning = running && (!detailMeta?.status || detailMeta.status === "running");
  const now = useSubagentNow(detailMeta, open && detailRunning);

  const prompt = log?.prompt ?? getStringField(args, "prompt");
  const context = log?.context ?? getStringField(args, "context");
  const summary = log?.summary || meta?.summary;
  const tools = meta?.toolsUsed.length ? meta.toolsUsed : (log?.toolsUsed ?? []);
  const paths = meta?.editedPaths.length ? meta.editedPaths : (log?.editedPaths ?? []);

  return (
    <div className="space-y-4">
      {detailMeta?.presetName || detailMeta?.presetKey ? (
        <div data-subagent-role className="text-sm font-medium">
          {detailMeta.presetName || detailMeta.presetKey}
          {detailMeta.presetKey ? <span className="ml-2 font-mono text-xs text-muted-foreground">{detailMeta.presetKey}</span> : null}
          {detailMeta.presetRevision != null ? <span className="ml-2 text-xs text-muted-foreground">r{detailMeta.presetRevision}</span> : null}
        </div>
      ) : null}
      <SubagentStatsLine
        meta={detailMeta}
        now={now}
        running={detailRunning}
        className="block text-xs text-muted-foreground [overflow-wrap:anywhere]"
      />
      {prompt ? (
        <CollapsibleSection label={t("tool_part.subagent_task_label")}>
          <div className="text-sm whitespace-pre-wrap break-words">{prompt}</div>
        </CollapsibleSection>
      ) : null}

      {context ? (
        <CollapsibleSection label={t("tool_part.subagent_context_label")}>
          <div className="whitespace-pre-wrap break-words text-muted-foreground">{context}</div>
        </CollapsibleSection>
      ) : null}

      {statusText || detailMeta?.error ? (
        <div className="text-sm">
          {statusText ? t("tool_part.subagent_status", { status: statusText }) : null}
          {detailMeta?.error ? (
            <div className="text-destructive text-xs">{detailMeta.error}</div>
          ) : null}
        </div>
      ) : null}

      {summary ? (
        <div>
          <div className="mb-1 text-muted-foreground text-xs">
            {t("tool_part.subagent_report_label")}
          </div>
          <Markdown content={summary} className="text-sm" />
          {meta?.truncated === true ? (
            <div className="mt-1 text-muted-foreground text-xs">
              {t("tool_part.subagent_truncated")}
            </div>
          ) : null}
        </div>
      ) : null}

      {tools.length > 0 ? (
        <div className="text-xs">
          {t("tool_part.subagent_tools")}: {tools.join(", ")}
        </div>
      ) : null}
      {paths.length > 0 ? (
        <div className="text-xs break-all">
          {t("tool_part.subagent_paths")}: {paths.join(", ")}
        </div>
      ) : null}

      {log && log.messages.length > 0 ? (
        <div className="space-y-3">
          <div className="text-muted-foreground text-xs">{t("tool_part.subagent_log")}</div>
          {log.messages
            .filter((message) => message.role.toLowerCase() !== "user")
            .map((message, index) =>
              message.role.toLowerCase() === "system" ? (
                <CollapsibleSection key={index} label={message.role}>
                  <SubagentLogMessageView message={message} />
                </CollapsibleSection>
              ) : (
                <SubagentLogMessageView key={index} message={message} />
              ),
            )}
        </div>
      ) : log && log.transcript.length > 0 ? (
        <div className="space-y-3">
          <div className="text-muted-foreground text-xs">{t("tool_part.subagent_log")}</div>
          {log.transcript
            .filter((entry) => entry.role.toLowerCase() !== "user")
            .map((entry, index) => (
              <SubagentTranscriptEntryView key={index} entry={entry} />
            ))}
        </div>
      ) : (
        <div className="space-y-2">
          <div className="text-muted-foreground text-xs">
            {meta?.logPath
              ? failed
                ? t("tool_part.subagent_log_unavailable")
                : t("tool_part.subagent_log_loading")
              : t("tool_part.subagent_log_unavailable")}
          </div>
          {!meta?.logPath && outputContent !== undefined && outputContent !== null ? (
            <CollapsibleSection label={t("tool_part.result")}>
              <JsonBlock value={outputContent} />
            </CollapsibleSection>
          ) : null}
        </div>
      )}
    </div>
  );
}

function ScrapeWebPreview({ content }: { content: unknown }) {
  const urls = getArrayField(content, "urls");

  if (urls.length === 0) {
    return <JsonBlock value={content} />;
  }

  return (
    <div className="space-y-3">
      {urls.map((item, index) => {
        if (!item || typeof item !== "object" || Array.isArray(item)) {
          return null;
        }

        const record = item as Record<string, unknown>;
        const url = typeof record.url === "string" ? record.url : "";
        const text = typeof record.content === "string" ? record.content : "";

        return (
          <div key={`${url}-${index}`} className="space-y-2 rounded-lg border p-3">
            <div className="line-clamp-1 text-muted-foreground text-xs">{url}</div>
            <div className="rounded-md border bg-muted/20 p-2">
              <Markdown content={text} className="text-sm" />
            </div>
          </div>
        );
      })}
    </div>
  );
}

interface AskUserQuestion {
  id: string;
  question: string;
  options: string[];
  selectionType: "text" | "single" | "multi";
}

function parseAskUserQuestions(args: unknown): AskUserQuestion[] {
  try {
    const questions = getArrayField(args, "questions");
    return questions
      .map((q) => {
        if (!q || typeof q !== "object" || Array.isArray(q)) return null;
        const record = q as Record<string, unknown>;
        const id = typeof record.id === "string" ? record.id : "";
        const question = typeof record.question === "string" ? record.question : "";
        if (!id || !question) return null;
        const rawOptions = Array.isArray(record.options) ? record.options : [];
        const options = rawOptions.filter((o): o is string => typeof o === "string");
        const selectionType: AskUserQuestion["selectionType"] =
          record.selection_type === "single" || record.selection_type === "multi"
            ? record.selection_type
            : "text";
        return { id, question, options, selectionType } satisfies AskUserQuestion;
      })
      .filter((q): q is AskUserQuestion => q !== null);
  } catch {
    return [];
  }
}

export function AskUserToolPart({ tool, loading, onToolApproval, isFirst, isLast }: ToolPartProps) {
  const { t } = useTranslation("message");
  const [expanded, setExpanded] = React.useState(true);

  const args = React.useMemo(() => safeJsonParse(tool.input), [tool.input]);
  const questions = React.useMemo(() => parseAskUserQuestions(args), [args]);
  const [answers, setAnswers] = React.useState<Record<string, string>>({});
  const [multiAnswers, setMultiAnswers] = React.useState<Record<string, string[]>>({});

  const isPending = tool.output.length === 0 && tool.approvalState.type === "pending";
  const isAnswered = tool.approvalState.type === "answered";

  const firstQuestion = questions[0]?.question ?? "...";
  const title =
    questions.length <= 1
      ? firstQuestion
      : t("tool_part.ask_user_questions_count", { count: questions.length });

  const getAnswer = (q: AskUserQuestion): string => {
    const text = answers[q.id] ?? "";
    return q.selectionType === "multi"
      ? [...(multiAnswers[q.id] ?? []), ...(text.trim() ? [text] : [])].join(", ")
      : text;
  };
  const allAnswered = questions.length > 0 && questions.every((q) => getAnswer(q).trim());

  const handleSubmit = () => {
    if (!onToolApproval || !allAnswered) return;
    const payload = JSON.stringify({
      answers: Object.fromEntries(questions.map((q) => [q.id, getAnswer(q)])),
    });
    void onToolApproval(tool.toolCallId, true, "", payload);
  };

  const setAnswer = (questionId: string, value: string) => {
    setAnswers((prev) => ({ ...prev, [questionId]: value }));
  };

  const toggleOption = (questionId: string, option: string) => {
    setMultiAnswers((prev) => {
      const selected = prev[questionId] ?? [];
      return {
        ...prev,
        [questionId]: selected.includes(option)
          ? selected.filter((value) => value !== option)
          : [...selected, option],
      };
    });
  };

  // Parse answered state for display
  const answeredValues = React.useMemo(() => {
    if (tool.approvalState.type !== "answered") return {};
    try {
      const parsed = JSON.parse(tool.approvalState.answer) as { answers?: Record<string, string> };
      return parsed.answers ?? {};
    } catch {
      return {};
    }
  }, [tool.approvalState]);

  return (
    <ControlledChainOfThoughtStep
      expanded={expanded}
      onExpandedChange={setExpanded}
      isFirst={isFirst}
      isLast={isLast}
      icon={
        loading ? (
          <Loader2 className="h-4 w-4 animate-spin text-primary" />
        ) : (
          <MessageCircleQuestion className="h-4 w-4 text-primary" />
        )
      }
      label={<span className="text-foreground line-clamp-2 text-sm font-medium">{title}</span>}
    >
      <div className="space-y-3 w-full">
        {questions.map((q) => (
          <div key={q.id} className="space-y-1.5">
            {questions.length > 1 && <div className="text-sm text-foreground">{q.question}</div>}

            {isPending && onToolApproval ? (
              <>
                {q.options.length > 0 && (
                  <div className="flex flex-wrap gap-1.5">
                    {q.options.map((option) => (
                      <button
                        key={option}
                        type="button"
                        aria-pressed={
                          q.selectionType === "multi"
                            ? (multiAnswers[q.id] ?? []).includes(option)
                            : answers[q.id] === option
                        }
                        onClick={() =>
                          q.selectionType === "multi"
                            ? toggleOption(q.id, option)
                            : setAnswer(q.id, option)
                        }
                        className={`rounded-full border px-3 py-1 text-xs transition-colors ${
                          (
                            q.selectionType === "multi"
                              ? (multiAnswers[q.id] ?? []).includes(option)
                              : answers[q.id] === option
                          )
                            ? "border-primary bg-primary/10 text-primary"
                            : "border-muted-foreground/30 text-muted-foreground hover:border-primary/50"
                        }`}
                      >
                        {option}
                      </button>
                    ))}
                  </div>
                )}
                <Input
                  value={answers[q.id] ?? ""}
                  onChange={(e) => setAnswer(q.id, e.target.value)}
                  placeholder={q.question}
                  className="text-sm"
                  onKeyDown={(e) => {
                    if (e.key === "Enter" && !e.shiftKey && allAnswered) {
                      e.preventDefault();
                      handleSubmit();
                    }
                  }}
                />
              </>
            ) : isAnswered ? (
              <div className="text-sm text-primary">{answeredValues[q.id] ?? ""}</div>
            ) : null}
          </div>
        ))}

        {isPending && onToolApproval && (
          <div className="flex justify-end">
            <Button size="sm" variant="secondary" disabled={!allAnswered} onClick={handleSubmit}>
              <Send className="mr-1.5 h-3.5 w-3.5" />
              {t("tool_part.ask_user_submit")}
            </Button>
          </div>
        )}
      </div>
    </ControlledChainOfThoughtStep>
  );
}

export function ToolPart({
  tool,
  loading = false,
  onToolApproval,
  isFirst,
  isLast,
}: ToolPartProps) {
  const { t } = useTranslation("message");
  const isMobile = useIsMobile();
  const [expanded, setExpanded] = React.useState(true);
  const [drawerOpen, setDrawerOpen] = React.useState(false);

  const args = React.useMemo(() => safeJsonParse(tool.input), [tool.input]);

  const outputText = React.useMemo(
    () =>
      tool.output
        .filter((part): part is UITextPart => part.type === "text")
        .map((part) => part.text)
        .join("\n"),
    [tool.output],
  );

  const outputContent = React.useMemo(() => safeJsonParse(outputText), [outputText]);

  const hasMediaOutput = React.useMemo(
    () => tool.output.some((p) => p.type === "image" || p.type === "video" || p.type === "audio"),
    [tool.output],
  );

  const memoryAction = getStringField(args, "action");
  const title = getToolTitle(tool.toolName, args, t);
  const isPending = tool.output.length === 0 && tool.approvalState.type === "pending";
  const isDenied = tool.approvalState.type === "denied";
  const deniedReason =
    tool.approvalState.type === "denied" ? (tool.approvalState.reason ?? "") : "";
  const isExecuted = tool.output.length > 0;

  // Subagent metadata: live values on tool.metadata, terminal copy on the output
  // text part's metadata and inside the output JSON (snake_case). Tool name may
  // be overridden, so metadata shape is accepted as a fallback signal.
  const subagentMeta = React.useMemo(
    () =>
      mergeSubagentMetadata(
        parseSubagentMetadata(outputContent),
        parseSubagentMetadata(tool.output[0]?.metadata),
        parseSubagentMetadata(tool.metadata),
      ),
    [outputContent, tool.output, tool.metadata],
  );
  const isSubagent =
    isSubagentToolName(tool.toolName) ||
    hasSubagentMetadataShape(tool.metadata) ||
    hasSubagentMetadataShape(tool.output[0]?.metadata) ||
    hasSubagentMetadataShape(outputContent);
  const subagentRunning =
    isSubagent &&
    !isDenied &&
    !isPending &&
    loading &&
    !isExecuted &&
    (!subagentMeta?.status || isSubagentRunningStatus(subagentMeta.status));
  const subagentTitle = React.useMemo(() => {
    if (!isSubagent) return title;
    const metaTitle = subagentMeta?.title?.trim();
    if (metaTitle) return metaTitle;
    const argTitle = (getStringField(args, "description") ?? getStringField(args, "prompt"))
      ?.replace(/\n/g, " ")
      .trim();
    return argTitle || t("tool_part.subagent");
  }, [isSubagent, subagentMeta, args, title, t]);

  const hasExtraContent =
    (tool.toolName === TOOL_NAMES.MEMORY &&
      (memoryAction === MEMORY_ACTIONS.CREATE || memoryAction === MEMORY_ACTIONS.EDIT) &&
      Boolean(getStringField(outputContent, "content"))) ||
    (tool.toolName === TOOL_NAMES.SEARCH_WEB &&
      (Boolean(getStringField(outputContent, "answer")) ||
        getArrayField(outputContent, "items").length > 0)) ||
    (tool.toolName === TOOL_NAMES.SCRAPE_WEB && Boolean(getStringField(args, "url"))) ||
    isSubagent ||
    isDenied ||
    hasMediaOutput;

  // Running subagents expose a live progress log, so the drawer is clickable
  // before the tool produces output. Other tools keep the old behavior.
  const canOpenDrawer = isPending || isExecuted || isSubagent;
  const Icon = getToolIcon(tool.toolName, memoryAction);

  const handleApprove = async (event: React.MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    if (!onToolApproval) return;
    await onToolApproval(tool.toolCallId, true, "");
  };

  const handleDeny = async (event: React.MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    if (!onToolApproval) return;
    const reason = window.prompt(t("tool_part.deny_reason_prompt"), "");
    if (reason === null) return;
    await onToolApproval(tool.toolCallId, false, reason);
  };

  return (
    <>
      <ControlledChainOfThoughtStep
        expanded={expanded}
        onExpandedChange={setExpanded}
        isFirst={isFirst}
        isLast={isLast}
        icon={
          loading ? (
            <Loader2 className="h-4 w-4 animate-spin text-primary" />
          ) : (
            <Icon className="h-4 w-4 text-primary" />
          )
        }
        label={
          isSubagent ? (
            <SubagentLabel meta={subagentMeta} title={subagentTitle} running={subagentRunning} />
          ) : (
            <span className="text-foreground line-clamp-2 text-sm font-medium">{title}</span>
          )
        }
        extra={
          isPending && onToolApproval ? (
            <div className="flex items-center gap-1">
              <Button onClick={handleDeny} size="icon-xs" type="button" variant="secondary">
                <X className="h-3.5 w-3.5" />
              </Button>
              <Button onClick={handleApprove} size="icon-xs" type="button" variant="secondary">
                <Check className="h-3.5 w-3.5" />
              </Button>
            </div>
          ) : isSubagent ? undefined : (
            <ToolDuration tool={tool} loading={loading} />
          )
        }
        onClick={canOpenDrawer ? () => setDrawerOpen(true) : undefined}
      >
        {hasExtraContent && (
          <div className="space-y-1">
            {tool.toolName === TOOL_NAMES.MEMORY &&
              (memoryAction === MEMORY_ACTIONS.CREATE || memoryAction === MEMORY_ACTIONS.EDIT) && (
                <div className="line-clamp-3 text-muted-foreground text-xs">
                  {getStringField(outputContent, "content")}
                </div>
              )}

            {tool.toolName === TOOL_NAMES.SEARCH_WEB && getStringField(outputContent, "answer") && (
              <div className="line-clamp-3 text-muted-foreground text-xs">
                {getStringField(outputContent, "answer")}
              </div>
            )}

            {tool.toolName === TOOL_NAMES.SEARCH_WEB &&
              getArrayField(outputContent, "items").length > 0 && (
                <div className="text-muted-foreground text-xs">
                  {t("tool_part.search_results_count", {
                    count: getArrayField(outputContent, "items").length,
                  })}
                </div>
              )}

            {tool.toolName === TOOL_NAMES.SCRAPE_WEB && getStringField(args, "url") && (
              <div className="line-clamp-2 text-muted-foreground text-xs">
                {getStringField(args, "url")}
              </div>
            )}

            {isSubagent && (
              <SubagentSummary args={args} meta={subagentMeta} running={subagentRunning} />
            )}

            {isDenied && (
              <div className="text-destructive text-xs">
                {deniedReason
                  ? t("tool_part.denied_with_reason", { reason: deniedReason })
                  : t("tool_part.denied")}
              </div>
            )}

            {hasMediaOutput && (
              <div className="flex flex-wrap gap-1">
                {tool.output.map((part, i) => {
                  if (part.type === "image") {
                    return (
                      <img
                        key={i}
                        alt=""
                        className="h-16 w-auto rounded border border-muted object-contain"
                        src={resolveFileUrl(part.url)}
                      />
                    );
                  }
                  if (part.type === "video") {
                    return (
                      <span
                        key={i}
                        className="inline-flex items-center gap-1 rounded border border-muted bg-muted/30 px-2 py-1 text-muted-foreground text-xs"
                      >
                        <Video className="h-3 w-3" />
                        video
                      </span>
                    );
                  }
                  if (part.type === "audio") {
                    return (
                      <span
                        key={i}
                        className="inline-flex items-center gap-1 rounded border border-muted bg-muted/30 px-2 py-1 text-muted-foreground text-xs"
                      >
                        <AudioLines className="h-3 w-3" />
                        audio
                      </span>
                    );
                  }
                  return null;
                })}
              </div>
            )}
          </div>
        )}
      </ControlledChainOfThoughtStep>

      <Drawer
        direction={isMobile ? "bottom" : "right"}
        open={drawerOpen}
        onOpenChange={setDrawerOpen}
      >
        <DrawerContent>
          <DrawerHeader>
            <DrawerTitle>{isSubagent ? subagentTitle : title}</DrawerTitle>
            <DrawerDescription>
              {t("tool_part.tool_name_label", { toolName: tool.toolName })}
            </DrawerDescription>
          </DrawerHeader>

          <div className="flex-1 min-h-0 space-y-4 overflow-y-auto px-4 pb-6">
            {tool.toolName === TOOL_NAMES.SEARCH_WEB && isExecuted ? (
              <SearchWebPreview args={args} content={outputContent} />
            ) : tool.toolName === TOOL_NAMES.SCRAPE_WEB && isExecuted ? (
              <ScrapeWebPreview content={outputContent} />
            ) : isSubagent ? (
              <SubagentPreview
                args={args}
                meta={subagentMeta}
                open={drawerOpen}
                running={subagentRunning}
                outputContent={outputContent}
              />
            ) : (
              <div className="space-y-3">
                <div>
                  <div className="mb-1 text-muted-foreground text-xs">
                    {t("tool_part.parameters")}
                  </div>
                  <JsonBlock value={args} />
                </div>
                {isExecuted && (
                  <div className="space-y-2">
                    <div className="mb-1 text-muted-foreground text-xs">
                      {t("tool_part.result")}
                    </div>
                    {tool.output.map((part, i) => {
                      if (part.type === "text") {
                        let parsed: unknown;
                        try {
                          parsed = JSON.parse(part.text);
                        } catch {
                          parsed = part.text;
                        }
                        return <JsonBlock key={i} value={parsed} />;
                      }
                      if (part.type === "image")
                        return <ImagePartRenderer key={i} url={part.url} />;
                      if (part.type === "video")
                        return <VideoPartRenderer key={i} url={part.url} />;
                      if (part.type === "audio")
                        return <AudioPartRenderer key={i} url={part.url} />;
                      return null;
                    })}
                  </div>
                )}
                {!isExecuted && (
                  <div className="text-muted-foreground text-sm">{t("tool_part.not_executed")}</div>
                )}
              </div>
            )}
          </div>
        </DrawerContent>
      </Drawer>
    </>
  );
}
