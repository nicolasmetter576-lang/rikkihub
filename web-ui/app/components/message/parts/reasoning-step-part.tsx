import * as React from "react";
import { Sparkles } from "lucide-react";
import { useTranslation } from "react-i18next";

import Markdown from "~/components/markdown/markdown";
import type { ReasoningPart as UIReasoningPart } from "~/types";
import Think from "~/assets/think.svg?react";
import { extractThinkingTitle, serverNow } from "~/lib/utils";

import { useSettingsStore } from "~/stores";

import { ControlledChainOfThoughtStep } from "../chain-of-thought";

interface ReasoningStepPartProps {
  reasoning: UIReasoningPart;
  collapsedAdaptiveWidth?: boolean;
  isFirst?: boolean;
  isLast?: boolean;
}

enum ReasoningCardState {
  Collapsed = "collapsed",
  Preview = "preview",
  Expanded = "expanded",
}

function formatDuration(createdAt?: string, finishedAt?: string | null): number | null {
  if (!createdAt) return null;

  const start = Date.parse(createdAt);
  if (Number.isNaN(start)) return null;

  const end = finishedAt ? Date.parse(finishedAt) : serverNow();
  if (Number.isNaN(end)) return null;

  const seconds = Math.max((end - start) / 1000, 0);
  if (seconds <= 0) return null;

  return Math.round(seconds * 10) / 10;
}

// The ticking duration lives in these small components so the 100ms interval only
// re-renders the label/extra text instead of the whole reasoning step (markdown included).
function useReasoningDuration(
  createdAt: string | undefined,
  finishedAt: string | null | undefined,
  loading: boolean,
): number | null {
  const [duration, setDuration] = React.useState<number | null>(() =>
    formatDuration(createdAt, finishedAt),
  );

  React.useEffect(() => {
    setDuration(formatDuration(createdAt, finishedAt));
    if (!loading) return;
    const id = setInterval(() => {
      setDuration(formatDuration(createdAt, finishedAt));
    }, 100);
    return () => clearInterval(id);
  }, [loading, createdAt, finishedAt]);

  return duration;
}

function ReasoningStepLabel({
  createdAt,
  finishedAt,
  loading,
  thinkingTitle,
}: {
  createdAt?: string;
  finishedAt?: string | null;
  loading: boolean;
  thinkingTitle: string | null;
}) {
  const { t } = useTranslation("message");
  const duration = useReasoningDuration(createdAt, finishedAt, loading);
  const showThinkingTitle = loading && thinkingTitle != null;

  return (
    <span className="text-foreground text-xs font-medium">
      {showThinkingTitle
        ? thinkingTitle
        : duration !== null
          ? t("message_parts.thinking_seconds", { seconds: duration.toFixed(1) })
          : t("message_parts.deep_thinking")}
    </span>
  );
}

function ReasoningStepDurationExtra({
  createdAt,
  finishedAt,
  loading,
  show,
}: {
  createdAt?: string;
  finishedAt?: string | null;
  loading: boolean;
  show: boolean;
}) {
  const duration = useReasoningDuration(createdAt, finishedAt, loading);
  if (!show || duration === null) return null;
  return <span className="text-muted-foreground text-xs">{duration.toFixed(1)}s</span>;
}

export function ReasoningStepPart({
  reasoning,
  collapsedAdaptiveWidth = false,
  isFirst,
  isLast,
}: ReasoningStepPartProps) {
  const loading = reasoning.finishedAt == null;
  const displaySetting = useSettingsStore((state) => state.settings?.displaySetting);
  const [expandState, setExpandState] = React.useState<ReasoningCardState>(
    ReasoningCardState.Collapsed,
  );
  const contentRef = React.useRef<HTMLDivElement>(null);
  const thinkingTitle = React.useMemo(
    () => extractThinkingTitle(reasoning.reasoning),
    [reasoning.reasoning],
  );
  const showThinkingTitle = loading && thinkingTitle != null;

  React.useEffect(() => {
    if (loading) {
      if (displaySetting?.showThinkingContent) {
        setExpandState((state) =>
          state === ReasoningCardState.Collapsed ? ReasoningCardState.Preview : state,
        );
      }
      return;
    }

    setExpandState((state) => {
      if (state === ReasoningCardState.Collapsed) return state;
      return (displaySetting?.autoCloseThinking ?? true)
        ? ReasoningCardState.Collapsed
        : ReasoningCardState.Expanded;
    });
  }, [
    loading,
    reasoning.reasoning,
    displaySetting?.showThinkingContent,
    displaySetting?.autoCloseThinking,
  ]);

  React.useEffect(() => {
    if (loading && expandState === ReasoningCardState.Preview && contentRef.current) {
      contentRef.current.scrollTop = contentRef.current.scrollHeight;
    }
  }, [loading, expandState, reasoning.reasoning]);

  const onExpandedChange = (nextExpanded: boolean) => {
    if (loading) {
      setExpandState(nextExpanded ? ReasoningCardState.Expanded : ReasoningCardState.Preview);
      return;
    }

    setExpandState(nextExpanded ? ReasoningCardState.Expanded : ReasoningCardState.Collapsed);
  };

  const preview = expandState === ReasoningCardState.Preview;

  return (
    <div data-part="reasoning" data-reasoning-loading={loading || undefined}>
      <ControlledChainOfThoughtStep
        expanded={expandState === ReasoningCardState.Expanded}
        onExpandedChange={onExpandedChange}
        collapsedAdaptiveWidth={collapsedAdaptiveWidth}
        isFirst={isFirst}
        isLast={isLast}
        icon={
          loading ? (
            <Sparkles className="h-4 w-4 animate-pulse text-primary" />
          ) : (
            <Think className="h-4 w-4 text-primary" />
          )
        }
        label={
          <ReasoningStepLabel
            createdAt={reasoning.createdAt}
            finishedAt={reasoning.finishedAt}
            loading={loading}
            thinkingTitle={thinkingTitle}
          />
        }
        extra={
          showThinkingTitle ? (
            <ReasoningStepDurationExtra
              createdAt={reasoning.createdAt}
              finishedAt={reasoning.finishedAt}
              loading={loading}
              show={showThinkingTitle}
            />
          ) : undefined
        }
        contentVisible={expandState !== ReasoningCardState.Collapsed}
      >
        <div
          ref={contentRef}
          className={preview ? "styled-scrollbar relative max-h-24 overflow-y-auto" : undefined}
        >
          <Markdown content={reasoning.reasoning} className="text-xs" isAnimating={loading} />
        </div>
      </ControlledChainOfThoughtStep>
    </div>
  );
}
