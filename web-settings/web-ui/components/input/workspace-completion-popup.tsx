import * as React from "react";

import { File, Folder, LoaderCircle } from "lucide-react";
import { useTranslation } from "react-i18next";

import { cn } from "~/lib/utils";
import type { WorkspaceCompletionViewState } from "@overlay/hooks/use-workspace-file-completion";

export interface WorkspaceCompletionPopupProps {
  state: WorkspaceCompletionViewState;
  onPick: (index: number) => void;
  onHighlight: (index: number) => void;
}

/**
 * `@` 工作区文件补全弹层。渲染在输入框容器内（absolute bottom-full）。
 *
 * - 候选项 onPointerDown preventDefault：点击不抢 textarea focus、不丢光标
 * - loading / empty / error 态只显示提示行，不消费按键、不阻碍发送
 */
export function WorkspaceCompletionPopup({
  state,
  onPick,
  onHighlight,
}: WorkspaceCompletionPopupProps) {
  const { t } = useTranslation("input");
  const listRef = React.useRef<HTMLUListElement | null>(null);

  // 选中项滚进可视区（键盘循环选择时）
  React.useEffect(() => {
    if (state.status !== "ready") return;
    listRef.current
      ?.querySelector(`[data-index="${state.selectedIndex}"]`)
      ?.scrollIntoView({ block: "nearest" });
  }, [state.selectedIndex, state.status]);

  if (state.status === "hidden") return null;

  return (
    <div
      className="absolute inset-x-1 bottom-full z-30 mb-1 overflow-hidden rounded-xl border border-border bg-popover text-popover-foreground shadow-md"
      // 任何点击都不让 textarea 失焦
      onPointerDown={(event) => event.preventDefault()}
    >
      {state.status === "loading" ? (
        <div className="flex items-center gap-2 px-3 py-2 text-xs text-muted-foreground">
          <LoaderCircle className="size-3.5 animate-spin" />
          {t("workspace_completion.loading")}
        </div>
      ) : state.status === "empty" ? (
        <div className="px-3 py-2 text-xs text-muted-foreground">
          {t("workspace_completion.empty")}
        </div>
      ) : state.status === "error" ? (
        <div className="px-3 py-2 text-xs text-muted-foreground">
          {t("workspace_completion.error")}
        </div>
      ) : (
        <ul
          ref={listRef}
          role="listbox"
          aria-label={t("workspace_completion.title")}
          className="max-h-64 overflow-y-auto py-1"
        >
          {state.items.map((item, index) => (
            <li
              key={item.label}
              role="option"
              aria-selected={index === state.selectedIndex}
              data-index={index}
              className={cn(
                "flex cursor-pointer items-center gap-2 px-3 py-1.5 text-xs",
                index === state.selectedIndex
                  ? "bg-accent text-accent-foreground"
                  : "text-foreground",
              )}
              onPointerDown={(event) => {
                // preventDefault 保住光标；apply 由外层 pointerup/click 之前完成更稳，
                // 但 pointerdown 即 apply 避免浏览器把 mousedown 默认行为先跑掉。
                event.preventDefault();
                onPick(index);
              }}
              onMouseEnter={() => onHighlight(index)}
            >
              {item.isDirectory ? (
                <Folder className="size-3.5 shrink-0 text-muted-foreground" />
              ) : (
                <File className="size-3.5 shrink-0 text-muted-foreground" />
              )}
              <span className="truncate font-mono">{item.label}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
