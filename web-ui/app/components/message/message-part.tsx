import * as React from "react";
import { useTranslation } from "react-i18next";

import type { ChartDto, ChartSpecDto, ReasoningPart, ToolPart, UIMessagePart } from "~/types";

import { ChainOfThought } from "./chain-of-thought";
import { AudioPart } from "./parts/audio-part";
import { ChartPart } from "./parts/chart-part";
import { DocumentPart } from "./parts/document-part";
import { ImagePart } from "./parts/image-part";
import { ReasoningStepPart } from "./parts/reasoning-step-part";
import { TextPart } from "./parts/text-part";
import { ServerToolPart } from "./parts/server-tool-part";
import { ASK_USER_TOOL_NAME, AskUserToolPart, ToolPart as ToolStepPart } from "./parts/tool-part";
import { VideoPart } from "./parts/video-part";

type ThinkingStep =
  | {
      type: "reasoning";
      reasoning: ReasoningPart;
    }
  | {
      type: "tool";
      tool: ToolPart;
    };

type MessagePartBlock =
  | {
      type: "thinking";
      steps: ThinkingStep[];
    }
  | {
      type: "content";
      part: UIMessagePart;
      index: number;
    }
  | {
      /** A server-validated chart_display call, shown in place like the app's ChartBlock. */
      type: "chart";
      spec: ChartSpecDto;
      index: number;
    };

export function groupMessageParts(
  parts: UIMessagePart[],
  charts?: ChartDto[] | null,
): MessagePartBlock[] {
  const result: MessagePartBlock[] = [];
  const chartByIndex = new Map((charts ?? []).map((chart) => [chart.partIndex, chart.spec]));
  let currentThinkingSteps: ThinkingStep[] = [];

  const flushThinkingSteps = () => {
    if (currentThinkingSteps.length === 0) return;
    result.push({ type: "thinking", steps: currentThinkingSteps });
    currentThinkingSteps = [];
  };

  parts.forEach((part, index) => {
    if (part.type === "reasoning") {
      currentThinkingSteps.push({ type: "reasoning", reasoning: part });
      return;
    }

    if (part.type === "tool") {
      const spec = chartByIndex.get(index);
      if (spec) {
        flushThinkingSteps();
        result.push({ type: "chart", spec, index });
        return;
      }
      currentThinkingSteps.push({ type: "tool", tool: part });
      return;
    }

    flushThinkingSteps();
    result.push({ type: "content", part, index });
  });

  flushThinkingSteps();
  return result;
}

interface MessagePartsProps {
  parts: UIMessagePart[];
  /** Server-validated charts of this message (MessageDto.charts). */
  charts?: ChartDto[] | null;
  loading?: boolean;
  onToolApproval?: (
    toolCallId: string,
    approved: boolean,
    reason: string,
    answer?: string,
  ) => void | Promise<void>;
  onClickCitation?: (id: string) => void;
}

function renderContentPart(
  part: UIMessagePart,
  t: (key: string, options?: Record<string, unknown>) => string,
  loading?: boolean,
  onClickCitation?: (id: string) => void,
) {
  switch (part.type) {
    case "text":
      return <TextPart text={part.text} isAnimating={loading} onClickCitation={onClickCitation} />;
    case "image":
      return <ImagePart url={part.url} />;
    case "video":
      return <VideoPart url={part.url} />;
    case "audio":
      return <AudioPart url={part.url} />;
    case "document":
      return <DocumentPart url={part.url} fileName={part.fileName} mime={part.mime} />;
    case "server_tool":
      return <ServerToolPart part={part} />;
    case "reasoning":
      // Unreachable: groupMessageParts always folds reasoning into thinking blocks.
      return null;
    case "tool":
      return (
        <div className="text-xs text-muted-foreground">{t("message_parts.tool_step_hint")}</div>
      );
  }
}

export const MessageParts = React.memo(
  ({ parts, charts, loading = false, onToolApproval, onClickCitation }: MessagePartsProps) => {
    const { t } = useTranslation("message");
    const groupedParts = React.useMemo(() => groupMessageParts(parts, charts), [parts, charts]);

    return (
      <>
        {groupedParts.map((block, blockIndex) => {
          if (block.type === "thinking") {
            if (block.steps.length === 0) return null;

            const isReasoningOnlyBlock = block.steps.every((step) => step.type === "reasoning");
            const hasLoadingReasoning = block.steps.some(
              (step) => step.type === "reasoning" && step.reasoning.finishedAt == null,
            );
            const enableAdaptiveWidth = isReasoningOnlyBlock && !hasLoadingReasoning;

            return (
              <ChainOfThought
                key={`thinking-${blockIndex}`}
                className="my-1"
                collapsedAdaptiveWidth={enableAdaptiveWidth}
                collapseLabel={t("message_parts.collapse_thinking")}
                showMoreLabel={(hiddenCount) =>
                  t("message_parts.expand_thinking_steps", { count: hiddenCount })
                }
                steps={block.steps}
                renderStep={(step, stepIndex, { isFirst, isLast }) => {
                  if (step.type === "reasoning") {
                    const stepKey = step.reasoning.createdAt ?? `${blockIndex}-${stepIndex}`;
                    return (
                      <ReasoningStepPart
                        key={stepKey}
                        reasoning={step.reasoning}
                        collapsedAdaptiveWidth={enableAdaptiveWidth}
                        isFirst={isFirst}
                        isLast={isLast}
                      />
                    );
                  }

                  const stepKey = step.tool.toolCallId || `${blockIndex}-${stepIndex}`;
                  if (step.tool.toolName === ASK_USER_TOOL_NAME) {
                    return (
                      <AskUserToolPart
                        key={stepKey}
                        tool={step.tool}
                        loading={loading && step.tool.output.length === 0}
                        onToolApproval={onToolApproval}
                        isFirst={isFirst}
                        isLast={isLast}
                      />
                    );
                  }

                  return (
                    <ToolStepPart
                      key={stepKey}
                      tool={step.tool}
                      loading={loading && step.tool.output.length === 0}
                      onToolApproval={onToolApproval}
                      isFirst={isFirst}
                      isLast={isLast}
                    />
                  );
                }}
              />
            );
          }

          if (block.type === "chart") {
            return <ChartPart key={`chart-${block.index}`} spec={block.spec} />;
          }

          return (
            <React.Fragment key={`content-${block.index}`}>
              {renderContentPart(block.part, t, loading, onClickCitation)}
            </React.Fragment>
          );
        })}
      </>
    );
  },
);
