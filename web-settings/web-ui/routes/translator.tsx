import * as React from "react";

import { Link } from "react-router";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { ArrowLeft, Check, Copy, Eraser, History, Languages, Loader2, Square, Trash2 } from "lucide-react";
import { Streamdown, defaultUrlTransform, type StreamdownProps } from "streamdown";
import remarkGfm from "remark-gfm";

import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert";
import { Button } from "~/components/ui/button";
import { Input } from "~/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "~/components/ui/select";
import { Textarea } from "~/components/ui/textarea";
import { copyTextToClipboard } from "~/lib/clipboard";
import { extractErrorMessage } from "~/lib/error";
import { cn } from "~/lib/utils";
import { useSettingsStore } from "~/stores";
import {
  TRANSLATOR_MAX_TEXT_BYTES,
  TRANSLATOR_HISTORY_PAGE_SIZE,
  buildTranslatorRequest,
  clearTranslatorHistory,
  createRequestEpoch,
  deleteTranslatorHistoryItem,
  fetchTranslatorHistory,
  mergeTranslatorHistory,
  type TranslatorHistoryItem,
  isValidBcp47Tag,
  listTranslatorChatModels,
  readTranslateModeId,
  streamTranslate,
  translatorTextByteLength,
} from "@overlay/lib/translator";
import { SettingsSegmented } from "@overlay/components/settings/settings-ui";

const CUSTOM_LANGUAGE_VALUE = "__custom__";
const DEFAULT_MODEL_VALUE = "__default__";

/** 常用目标语言（值即 BCP-47 tag，直接发给后端）。 */
const COMMON_LANGUAGE_TAGS = [
  "en",
  "zh-CN",
  "zh-TW",
  "ja",
  "ko",
  "fr",
  "de",
  "es",
  "ru",
  "pt",
  "it",
  "ar",
  "th",
  "vi",
  "id",
] as const;

const REMARK_PLUGINS: StreamdownProps["remarkPlugins"] = [remarkGfm];
// 安全渲染：不挂 rehype-raw —— 模型输出的原始 HTML 节点会被丢弃不执行；
// urlTransform 走默认协议白名单（javascript: 等会被剥掉）。
const SAFE_URL_TRANSFORM = defaultUrlTransform;
const SAFE_COMPONENTS: StreamdownProps["components"] = {
  a: ({ href, children }) => (
    <a href={href} target="_blank" rel="noopener noreferrer">
      {children}
    </a>
  ),
};

type RunOutcome = "pending" | "done" | "error" | "cancelled";

function formatHistoryTime(createAt: number): string {
  return new Date(createAt).toLocaleString(undefined, {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export default function TranslatorPage() {
  const { t } = useTranslation("translator");

  const settings = useSettingsStore((state) => state.settings);
  const defaultModelId = readTranslateModeId(settings);
  const modelOptions = React.useMemo(() => listTranslatorChatModels(settings), [settings]);

  const [sourceText, setSourceText] = React.useState("");
  const [languageChoice, setLanguageChoice] = React.useState<string>("en");
  const [customLanguage, setCustomLanguage] = React.useState("");
  const [modelChoice, setModelChoice] = React.useState<string>(DEFAULT_MODEL_VALUE);
  const [result, setResult] = React.useState("");
  const [streamText, setStreamText] = React.useState<string | null>(null);
  const [streaming, setStreaming] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const [renderMarkdown, setRenderMarkdown] = React.useState(false);
  const [copied, setCopied] = React.useState(false);

  const abortRef = React.useRef<AbortController | null>(null);
  const epochRef = React.useRef(createRequestEpoch());
  const outcomeRef = React.useRef(new Map<number, RunOutcome>());
  const currentEpochRef = React.useRef(0);
  // 流式回调里的最新累计文本（state 在回调闭包里会 stale，必须用 ref）
  const latestStreamRef = React.useRef("");

  // 翻译历史：App 与 Web 共用，服务端只留最近 100 条
  const [history, setHistory] = React.useState<TranslatorHistoryItem[]>([]);
  const [historyTotal, setHistoryTotal] = React.useState(0);
  const [historyLoading, setHistoryLoading] = React.useState(false);
  const [historyError, setHistoryError] = React.useState<string | null>(null);
  const historyCountRef = React.useRef(0);
  historyCountRef.current = history.length;

  // 重新取前 N 条（N = 已加载条数，至少一页），翻译完成或删除后用来对齐服务端
  const reloadHistory = React.useCallback(async () => {
    setHistoryLoading(true);
    try {
      const limit = Math.min(100, Math.max(TRANSLATOR_HISTORY_PAGE_SIZE, historyCountRef.current));
      const page = await fetchTranslatorHistory(0, limit);
      setHistory(page.items);
      setHistoryTotal(page.total);
      setHistoryError(null);
    } catch (err) {
      setHistoryError(extractErrorMessage(err, t("history.load_failed")));
    } finally {
      setHistoryLoading(false);
    }
  }, [t]);

  const loadMoreHistory = React.useCallback(async () => {
    setHistoryLoading(true);
    try {
      const page = await fetchTranslatorHistory(historyCountRef.current);
      setHistory((current) => mergeTranslatorHistory(current, page.items));
      setHistoryTotal(page.total);
      setHistoryError(null);
    } catch (err) {
      setHistoryError(extractErrorMessage(err, t("history.load_failed")));
    } finally {
      setHistoryLoading(false);
    }
  }, [t]);

  React.useEffect(() => {
    void reloadHistory();
  }, [reloadHistory]);

  // 卸载/切页时取消在途请求（旧 epoch 一并失效，返回的事件全部丢弃）。
  React.useEffect(
    () => () => {
      epochRef.current.invalidate();
      abortRef.current?.abort();
    },
    [],
  );

  const targetLanguage =
    languageChoice === CUSTOM_LANGUAGE_VALUE ? customLanguage.trim() : languageChoice;
  const languageValid = isValidBcp47Tag(targetLanguage);
  const textBytes = React.useMemo(() => translatorTextByteLength(sourceText), [sourceText]);
  const textValid = sourceText.trim().length > 0 && textBytes <= TRANSLATOR_MAX_TEXT_BYTES;

  // 选中的模型：默认项解析为全局 translateModeId；不在可选列表时合成显示项提示不可用。
  const selectedModelId = modelChoice === DEFAULT_MODEL_VALUE ? defaultModelId : modelChoice;
  const effectiveModelOptions = React.useMemo(() => {
    if (!selectedModelId || modelOptions.some((option) => option.id === selectedModelId)) {
      return modelOptions;
    }
    return [
      ...modelOptions,
      {
        id: selectedModelId,
        modelId: selectedModelId,
        label: t("model.unavailable"),
        providerName: "",
      },
    ];
  }, [modelOptions, selectedModelId, t]);

  const hasAnyChatModel = modelOptions.length > 0;
  const canSubmit = !streaming && textValid && languageValid;

  const displayText = streaming ? (streamText ?? result) : result;
  const displayStreaming = streaming && streamText !== null;

  const runTranslation = React.useCallback(async () => {
    if (!canSubmit) return;

    // 新请求开始前先断掉旧请求；旧 epoch 失效后其事件全部被忽略，不会覆盖新结果。
    abortRef.current?.abort();
    const epoch = epochRef.current.next();
    currentEpochRef.current = epoch;
    const controller = new AbortController();
    abortRef.current = controller;
    outcomeRef.current.set(epoch, "pending");

    setStreaming(true);
    setStreamText(null);
    latestStreamRef.current = "";
    setError(null);
    setCopied(false);

    const request = buildTranslatorRequest(
      sourceText,
      targetLanguage,
      selectedModelId,
      defaultModelId,
    );

    await streamTranslate(
      request,
      {
        onEvent: (eventItem) => {
          if (!epochRef.current.isCurrent(epoch)) return;
          if (eventItem.type === "text") {
            latestStreamRef.current = eventItem.text;
            setStreamText(eventItem.text);
          } else if (eventItem.type === "done") {
            outcomeRef.current.set(epoch, "done");
            setResult(eventItem.text);
            setStreamText(null);
            // 服务端在 done 之前已写入历史
            void reloadHistory();
          } else if (eventItem.type === "error") {
            outcomeRef.current.set(epoch, "error");
            setError(eventItem.message || t("error.failed"));
            // 服务端 error 事件后连接正常关闭；保留已有结果与已收到的部分内容，不清空。
            if (latestStreamRef.current) setResult(latestStreamRef.current);
          }
        },
        onError: (err) => {
          if (!epochRef.current.isCurrent(epoch)) return;
          // 用户主动 cancel 时 fetch 抛 AbortError——此时 outcome 已标 cancelled，不报错。
          if (outcomeRef.current.get(epoch) === "cancelled") return;
          outcomeRef.current.set(epoch, "error");
          setError(extractErrorMessage(err, t("error.failed")));
        },
        onClose: () => {
          if (!epochRef.current.isCurrent(epoch)) return;
          const outcome = outcomeRef.current.get(epoch);
          // pending 结束（断开/关闭且没收到 done/error）且已有部分内容：收进结果
          if (outcome === "pending" && latestStreamRef.current) {
            setResult(latestStreamRef.current);
          }
          setStreamText(null);
          setStreaming(false);
          outcomeRef.current.delete(epoch);
        },
      },
      { signal: controller.signal },
    );
  }, [canSubmit, sourceText, targetLanguage, selectedModelId, defaultModelId, reloadHistory, t]);

  const cancelTranslation = React.useCallback(() => {
    outcomeRef.current.set(currentEpochRef.current, "cancelled");
    abortRef.current?.abort();
    if (latestStreamRef.current) setResult(latestStreamRef.current);
    setStreamText(null);
    setStreaming(false);
  }, []);

  const clearAll = React.useCallback(() => {
    abortRef.current?.abort();
    epochRef.current.invalidate();
    latestStreamRef.current = "";
    setStreaming(false);
    setStreamText(null);
    setSourceText("");
    setResult("");
    setError(null);
    setCopied(false);
  }, []);

  const restoreHistory = React.useCallback((item: TranslatorHistoryItem) => {
    abortRef.current?.abort();
    epochRef.current.invalidate();
    latestStreamRef.current = "";
    setStreaming(false);
    setStreamText(null);
    setError(null);
    setCopied(false);
    setSourceText(item.sourceText);
    setResult(item.translatedText);
    const common = (COMMON_LANGUAGE_TAGS as readonly string[]).includes(item.targetLanguage);
    if (common) {
      setLanguageChoice(item.targetLanguage);
    } else {
      setLanguageChoice(CUSTOM_LANGUAGE_VALUE);
      setCustomLanguage(item.targetLanguage);
    }
  }, []);

  const deleteHistoryItem = React.useCallback(
    async (item: TranslatorHistoryItem) => {
      try {
        await deleteTranslatorHistoryItem(item.id);
        setHistory((current) => current.filter((entry) => entry.id !== item.id));
        setHistoryTotal((total) => Math.max(0, total - 1));
      } catch (err) {
        toast.error(extractErrorMessage(err, t("history.delete_failed")));
        void reloadHistory();
      }
    },
    [reloadHistory, t],
  );

  const clearHistory = React.useCallback(async () => {
    if (!window.confirm(t("history.clear_confirm"))) return;
    try {
      await clearTranslatorHistory();
      setHistory([]);
      setHistoryTotal(0);
    } catch (err) {
      toast.error(extractErrorMessage(err, t("history.delete_failed")));
      void reloadHistory();
    }
  }, [reloadHistory, t]);

  const copyResult = React.useCallback(async () => {
    if (!displayText) return;
    try {
      await copyTextToClipboard(displayText);
      setCopied(true);
      toast.success(t("result.copied"));
      window.setTimeout(() => setCopied(false), 2000);
    } catch (err) {
      toast.error(extractErrorMessage(err, t("error.copy_failed")));
    }
  }, [displayText, t]);

  return (
    <div className="flex min-h-svh flex-col bg-background">
      <header className="sticky top-0 z-20 border-b bg-background/90 backdrop-blur">
        <div className="mx-auto flex w-full max-w-6xl items-center gap-3 px-4 py-3">
          <Button variant="ghost" size="sm" asChild>
            <Link to="/">
              <ArrowLeft className="size-4" />
              {t("nav.back_to_chat")}
            </Link>
          </Button>
          <Languages className="size-4 text-muted-foreground" />
          <h1 className="text-sm font-semibold">{t("nav.title")}</h1>
        </div>
      </header>

      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-6">
        <div className="space-y-6">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
            <div className="space-y-1">
              <h2 className="text-xl font-semibold">{t("page.title")}</h2>
              <p className="text-sm text-muted-foreground">{t("page.description")}</p>
            </div>
          </div>

          {/* 小屏单列、≥md 双栏 */}
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
            {/* ---------- 左栏：输入 ---------- */}
            <section className="flex flex-col gap-3">
              <label className="flex flex-col gap-1.5">
                <span className="text-sm font-medium">{t("input.label")}</span>
                <Textarea
                  value={sourceText}
                  onChange={(event) => setSourceText(event.target.value)}
                  placeholder={t("input.placeholder")}
                  rows={12}
                  className="min-h-48 resize-y font-normal"
                  aria-label={t("input.label")}
                />
              </label>
              <div className="flex items-center justify-between text-xs">
                <span
                  className={cn(
                    "text-muted-foreground",
                    textBytes > TRANSLATOR_MAX_TEXT_BYTES && "text-destructive",
                  )}
                >
                  {t("input.bytes", { count: textBytes, max: TRANSLATOR_MAX_TEXT_BYTES })}
                </span>
              </div>

              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-medium">{t("language.label")}</span>
                <Select value={languageChoice} onValueChange={setLanguageChoice}>
                  <SelectTrigger className="w-full" aria-label={t("language.label")}>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {COMMON_LANGUAGE_TAGS.map((tag) => (
                      <SelectItem key={tag} value={tag}>
                        {t(`language.tags.${tag.replaceAll("-", "_")}`)}
                      </SelectItem>
                    ))}
                    <SelectItem value={CUSTOM_LANGUAGE_VALUE}>
                      {t("language.custom")}
                    </SelectItem>
                  </SelectContent>
                </Select>
                {languageChoice === CUSTOM_LANGUAGE_VALUE ? (
                  <div className="flex flex-col gap-1">
                    <Input
                      value={customLanguage}
                      onChange={(event) => setCustomLanguage(event.target.value)}
                      placeholder={t("language.custom_placeholder")}
                      aria-label={t("language.custom")}
                      aria-invalid={!languageValid}
                    />
                    {!languageValid ? (
                      <span className="text-xs text-destructive" role="status">
                        {t("language.invalid")}
                      </span>
                    ) : null}
                  </div>
                ) : null}
              </div>

              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-medium">{t("model.label")}</span>
                <Select value={modelChoice} onValueChange={setModelChoice}>
                  <SelectTrigger className="w-full" aria-label={t("model.label")}>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value={DEFAULT_MODEL_VALUE}>
                      {t("model.default_option")}
                    </SelectItem>
                    {effectiveModelOptions.map((option) => (
                      <SelectItem key={option.id} value={option.id}>
                        {option.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <span className="text-xs text-muted-foreground">{t("model.hint")}</span>
                {!hasAnyChatModel ? (
                  <span className="text-xs text-destructive" role="status">
                    {t("error.no_models")}
                  </span>
                ) : null}
              </div>

              <div className="flex flex-wrap items-center gap-2 pt-1">
                {streaming ? (
                  <Button
                    type="button"
                    variant="destructive"
                    onClick={cancelTranslation}
                  >
                    <Square className="size-4" />
                    {t("action.cancel")}
                  </Button>
                ) : (
                  <Button type="button" onClick={() => void runTranslation()} disabled={!canSubmit}>
                    <Languages className="size-4" />
                    {t("action.translate")}
                  </Button>
                )}
                <Button type="button" variant="outline" onClick={clearAll}>
                  <Eraser className="size-4" />
                  {t("action.clear")}
                </Button>
              </div>
            </section>

            {/* ---------- 右栏：结果 ---------- */}
            <section className="flex flex-col gap-3">
              <div className="flex items-center justify-between gap-2">
                <span className="text-sm font-medium">{t("result.label")}</span>
                <div className="flex items-center gap-2">
                  <SettingsSegmented
                    value={renderMarkdown ? "markdown" : "plain"}
                    onChange={(value) => setRenderMarkdown(value === "markdown")}
                    options={[
                      { value: "plain", label: t("result.render_plain") },
                      { value: "markdown", label: t("result.render_markdown") },
                    ]}
                  />
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => void copyResult()}
                    disabled={!displayText}
                  >
                    {copied ? <Check className="size-4" /> : <Copy className="size-4" />}
                    {copied ? t("result.copied") : t("action.copy")}
                  </Button>
                </div>
              </div>

              <div
                className="relative min-h-48 flex-1 rounded-md border bg-card p-3"
                aria-live="polite"
                aria-busy={streaming}
              >
                {streaming ? (
                  <span className="absolute right-3 top-3 inline-flex items-center gap-1 text-xs text-muted-foreground" role="status">
                    <Loader2 className="size-3.5 animate-spin" />
                    {t("action.translating")}
                  </span>
                ) : null}
                {displayText ? (
                  renderMarkdown ? (
                    <Streamdown
                      className="markdown"
                      remarkPlugins={REMARK_PLUGINS}
                      urlTransform={SAFE_URL_TRANSFORM}
                      components={SAFE_COMPONENTS}
                      isAnimating={displayStreaming}
                    >
                      {displayText}
                    </Streamdown>
                  ) : (
                    <pre className="whitespace-pre-wrap break-words text-sm">{displayText}</pre>
                  )
                ) : (
                  <p className="text-sm text-muted-foreground">{t("result.empty")}</p>
                )}
              </div>

              {error ? (
                <Alert variant="destructive">
                  <AlertTitle>{t("error.title")}</AlertTitle>
                  <AlertDescription>{error}</AlertDescription>
                </Alert>
              ) : null}
            </section>
          </div>

          {/* ---------- 翻译历史 ---------- */}
          <section className="flex flex-col gap-3" aria-label={t("history.title")}>
            <div className="flex items-center justify-between gap-2">
              <span className="inline-flex items-center gap-2 text-sm font-medium">
                <History className="size-4 text-muted-foreground" />
                {t("history.title")}
                {historyTotal > 0 ? (
                  <span className="text-xs font-normal text-muted-foreground">{historyTotal}</span>
                ) : null}
              </span>
              {history.length > 0 ? (
                <Button type="button" variant="ghost" size="sm" onClick={() => void clearHistory()}>
                  {t("history.clear")}
                </Button>
              ) : null}
            </div>
            {historyError ? (
              <p className="text-sm text-destructive" role="status">
                {historyError}
              </p>
            ) : null}
            {history.length === 0 && !historyLoading && !historyError ? (
              <p className="text-sm text-muted-foreground">{t("history.empty")}</p>
            ) : null}
            <ul className="flex flex-col gap-2">
              {history.map((item) => (
                <li key={item.id} className="group relative rounded-md border bg-card">
                  <button
                    type="button"
                    className="flex w-full flex-col gap-1 p-3 pr-12 text-left hover:bg-accent/50"
                    onClick={() => restoreHistory(item)}
                    title={t("history.restore")}
                  >
                    <span className="line-clamp-2 text-sm text-muted-foreground">{item.sourceText}</span>
                    <span className="line-clamp-3 text-sm">{item.translatedText}</span>
                    <span className="text-xs text-muted-foreground">
                      {item.targetLanguage} · {formatHistoryTime(item.createAt)}
                    </span>
                  </button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    className="absolute right-2 top-2"
                    aria-label={t("history.delete")}
                    title={t("history.delete")}
                    onClick={() => void deleteHistoryItem(item)}
                  >
                    <Trash2 className="size-4" />
                  </Button>
                </li>
              ))}
            </ul>
            {history.length < historyTotal ? (
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="self-center"
                disabled={historyLoading}
                onClick={() => void loadMoreHistory()}
              >
                {historyLoading ? <Loader2 className="size-4 animate-spin" /> : null}
                {t("history.load_more")}
              </Button>
            ) : null}
          </section>
        </div>
      </main>
    </div>
  );
}
