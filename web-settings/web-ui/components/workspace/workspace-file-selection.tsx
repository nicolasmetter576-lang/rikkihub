import * as React from "react";

import { Archive, CopyPlus, FolderInput, Loader2, Trash2, X } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "~/components/ui/button";
import { Checkbox } from "~/components/ui/checkbox";
import { cn } from "~/lib/utils";
import {
  isWorkspaceSelectionAll,
  nextSelectionForScope,
  setWorkspaceSelectionAll,
  toggleWorkspaceSelection,
  validateWorkspaceBatchPaths,
  WORKSPACE_BATCH_MAX_PATHS,
} from "@overlay/lib/workspace";
import type { WorkspaceFileEntry } from "@overlay/types/workspace";

/**
 * 目录级多选状态。scopeKey（workspaceId/area/path）变化自动清空。
 * 忙时禁变动由 UI disabled 保证（checkbox/按钮在 pending 时不可点）。
 */
export function useWorkspaceFileSelection(scopeKey: string) {
  // {scopeKey, selected} 合一存储：scope 变化经 nextSelectionForScope 清空
  const [state, setState] = React.useState<{ scopeKey: string; selected: ReadonlySet<string> }>(
    () => ({ scopeKey, selected: new Set() }),
  );
  if (state.scopeKey !== scopeKey) {
    setState(nextSelectionForScope(state, scopeKey));
  }
  const selected = state.selected;
  const setSelected = React.useCallback(
    (updater: ReadonlySet<string> | ((prev: ReadonlySet<string>) => ReadonlySet<string>)) => {
      setState((prev) => ({
        scopeKey: prev.scopeKey,
        selected: typeof updater === "function" ? updater(prev.selected) : updater,
      }));
    },
    [],
  );

  const toggle = React.useCallback((path: string) => {
    setSelected((prev) => toggleWorkspaceSelection(prev, path));
  }, []);

  const setAll = React.useCallback(
    (entries: ReadonlyArray<WorkspaceFileEntry>, all: boolean) => {
      setSelected(setWorkspaceSelectionAll(entries, all));
    },
    [],
  );

  const clear = React.useCallback(() => {
    setSelected(new Set());
  }, []);

  /** 批操作结束后用给定路径集替换选择（如保留失败项）。 */
  const replace = React.useCallback((paths: Iterable<string>) => {
    setSelected(new Set(paths));
  }, []);

  return { selected, toggle, setAll, clear, replace, setSelected };
}

export interface WorkspaceSelectionBarProps {
  entries: ReadonlyArray<WorkspaceFileEntry>;
  selected: ReadonlySet<string>;
  busy: boolean;
  /** 批量操作进行中（禁用全部交互） */
  pending?: boolean;
  onToggleAll: (all: boolean) => void;
  onClear: () => void;
  onArchive: () => void;
  onDelete: () => void;
  /** 多选移动（只在 FILES 区提供；不传则不显示） */
  onMove?: () => void;
  /** 多选复制（只在 FILES 区提供；不传则不显示） */
  onCopy?: () => void;
  /** 紧凑模式（聊天侧面板）：只留图标与计数 */
  compact?: boolean;
  className?: string;
}

/**
 * 文件列表的选择工具条：当前页全选 checkbox + 选中计数 + 下载 ZIP / 批删。
 * 详情页文件浏览器与聊天侧文件面板共用，交互保持一致。
 */
export function WorkspaceSelectionBar({
  entries,
  selected,
  busy,
  pending = false,
  onToggleAll,
  onClear,
  onArchive,
  onDelete,
  onMove,
  onCopy,
  compact = false,
  className,
}: WorkspaceSelectionBarProps) {
  const { t } = useTranslation("workspace");
  const disabled = busy || pending;
  const allSelected = isWorkspaceSelectionAll(selected, entries);
  const someSelected = selected.size > 0;
  const paths = React.useMemo(() => [...selected], [selected]);
  const invalidReason = validateWorkspaceBatchPaths(paths);
  const overLimit = invalidReason === "too_many";

  return (
    <div
      className={cn(
        "flex flex-wrap items-center gap-2 rounded-lg border bg-muted/30 px-2 py-1.5",
        className,
      )}
      data-testid="workspace-selection-bar"
    >
      <label
        className={cn(
          "flex items-center gap-2 text-xs text-muted-foreground",
          disabled && "opacity-60",
        )}
      >
        <Checkbox
          checked={allSelected ? true : someSelected ? "indeterminate" : false}
          disabled={disabled}
          onCheckedChange={(checked) => onToggleAll(checked === true)}
          aria-label={t("selection.select_all")}
        />
        {compact ? null : <span>{t("selection.select_all")}</span>}
      </label>
      <span className="text-xs text-muted-foreground" data-testid="selection-count">
        {t("selection.selected", { count: selected.size })}
      </span>
      {overLimit ? (
        <span className="text-xs text-destructive">
          {t("selection.too_many", { max: WORKSPACE_BATCH_MAX_PATHS })}
        </span>
      ) : null}
      <div className="ml-auto flex items-center gap-1.5">
        {someSelected ? (
          <Button
            type="button"
            size={compact ? "icon-sm" : "sm"}
            variant="ghost"
            disabled={disabled}
            onClick={onClear}
            title={t("selection.clear")}
          >
            <X className="size-4" />
            {compact ? null : t("selection.clear")}
          </Button>
        ) : null}
        {onMove ? (
          <Button
            type="button"
            size={compact ? "icon-sm" : "sm"}
            variant="outline"
            disabled={disabled || !someSelected || invalidReason != null}
            onClick={onMove}
            title={t("selection.move")}
            data-testid="selection-move"
          >
            <FolderInput className="size-4" />
            {compact ? null : t("selection.move")}
          </Button>
        ) : null}
        {onCopy ? (
          <Button
            type="button"
            size={compact ? "icon-sm" : "sm"}
            variant="outline"
            disabled={disabled || !someSelected || invalidReason != null}
            onClick={onCopy}
            title={t("selection.copy")}
            data-testid="selection-copy"
          >
            <CopyPlus className="size-4" />
            {compact ? null : t("selection.copy")}
          </Button>
        ) : null}
        <Button
          type="button"
          size={compact ? "icon-sm" : "sm"}
          variant="outline"
          disabled={disabled || !someSelected || invalidReason != null}
          onClick={onArchive}
          title={t("selection.download_zip")}
        >
          {pending ? <Loader2 className="size-4 animate-spin" /> : <Archive className="size-4" />}
          {compact ? null : t("selection.download_zip")}
        </Button>
        <Button
          type="button"
          size={compact ? "icon-sm" : "sm"}
          variant="outline"
          className="text-destructive hover:bg-destructive/10 hover:text-destructive"
          disabled={disabled || !someSelected || invalidReason != null}
          onClick={onDelete}
          title={t("selection.delete")}
        >
          <Trash2 className="size-4" />
          {compact ? null : t("selection.delete")}
        </Button>
      </div>
    </div>
  );
}

/** 行内 checkbox（共享样式）。 */
export function WorkspaceEntryCheckbox({
  path,
  name,
  selected,
  disabled,
  onToggle,
}: {
  path: string;
  name: string;
  selected: boolean;
  disabled: boolean;
  onToggle: (path: string) => void;
}) {
  const { t } = useTranslation("workspace");
  if (name === "..") return <span className="size-4 shrink-0" aria-hidden />;
  return (
    <Checkbox
      className="shrink-0"
      checked={selected}
      disabled={disabled}
      aria-label={t("selection.select_entry", { name })}
      onCheckedChange={() => onToggle(path)}
      onClick={(event) => event.stopPropagation()}
    />
  );
}
