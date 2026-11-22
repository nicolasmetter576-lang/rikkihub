import * as React from "react";

import { Minus, Pencil, Plus, X } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "~/components/ui/dialog";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "~/components/ui/select";
import { Switch } from "~/components/ui/switch";
import { Textarea } from "~/components/ui/textarea";
import api, { ApiError } from "~/services/api";
import type {
  AutoCompressMode,
  CarriedOriginalEditDto,
  CarriedOriginalPreviewDto,
  CarryOriginalsMode,
  CompressEstimateDto,
  CompressStateDto,
  CompressStateResponse,
  ConversationCompressionDto,
  MessageDto,
  MessageNodeDto,
} from "~/types";
import {
  fetchCompressEstimate,
  fetchCompressionConfig,
  templateIds,
  templateName,
  updateCompressionConfig,
  updateConversationCompression,
  type CompressionConfig,
} from "@overlay/lib/compression";

// Same bounds as the app dialog and the server (CompressRequest).
export const COMPRESS_MAX_ADDITIONAL_PROMPT = 2_000;
export const COMPRESS_MAX_KEEP_RECENT = 100_000;
export const COMPRESS_MIN_CARRY_COUNT = 1;
export const COMPRESS_MAX_CARRY_COUNT = 50;

/** Stepper arithmetic for "keep recent messages": never below 0, never above the server's bound. */
export function stepKeepRecent(value: number, delta: number): number {
  return Math.min(COMPRESS_MAX_KEEP_RECENT, Math.max(0, value + delta));
}

export function stepCarryCount(value: number, delta: number): number {
  return Math.min(COMPRESS_MAX_CARRY_COUNT, Math.max(COMPRESS_MIN_CARRY_COUNT, value + delta));
}

/** Translation key for a compression error code (start refusal or final failure). */
export function compressErrorKey(code: string | null | undefined): string {
  switch (code) {
    case "CONVERSATION_BUSY":
      return "conversations.compress.error_busy";
    case "ALREADY_COMPRESSING":
      return "conversations.compress.error_already";
    case "NOTHING_TO_COMPRESS":
      return "conversations.compress.error_nothing";
    case "NOT_ENOUGH_MESSAGES":
      return "conversations.compress.error_not_enough";
    case "PENDING_TOOLS":
      return "conversations.compress.error_pending_tools";
    case "TOOL_CALL_RESPONSE":
      return "conversations.compress.error_tool_call";
    case "REQUEST_LIMIT_EXCEEDED":
      return "conversations.compress.error_request_limit";
    case "REQUEST_TIMEOUT":
      return "conversations.compress.error_timeout";
    case "CONVERSATION_CHANGED":
      return "conversations.compress.error_changed";
    case "CLI_CONVERSATION":
      return "conversations.compress.error_cli";
    default:
      return "conversations.compress.error_failed";
  }
}

export function formatCompressResultMeta(
  state: CompressStateDto | null | undefined,
  t: (key: string, options?: Record<string, unknown>) => string,
): string | null {
  if (!state || !state.requestCount || state.requestCount <= 0) return null;
  const parts: string[] = [t("conversations.compress.result_summary", { count: state.requestCount })];
  if (state.chunked) parts.push(t("conversations.compress.result_chunked"));
  if (state.cacheBypassed) parts.push(t("conversations.compress.result_no_cache"));
  if (state.carriedCount && state.carriedCount > 0) {
    parts.push(t("conversations.compress.result_carried", { count: state.carriedCount }));
  }
  const totalTokens = state.totalUsage?.totalTokens;
  if (totalTokens && totalTokens > 0) {
    parts.push(t("conversations.compress.result_usage", { tokens: totalTokens.toLocaleString() }));
  }
  return parts.join(" · ");
}

interface CompressDialogProps {
  conversationId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Context used by the latest reply; null when unknown or already compressed after it. */
  contextTokens?: number | null;
  /** Context window of the conversation's chat model. */
  contextLength?: number | null;
  /** The conversation's message nodes, for the local preview of what gets summarized. */
  nodes?: MessageNodeDto[];
  /** The conversation's own compression settings (null = follow global). */
  override?: ConversationCompressionDto | null;
}

const FOLLOW = "__follow__";

/**
 * Messages that would be summarized:
 * - Normal: after the last checkpoint, minus the kept recent ones.
 * - fromOriginal: all non-checkpoint messages up to the cut point (cut must stay after the last checkpoint).
 */
export function compressionPreviewRange(
  nodes: MessageNodeDto[],
  keepRecent: number,
  fromOriginal = false,
): MessageDto[] {
  const messages = nodes.map((node) => node.messages[node.selectIndex] ?? node.messages[0]);
  let checkpoint = -1;
  messages.forEach((message, index) => {
    if (message?.isContextCheckpoint) checkpoint = index;
  });
  const end = messages.length - Math.max(0, keepRecent);
  if (!fromOriginal) {
    if (end <= checkpoint + 1) return [];
    return messages.slice(checkpoint + 1, end).filter((message): message is MessageDto => Boolean(message));
  }
  if (end <= 0 || end <= checkpoint) return [];
  return messages
    .slice(0, end)
    .filter((message): message is MessageDto => Boolean(message) && !message.isContextCheckpoint);
}

/**
 * Why there is nothing to compress, as a translation key, or null when there is something.
 * Same rule the server rejects with: no new messages, or all of them inside the kept tail.
 */
export function compressBlockedKey(
  nodes: MessageNodeDto[],
  keepRecent: number,
  fromOriginal = false,
): string | null {
  if (compressionPreviewRange(nodes, keepRecent, fromOriginal).length > 0) return null;
  return keepRecent > 0 && compressionPreviewRange(nodes, 0, fromOriginal).length > 0
    ? "conversations.compress.error_not_enough"
    : "conversations.compress.error_nothing";
}

/**
 * Starts a background compression; progress and the result arrive over the conversation SSE.
 * The server inserts a summary checkpoint at the end; nothing is deleted.
 */
export function CompressDialog({
  conversationId,
  open,
  onOpenChange,
  contextTokens,
  contextLength,
  nodes = [],
  override = null,
}: CompressDialogProps) {
  const { t } = useTranslation("page");
  const hasCheckpoint = React.useMemo(
    () => nodes.some((node) => (node.messages[node.selectIndex] ?? node.messages[0])?.isContextCheckpoint),
    [nodes],
  );
  const [additionalPrompt, setAdditionalPrompt] = React.useState("");
  // 0 compresses everything, which keeps the request identical to the chat's and reuses its cache.
  const [keepRecent, setKeepRecent] = React.useState(0);
  const [starting, setStarting] = React.useState(false);
  const [config, setConfig] = React.useState<CompressionConfig | null>(null);
  const [templateId, setTemplateId] = React.useState<string | null>(null);
  const [review, setReview] = React.useState(false);
  const [createNewConversation, setCreateNewConversation] = React.useState(false);
  const [fromOriginal, setFromOriginal] = React.useState(false);
  const [carryMode, setCarryMode] = React.useState<CarryOriginalsMode>("off");
  const [carryCount, setCarryCount] = React.useState(3);
  const [carryIncludeReplies, setCarryIncludeReplies] = React.useState(false);
  const [carryExcludedNodeIds, setCarryExcludedNodeIds] = React.useState<string[]>([]);
  const [carryEdits, setCarryEdits] = React.useState<Record<string, CarriedOriginalEditDto>>({});
  const [editingCarried, setEditingCarried] = React.useState<CarriedOriginalPreviewDto | null>(null);
  const [estimate, setEstimate] = React.useState<CompressEstimateDto | null>(null);
  const [showPreview, setShowPreview] = React.useState(false);
  const nothingKey = compressBlockedKey(nodes, keepRecent, fromOriginal);

  React.useEffect(() => {
    if (!open) return;
    let alive = true;
    setFromOriginal(false);
    setCreateNewConversation(false);
    setCarryExcludedNodeIds([]);
    setCarryEdits({});
    fetchCompressionConfig()
      .then((value) => {
        if (!alive) return;
        setConfig(value);
        setReview(value.reviewBeforeApply);
        setCarryMode(value.carryOriginalsMode);
        setCarryCount(value.carryOriginalsCount);
        setCarryIncludeReplies(value.carryIncludeReplies);
        const conversationTemplate = override?.templateId && templateIds(value).includes(override.templateId)
          ? override.templateId
          : null;
        setTemplateId(conversationTemplate ?? value.templateId);
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [open, override?.templateId]);

  React.useEffect(() => {
    if (!open) return;
    let alive = true;
    const timer = setTimeout(() => {
      fetchCompressEstimate(conversationId, {
        additionalPrompt,
        keepRecent,
        templateId,
        fromOriginal,
        carryMode,
        carryCount,
        carryIncludeReplies,
        carryExcludedNodeIds,
        carryEdits,
      })
        .then((value) => { if (alive) setEstimate(value); })
        .catch(() => { if (alive) setEstimate(null); });
    }, 150);
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [
    open,
    conversationId,
    keepRecent,
    nodes.length,
    fromOriginal,
    carryMode,
    carryCount,
    carryIncludeReplies,
    carryExcludedNodeIds,
    carryEdits,
    templateId,
    additionalPrompt,
  ]);

  const start = async () => {
    setStarting(true);
    try {
      if (config && review !== config.reviewBeforeApply) {
        await updateCompressionConfig({ reviewBeforeApply: review }).catch(() => undefined);
      }
      await api.post<CompressStateResponse>(`conversations/${conversationId}/compress`, {
        additionalPrompt,
        keepRecent,
        templateId,
        review,
        fromOriginal,
        createNewConversation,
        carryMode,
        carryCount,
        carryIncludeReplies,
        carryExcludedNodeIds,
        carryEdits,
      });
      onOpenChange(false);
    } catch (error) {
      const code = error instanceof ApiError ? error.errorCode : undefined;
      toast.error(t(compressErrorKey(code)), {
        description: error instanceof Error && !code ? error.message : undefined,
      });
    } finally {
      setStarting(false);
    }
  };

  const saveOverride = (next: ConversationCompressionDto) => {
    updateConversationCompression(conversationId, next).catch((error) => {
      toast.error(error instanceof Error ? error.message : t("conversations.compress.error_failed"));
    });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] max-w-lg overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{t("conversations.compress.title")}</DialogTitle>
          <DialogDescription>{t("conversations.compress.description")}</DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          {contextTokens != null && (estimate?.budgetTokens || contextLength) ? (
            // Relative to the input budget once known, like the estimate below and the usage hint.
            <ContextUsage
              tokens={contextTokens}
              length={estimate?.budgetTokens || contextLength!}
              label={t(estimate?.budgetTokens ? "conversations.compress.context_usage_budget" : "conversations.compress.context_usage")}
            />
          ) : null}

          {config && templateId ? (
            <LabeledSelect
              label={t("conversations.compress.template")}
              value={templateId}
              onValueChange={setTemplateId}
              options={templateIds(config).map((id) => ({ value: id, label: templateName(config, id, t) }))}
              testId="compress-template"
            />
          ) : null}

          <label className="block space-y-1.5">
            <span className="text-sm font-medium">
              {t("conversations.compress.additional_prompt")}
            </span>
            <Textarea
              value={additionalPrompt}
              maxLength={COMPRESS_MAX_ADDITIONAL_PROMPT}
              onChange={(event) => setAdditionalPrompt(event.target.value)}
              placeholder={t("conversations.compress.additional_prompt_hint")}
              rows={3}
            />
          </label>

          <div className="space-y-1.5">
            <div className="flex items-center gap-2">
              <span className="flex-1 text-sm font-medium">
                {t("conversations.compress.keep_recent")}
              </span>
              <Button
                type="button"
                variant="outline"
                size="icon-sm"
                aria-label={t("conversations.compress.keep_recent_decrease")}
                disabled={keepRecent <= 0}
                onClick={() => setKeepRecent((value) => stepKeepRecent(value, -1))}
              >
                <Minus className="size-4" />
              </Button>
              <span className="min-w-10 text-center text-sm font-medium tabular-nums" data-keep-recent>
                {keepRecent}
              </span>
              <Button
                type="button"
                variant="outline"
                size="icon-sm"
                aria-label={t("conversations.compress.keep_recent_increase")}
                disabled={keepRecent >= COMPRESS_MAX_KEEP_RECENT}
                onClick={() => setKeepRecent((value) => stepKeepRecent(value, 1))}
              >
                <Plus className="size-4" />
              </Button>
            </div>
            <p className="text-xs text-muted-foreground">
              {t("conversations.compress.keep_recent_desc")}
            </p>
          </div>

          {hasCheckpoint ? (
            <div className="space-y-1">
              <div className="flex items-center justify-between gap-4">
                <span className="text-sm font-medium">{t("conversations.compress.from_original")}</span>
                <Switch
                  checked={fromOriginal}
                  onCheckedChange={setFromOriginal}
                  data-testid="compress-from-original"
                />
              </div>
              <p className="text-xs text-muted-foreground">{t("conversations.compress.from_original_desc")}</p>
            </div>
          ) : null}

          <div className="space-y-2.5 border-t pt-3">
            <LabeledSelect
              label={t("conversations.compress.carry_title")}
              value={carryMode}
              onValueChange={(value) => setCarryMode(value as CarryOriginalsMode)}
              options={(["off", "recent", "auto"] as const).map((mode) => ({
                value: mode,
                label: t(`settings:compression.carry_modes.${mode}`),
              }))}
              testId="compress-carry-mode"
            />
            {carryMode === "recent" ? (
              <div className="flex items-center gap-2">
                <span className="flex-1 text-sm">{t("conversations.compress.carry_count")}</span>
                <Button
                  type="button"
                  variant="outline"
                  size="icon-sm"
                  disabled={carryCount <= COMPRESS_MIN_CARRY_COUNT}
                  onClick={() => setCarryCount((value) => stepCarryCount(value, -1))}
                >
                  <Minus className="size-4" />
                </Button>
                <span className="min-w-8 text-center text-sm font-medium tabular-nums">{carryCount}</span>
                <Button
                  type="button"
                  variant="outline"
                  size="icon-sm"
                  disabled={carryCount >= COMPRESS_MAX_CARRY_COUNT}
                  onClick={() => setCarryCount((value) => stepCarryCount(value, 1))}
                >
                  <Plus className="size-4" />
                </Button>
              </div>
            ) : null}
            {carryMode !== "off" ? (
              <>
                <div className="flex items-center justify-between gap-4">
                  <span className="text-sm">{t("conversations.compress.carry_replies")}</span>
                  <Switch
                    checked={carryIncludeReplies}
                    onCheckedChange={setCarryIncludeReplies}
                    data-testid="compress-carry-replies"
                  />
                </div>
                {!nothingKey && estimate ? (
                  <div className="space-y-1.5">
                    <div className="flex items-center justify-between gap-2 text-xs font-medium">
                      <span>
                        {t("conversations.compress.carry_list_title", {
                          count: estimate.carriedCount ?? 0,
                          tokens: (estimate.carriedTokens ?? 0).toLocaleString(),
                        })}
                      </span>
                      {(estimate.carriedExcludedCount ?? 0) > 0 ? (
                        <Button
                          type="button"
                          variant="ghost"
                          size="sm"
                          className="h-6 px-2 text-xs"
                          onClick={() => setCarryExcludedNodeIds([])}
                          data-testid="compress-carry-restore"
                        >
                          {t("conversations.compress.carry_restore", { count: estimate.carriedExcludedCount ?? 0 })}
                        </Button>
                      ) : null}
                    </div>
                    {estimate.carriedOverBudget ? (
                      <p className="text-xs text-destructive">{t("conversations.compress.carry_over_budget")}</p>
                    ) : null}
                    {(estimate.carriedSkippedForBudget ?? 0) > 0 ? (
                      <p className="text-xs text-muted-foreground">
                        {t("conversations.compress.carry_skipped_budget", { count: estimate.carriedSkippedForBudget })}
                      </p>
                    ) : null}
                    {(estimate.carriedItems ?? []).length === 0 ? (
                      <p className="text-xs text-muted-foreground">{t("conversations.compress.carry_empty")}</p>
                    ) : (
                      <div className="max-h-48 space-y-1.5 overflow-y-auto" data-testid="compress-carry-list">
                        {(estimate.carriedItems ?? []).map((item, idx) => (
                          <div
                            key={item.nodeId}
                            className="flex items-center gap-2 rounded-md border bg-muted/40 px-2.5 py-1.5 text-xs"
                          >
                            <div className="min-w-0 flex-1 space-y-0.5">
                              <p className="line-clamp-2 break-words">
                                {idx + 1}. {item.userText.replace(/\s+/g, " ")}
                              </p>
                              <p className="text-[11px] text-muted-foreground">
                                {[
                                  item.replyText ? t("conversations.compress.carry_with_reply") : null,
                                  item.edited ? t("conversations.compress.carry_edited") : null,
                                  `~${item.tokens} tok`,
                                ]
                                  .filter(Boolean)
                                  .join(" · ")}
                              </p>
                            </div>
                            <Button
                              type="button"
                              variant="ghost"
                              size="icon-sm"
                              aria-label={t("conversations.compress.carry_edit_title")}
                              onClick={() => setEditingCarried(item)}
                            >
                              <Pencil className="size-3.5" />
                            </Button>
                            <Button
                              type="button"
                              variant="ghost"
                              size="icon-sm"
                              aria-label={t("conversations.compress.carry_remove")}
                              onClick={() => setCarryExcludedNodeIds((prev) => [...prev, item.nodeId])}
                            >
                              <X className="size-3.5" />
                            </Button>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                ) : null}
              </>
            ) : null}
          </div>

          {nothingKey ? (
            <p className="text-sm text-destructive" data-testid="compress-nothing">{t(nothingKey)}</p>
          ) : (
            <>
              {estimate ? <EstimatePanel estimate={estimate} /> : null}

              <div className="space-y-1.5">
                <Button type="button" variant="ghost" size="sm" onClick={() => setShowPreview((value) => !value)}
                  data-testid="compress-preview-toggle">
                  {t("conversations.compress.preview")}
                </Button>
                {showPreview ? (
                  <CompressionPreview messages={compressionPreviewRange(nodes, keepRecent, fromOriginal)} />
                ) : null}
              </div>
            </>
          )}

          <div className="flex items-center justify-between gap-4">
            <span className="text-sm">{t("conversations.compress.review_toggle")}</span>
            <Switch checked={review} onCheckedChange={setReview} data-testid="compress-review-toggle" />
          </div>

          <div className="flex items-start justify-between gap-4">
            <div className="space-y-0.5">
              <span className="text-sm">{t("conversations.compress.new_conversation_toggle")}</span>
              <p className="text-xs text-muted-foreground">{t("conversations.compress.new_conversation_hint")}</p>
            </div>
            <Switch
              checked={createNewConversation}
              onCheckedChange={setCreateNewConversation}
              data-testid="compress-new-conversation-toggle"
            />
          </div>

          {config ? (
            <div className="space-y-2 border-t pt-3">
              <p className="text-sm font-medium">{t("conversations.compress.conversation_settings")}</p>
              <LabeledSelect
                label={t("conversations.compress.mode")}
                value={override?.mode ?? FOLLOW}
                onValueChange={(value) =>
                  saveOverride({ ...override, mode: value === FOLLOW ? null : (value as AutoCompressMode) })}
                options={[
                  {
                    value: FOLLOW,
                    label: t("conversations.compress.follow_global", { value: t(`settings:compression.modes.${config.mode}`) }),
                  },
                  ...(["off", "notify", "auto"] as const).map((mode) => ({
                    value: mode,
                    label: t(`settings:compression.modes.${mode}`),
                  })),
                ]}
                testId="compress-conversation-mode"
              />
              <LabeledSelect
                label={t("conversations.compress.default_template")}
                value={override?.templateId && templateIds(config).includes(override.templateId) ? override.templateId : FOLLOW}
                onValueChange={(value) => saveOverride({ ...override, templateId: value === FOLLOW ? null : value })}
                options={[
                  {
                    value: FOLLOW,
                    label: t("conversations.compress.follow_global", { value: templateName(config, config.templateId, t) }),
                  },
                  ...templateIds(config).map((id) => ({ value: id, label: templateName(config, id, t) })),
                ]}
                testId="compress-conversation-template"
              />
            </div>
          ) : null}

          <p className="text-xs text-muted-foreground">{t("conversations.compress.checkpoint_info")}</p>
          <p className="text-xs text-muted-foreground">
            {t("conversations.compress.background_hint")}
          </p>
        </div>

        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
            {t("conversations.compress.cancel")}
          </Button>
          <Button type="button" disabled={starting || nothingKey != null} onClick={() => void start()} data-testid="compress-start">
            {starting ? t("conversations.compress.starting") : t("conversations.compress.start")}
          </Button>
        </DialogFooter>
      </DialogContent>

      {editingCarried ? (
        <EditCarriedDialog
          item={editingCarried}
          onSave={(userText, replyText) => {
            setCarryEdits((prev) => ({ ...prev, [editingCarried.nodeId]: { userText, replyText } }));
            setEditingCarried(null);
          }}
          onClose={() => setEditingCarried(null)}
        />
      ) : null}
    </Dialog>
  );
}

function EditCarriedDialog({
  item,
  onSave,
  onClose,
}: {
  item: CarriedOriginalPreviewDto;
  onSave: (userText: string, replyText: string | null) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation("page");
  const [userText, setUserText] = React.useState(item.userText);
  const [replyText, setReplyText] = React.useState(item.replyText ?? "");
  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose(); }}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{t("conversations.compress.carry_edit_title")}</DialogTitle>
          <DialogDescription>{t("conversations.compress.carry_edit_desc")}</DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          <label className="block space-y-1">
            <span className="text-xs font-medium">{t("conversations.compress.carry_user_label")}</span>
            <Textarea value={userText} onChange={(e) => setUserText(e.target.value)} rows={5} />
          </label>
          {item.replyText != null ? (
            <label className="block space-y-1">
              <span className="text-xs font-medium">{t("conversations.compress.carry_reply_label")}</span>
              <Textarea value={replyText} onChange={(e) => setReplyText(e.target.value)} rows={4} />
            </label>
          ) : null}
        </div>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>{t("conversations.compress.cancel")}</Button>
          <Button
            type="button"
            disabled={!userText.trim()}
            onClick={() => onSave(userText, item.replyText != null ? replyText : null)}
          >
            {t("conversations.compress.carry_edit_save")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** "Review before applying": the summary (with carried verbatim messages) can be edited; applying inserts it. */
export function CompressReviewDialog({
  conversationId,
  summary,
  compressState = null,
  open,
  onOpenChange,
}: {
  conversationId: string;
  summary: string;
  compressState?: CompressStateDto | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const { t } = useTranslation("page");
  const [text, setText] = React.useState(summary);
  const [busy, setBusy] = React.useState(false);
  const meta = formatCompressResultMeta(compressState, t);
  const resolve = async (value: string | null) => {
    setBusy(true);
    try {
      await api.post(`conversations/${conversationId}/compress/review`, { summary: value });
      onOpenChange(false);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t("conversations.compress.error_failed"));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>{t("conversations.compress.review_title")}</DialogTitle>
          <DialogDescription>{t("conversations.compress.review_desc")}</DialogDescription>
        </DialogHeader>
        {meta ? <p className="text-xs font-medium text-primary" data-testid="compress-review-meta">{meta}</p> : null}
        <Textarea value={text} onChange={(event) => setText(event.target.value)} rows={16}
          className="font-mono text-xs" data-testid="compress-review-text" />
        <DialogFooter>
          <Button type="button" variant="outline" disabled={busy} onClick={() => void resolve(null)}
            data-testid="compress-review-discard">
            {t("conversations.compress.review_discard")}
          </Button>
          <Button type="button" disabled={busy || !text.trim()} onClick={() => void resolve(text)}
            data-testid="compress-review-apply">
            {t(
              compressState?.createNewConversation
                ? "conversations.compress.review_apply_new"
                : "conversations.compress.review_apply",
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function LabeledSelect({
  label,
  value,
  onValueChange,
  options,
  testId,
}: {
  label: string;
  value: string;
  onValueChange: (value: string) => void;
  options: Array<{ value: string; label: string }>;
  testId: string;
}) {
  return (
    <div className="flex items-center justify-between gap-3">
      <span className="text-sm">{label}</span>
      <Select value={value} onValueChange={onValueChange}>
        <SelectTrigger className="w-48" data-testid={testId}>
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {options.map((option) => (
            <SelectItem key={option.value} value={option.value}>
              {option.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}

function EstimatePanel({ estimate }: { estimate: CompressEstimateDto }) {
  const { t } = useTranslation("page");
  const budget = estimate.budgetTokens;
  const carriedCount = estimate.carriedCount ?? 0;
  const maxRequests = estimate.maxChunkRequests ?? 8;
  const estimatedChunks = estimate.estimatedChunks ?? 1;
  return (
    <div className="space-y-1 rounded-lg bg-muted px-3 py-2 text-xs" data-testid="compress-estimate">
      <p className="text-sm font-medium">{t("conversations.compress.estimate_title")}</p>
      <p>
        {t("conversations.compress.estimate_summarized", {
          count: estimate.summarizedMessages,
          chars: estimate.summarizedChars.toLocaleString(),
        })}
      </p>
      <p>{t("conversations.compress.estimate_kept", { count: estimate.keptMessages })}</p>
      {carriedCount > 0 ? (
        <p>
          {t("conversations.compress.estimate_carried", {
            count: carriedCount,
            tokens: (estimate.carriedTokens ?? 0).toLocaleString(),
          })}
        </p>
      ) : null}
      <p>
        {t("conversations.compress.estimate_tokens", {
          before: estimate.beforeTokens.toLocaleString(),
          min: estimate.afterMinTokens.toLocaleString(),
          max: estimate.afterMaxTokens.toLocaleString(),
        })}
      </p>
      {budget ? (
        <p>
          {t("conversations.compress.estimate_percent", {
            before: Math.floor((estimate.beforeTokens * 100) / budget),
            after: Math.floor((estimate.afterMaxTokens * 100) / budget),
          })}
        </p>
      ) : null}
      {estimate.willChunk ? (
        <p className="text-destructive" data-testid="compress-estimate-chunk-warn">
          {estimatedChunks > maxRequests
            ? t("conversations.compress.estimate_chunk_limit_warn", { count: estimatedChunks, limit: maxRequests })
            : t("conversations.compress.estimate_chunked", { count: estimatedChunks, limit: maxRequests })}
        </p>
      ) : estimate.willBreakCache ? (
        <p className="text-destructive" data-testid="compress-estimate-cache-warn">
          {t("conversations.compress.estimate_cache_bypassed")}
        </p>
      ) : null}
      <p className="text-muted-foreground">
        {estimate.beforeExact ? t("conversations.compress.estimate_exact") : t("conversations.compress.estimate_local")}
      </p>
    </div>
  );
}

/** First and last two messages that would be summarized, with the rest folded into a count. */
function CompressionPreview({ messages }: { messages: MessageDto[] }) {
  const { t } = useTranslation("page");
  const folded = messages.length > 4;
  const shown = folded ? [...messages.slice(0, 2), ...messages.slice(-2)] : messages;
  return (
    <div className="space-y-0.5 text-xs" data-testid="compress-preview">
      {shown.map((message, index) => (
        <React.Fragment key={message.id}>
          {folded && index === 2 ? (
            <p className="text-muted-foreground">
              {t("conversations.compress.preview_more", { count: messages.length - 4 })}
            </p>
          ) : null}
          <p className="truncate">
            {message.role === "USER" ? "👤 " : "🤖 "}
            {messageText(message).replace(/\s+/g, " ").slice(0, 80)}
          </p>
        </React.Fragment>
      ))}
    </div>
  );
}

function messageText(message: MessageDto): string {
  return message.parts.map((part) => (part.type === "text" ? part.text : "")).join(" ");
}

/** Context usage bar, turning warning colours near the window like the app's ring. */
function ContextUsage({ tokens, length, label }: { tokens: number; length: number; label: string }) {
  const fraction = Math.min(1, Math.max(0, tokens / length));
  const tone = fraction >= 0.9 ? "bg-destructive" : fraction >= 0.7 ? "bg-amber-500" : "bg-primary";
  return (
    <div className="space-y-1.5" data-context-usage>
      <div className="flex justify-between text-xs text-muted-foreground">
        <span>{label}</span>
        <span>
          {tokens.toLocaleString()} / {length.toLocaleString()} · {Math.round(fraction * 100)}%
        </span>
      </div>
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted">
        <div className={`h-full ${tone}`} style={{ width: `${fraction * 100}%` }} />
      </div>
    </div>
  );
}
