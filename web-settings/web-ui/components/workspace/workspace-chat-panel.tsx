import { WorkspaceEditorRouterGuard } from "@overlay/components/workspace/use-workspace-editor-session";
import { useWorkspaceFilePane, WorkspaceFilePaneBody } from "@overlay/components/workspace/workspace-file-pane";
import type { EditorSaveStatus } from "@overlay/lib/workspace-editor-session";
import type { GroupImperativeHandle } from "react-resizable-panels";
import * as React from "react";
import { createPortal } from "react-dom";

import {
  ArrowLeft,
  CloudCheck,
  CloudOff,
  Columns2,
  Copy,
  Download,
  ExternalLink,
  FilePlus,
  FileText,
  Folder,
  FolderInput,
  FolderOpen,
  Image as ImageIcon,
  Loader2,
  Maximize2,
  Minimize2,
  MoreVertical,
  PackageOpen,
  Pencil,
  RefreshCw,
  Upload,
  X,
  CopyPlus,
} from "lucide-react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import { ResizableHandle, ResizablePanel, ResizablePanelGroup } from "~/components/ui/resizable";
import { fitPanelLayout } from "~/lib/panel-layout";
import { Spinner } from "~/components/ui/spinner";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "~/components/ui/dropdown-menu";
import { cn } from "~/lib/utils";
import { copyTextToClipboard } from "~/lib/clipboard";
import {
  useWorkspaceFileSelection,
  WorkspaceEntryCheckbox,
  WorkspaceSelectionBar,
} from "@overlay/components/workspace/workspace-file-selection";
import { useWorkspaceDragUpload } from "@overlay/components/workspace/use-workspace-drag-upload";
import { useWorkspaceFileDialogs } from "@overlay/components/workspace/workspace-file-dialogs";
import {
  detectWorkspaceFileKind,
  downloadWorkspaceArchive,
  formatWorkspaceBytes,
  isWorkspaceAbortError,
  isWorkspaceArchiveName,
  parentWorkspacePath,
  pruneWorkspaceSelection,
  saveBlobAs,
  sortWorkspaceEntries,
  workspaceApi,
  workspaceFileUrl,
  workspaceGuestPath,
} from "@overlay/lib/workspace";
import { reportSettingsError } from "@overlay/components/settings/settings-ui";
import type { Workspace, WorkspaceFileEntry } from "@overlay/types/workspace";

type SyncStatus = EditorSaveStatus;

function formatClock(date: Date): string {
  const hh = String(date.getHours()).padStart(2, "0");
  const mm = String(date.getMinutes()).padStart(2, "0");
  return `${hh}:${mm}`;
}

function formatEntryTime(updatedAt: number): string {
  return new Date(updatedAt).toLocaleString(undefined, {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function SyncBadge({
  status,
  savedAt,
  onRetry,
}: {
  status: SyncStatus;
  savedAt: Date | null;
  onRetry: () => void;
}) {
  const { t } = useTranslation("workspace");
  if (status === "idle") return null;

  const baseClass = "inline-flex shrink-0 items-center gap-1 text-xs";

  if (status === "saving") {
    return (
      <span className={cn(baseClass, "text-muted-foreground")}>
        <Loader2 className="size-3.5 animate-spin" />
        {t("chat_panel.sync.saving")}
      </span>
    );
  }
  if (status === "unsaved") {
    return (
      <span className={cn(baseClass, "text-muted-foreground")}>
        <Pencil className="size-3.5" />
        {t("chat_panel.sync.unsaved")}
      </span>
    );
  }
  if (status === "saved") {
    return (
      <span className={cn(baseClass, "text-muted-foreground")}>
        <CloudCheck className="size-3.5" />
        {t("chat_panel.sync.saved", { time: savedAt ? formatClock(savedAt) : "--:--" })}
      </span>
    );
  }
  if (["conflict", "unknown", "missingRevision"].includes(status)) return <span role="status" className={cn(baseClass, "text-destructive")}>{t(`editor_state.${status}`)}</span>;
  return (
    <button
      type="button"
      onClick={onRetry}
      className={cn(baseClass, "text-destructive hover:underline")}
    >
      <CloudOff className="size-3.5" />
      {t("chat_panel.sync.error")}
    </button>
  );
}

export interface WorkspaceChatPanelProps {
  workspaceId: string;
  /**
   * 专注模式：本面板只放编辑器（正文居中），文件列表 portal 到 [listContainer]
   * （聊天页最右侧的面板）。编辑器始终留在本组件里、不重新挂载，撤销历史/推敲/自动保存都保留。
   */
  focus?: boolean;
  listContainer?: HTMLElement | null;
  /** 给了才显示「专注」按钮、响应 Ctrl/⌘+Shift+F 与 Esc（手机抽屉不给）。 */
  onFocusChange?: (focus: boolean) => void;
  /** 给了才能分成左右两栏（Ctrl/⌘+\ 切换；手机抽屉不给）；开关时回调，聊天页据此加宽面板。 */
  onSplitChange?: (split: boolean) => void;
}

type PaneIndex = 0 | 1;
const otherPane = (index: PaneIndex): PaneIndex => (index === 0 ? 1 : 0);

export function WorkspaceChatPanel({
  workspaceId,
  focus = false,
  listContainer = null,
  onFocusChange,
  onSplitChange,
}: WorkspaceChatPanelProps): React.JSX.Element {
  const { t } = useTranslation("workspace");

  const [workspace, setWorkspace] = React.useState<Workspace | null>(null);
  const [workspaceError, setWorkspaceError] = React.useState(false);
  const [path, setPath] = React.useState("");
  const [entries, setEntries] = React.useState<WorkspaceFileEntry[]>([]);
  const [listLoading, setListLoading] = React.useState(true);
  const [listError, setListError] = React.useState(false);

  // 两栏各自一个文件与编辑会话。单栏时只显示 [primary] 那一栏，另一栏保持空着；
  // 两栏始终按 0 左 1 右的固定位置渲染，开关双栏、进出专注模式都不重建已打开的编辑器。
  const paneA = useWorkspaceFilePane(workspaceId);
  const paneB = useWorkspaceFilePane(workspaceId);
  const panes = [paneA, paneB] as const;
  const splittable = onSplitChange !== undefined;
  const [split, setSplit] = React.useState(false);
  const [primary, setPrimary] = React.useState<PaneIndex>(0);
  const [active, setActive] = React.useState<PaneIndex>(0);
  /** 普通模式双栏：在这一栏上面盖着文件列表选新文件（文件仍在下面，取消即回到它）。 */
  const [picking, setPicking] = React.useState<PaneIndex | null>(null);
  const main = panes[primary];

  const onSplitChangeRef = React.useRef(onSplitChange);
  onSplitChangeRef.current = onSplitChange;
  const splitReported = React.useRef(false);
  const splitRef = React.useRef(split);
  splitRef.current = split;
  React.useEffect(() => {
    if (!split && !splitReported.current) return;
    splitReported.current = true;
    onSplitChangeRef.current?.(split);
  }, [split]);
  // 换工作区（本组件按 key 重建）或切到手机布局时，两栏随之消失：告诉聊天页恢复布局。
  React.useEffect(() => () => { if (splitRef.current) onSplitChangeRef.current?.(false); }, []);

  // 文件列表显示在哪一栏（普通模式）、点文件打开到哪一栏。专注模式列表在最右侧面板，打开到活动栏。
  const listPane: PaneIndex | null = !split
    ? (focus || !main.openFile ? primary : null)
    : focus ? active
    : picking ?? (!paneA.openFile ? 0 : !paneB.openFile ? 1 : null);
  const listTarget: PaneIndex = listPane ?? active;
  const listVisible = focus || listPane !== null;

  const loadWorkspace = React.useCallback(async () => {
    setWorkspaceError(false);
    try {
      const result = await workspaceApi.get(workspaceId);
      setWorkspace(result);
    } catch {
      setWorkspaceError(true);
    }
  }, [workspaceId]);

  React.useEffect(() => {
    setWorkspace(null);
    void loadWorkspace();
  }, [loadWorkspace]);

  const [batchBusy, setBatchBusy] = React.useState(false);
  const selection = useWorkspaceFileSelection(
    `${workspaceId}:FILES:${path}:${listVisible ? "list" : main.openFile?.path ?? "list"}`,
  );
  // loadEntries 里修剪已消失的选中项（用 ref 避免 stale deps）
  const selectionRef = React.useRef(selection);
  selectionRef.current = selection;
  const setSelectedPrune = React.useCallback((entries: WorkspaceFileEntry[]) => {
    selectionRef.current.setSelected((prev) => pruneWorkspaceSelection(prev, entries));
  }, []);

  const loadEntries = React.useCallback(async () => {
    setListLoading(true);
    setListError(false);
    try {
      const result = await workspaceApi.listFiles(workspaceId, "FILES", path);
      setEntries(result.entries);
      setSelectedPrune(result.entries);
    } catch {
      setListError(true);
    } finally {
      setListLoading(false);
    }
  }, [workspaceId, path, setSelectedPrune]);

  React.useEffect(() => {
    void loadEntries();
  }, [loadEntries]);

  const { dragActive, uploadRef, dragHandlers, handleFileInputChange } = useWorkspaceDragUpload({
    workspaceId,
    area: "FILES",
    path,
    enabled: listVisible,
    onUploaded: loadEntries,
  });

  const {
    dialogs: fileDialogs,
    openCreateFile,
    openRename,
    openMove,
    openCopy,
    openBatchDelete,
    batchDeleting,
    openBatchMove,
    openBatchCopy,
    batchMoving,
    openExtract,
  } = useWorkspaceFileDialogs({
    workspaceId,
    area: "FILES",
    path,
    reload: loadEntries,
    onCreated: (content, snapshot) => {
      setActive(listTarget);
      setPicking(null);
      panes[listTarget].openCreated(content, snapshot);
    },
  });

  const batchPending = batchBusy || batchDeleting || batchMoving;

  const sortedEntries = React.useMemo(() => sortWorkspaceEntries(entries), [entries]);
  const selectedEntries = React.useMemo(
    () => sortedEntries.filter((entry) => selection.selected.has(entry.path)),
    [selection.selected, sortedEntries],
  );

  const handleArchive = React.useCallback(async () => {
    const paths = [...selection.selected];
    if (paths.length === 0 || batchPending) return;
    setBatchBusy(true);
    try {
      const { blob, fileName } = await downloadWorkspaceArchive(workspaceId, "FILES", paths);
      saveBlobAs(blob, fileName);
      toast.success(t("files.archive_done", { count: paths.length }));
    } catch (error) {
      if (!isWorkspaceAbortError(error)) {
        reportSettingsError(error, t("files.archive_failed"));
      }
    } finally {
      setBatchBusy(false);
    }
  }, [batchPending, selection.selected, t, workspaceId]);

  const handleBatchDelete = React.useCallback(() => {
    if (batchPending || selectedEntries.length === 0) return;
    openBatchDelete(selectedEntries, (failedPaths) => {
      selection.replace(failedPaths);
    });
  }, [batchPending, openBatchDelete, selectedEntries, selection]);

  const handleBatchMove = React.useCallback(() => {
    if (batchPending || selectedEntries.length === 0) return;
    openBatchMove(selectedEntries, (failedPaths) => selection.replace(failedPaths));
  }, [batchPending, openBatchMove, selectedEntries, selection]);

  const handleBatchCopy = React.useCallback(() => {
    if (batchPending || selectedEntries.length === 0) return;
    openBatchCopy(selectedEntries, (failedPaths) => selection.replace(failedPaths));
  }, [batchPending, openBatchCopy, selectedEntries, selection]);

  const copyPath = async (entry: WorkspaceFileEntry) => {
    try {
      await copyTextToClipboard(workspaceGuestPath("FILES", entry.path));
      toast.success(t("files.path_copied"));
    } catch {
      toast.error(t("files.copy_failed"));
    }
  };

  // 普通单栏：打开文件时列表被编辑器替换，进出目录要先关掉文件；专注模式与双栏里列表和编辑器同时在。
  const listBesideEditor = focus || split;
  const goToPath = (next: string) => listBesideEditor ? setPath(next) : main.close(() => setPath(next));

  const goBack = () => {
    if (!listBesideEditor && main.openFile) {
      main.close();
      return;
    }
    if (path) setPath(parentWorkspacePath(path));
  };

  /** 打开到 [target] 栏；文件已在另一栏打开就切到那一栏（同一文件两个会话会互相冲突）。 */
  const openInto = async (target: PaneIndex, entry: { path: string; name: string }) => {
    setPicking(null);
    if (panes[otherPane(target)].openFile?.path === entry.path) {
      setActive(otherPane(target));
      toast.info(t("split.already_open"));
      return;
    }
    setActive(target);
    await panes[target].open(entry);
  };

  const openEntry = (entry: WorkspaceFileEntry) => {
    if (entry.isDirectory) {
      goToPath(entry.path);
      return;
    }
    void openInto(listTarget, entry);
  };

  const openInOtherPane = (entry: WorkspaceFileEntry) => {
    const target = split ? otherPane(listTarget) : otherPane(primary);
    if (panes[otherPane(target)].openFile?.path === entry.path) {
      setActive(otherPane(target));
      toast.info(t("split.already_open"));
      return;
    }
    if (!split) setSplit(true);
    void openInto(target, entry);
  };

  const enterSplit = () => {
    if (split || !splittable) return;
    setSplit(true);
    setActive(otherPane(primary));
    setPicking(null);
  };

  /** 回到单栏，留下 [keep]（缺省：活动栏，它空着就留另一栏）；关掉的那一栏有未保存内容时先确认。 */
  const exitSplit = (keep?: PaneIndex) => {
    if (!split) return;
    const kept = keep ?? (panes[active].openFile || !panes[otherPane(active)].openFile ? active : otherPane(active));
    panes[otherPane(kept)].close(() => {
      setSplit(false);
      setPrimary(kept);
      setActive(kept);
      setPicking(null);
    });
  };

  const refresh = () => {
    void loadEntries();
    for (const pane of split ? panes : [main]) pane.reload();
  };

  // 快捷键：Ctrl/⌘+Shift+F 切换专注（有打开的文件时），Esc 退出专注（不在弹窗里、没被别处处理时），
  // Ctrl/⌘+\ 切换双栏。
  const anyFileOpen = split ? Boolean(paneA.openFile || paneB.openFile) : main.openFile !== null;
  const canFocus = anyFileOpen;
  const shortcutsRef = React.useRef({ enterSplit, exitSplit });
  shortcutsRef.current = { enterSplit, exitSplit };
  React.useEffect(() => {
    if (!onFocusChange && !splittable) return;
    const onKey = (event: KeyboardEvent) => {
      const mod = (event.ctrlKey || event.metaKey) && !event.altKey;
      if (onFocusChange && mod && event.shiftKey && event.key.toLowerCase() === "f") {
        if (!focus && !canFocus) return;
        event.preventDefault();
        onFocusChange(!focus);
      } else if (splittable && mod && !event.shiftKey && event.key === "\\") {
        if (!split && !anyFileOpen) return;
        event.preventDefault();
        if (split) shortcutsRef.current.exitSplit(); else shortcutsRef.current.enterSplit();
      } else if (onFocusChange && event.key === "Escape" && focus && !event.defaultPrevented &&
        !document.querySelector('[role="dialog"], [data-radix-popper-content-wrapper]')) {
        onFocusChange(false);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onFocusChange, focus, canFocus, splittable, split, anyFileOpen]);

  // 两栏的尺寸：开双栏 50/50，关掉后剩下的一栏占满。面板挂载后要等下一帧再设。
  const paneGroupRef = React.useRef<GroupImperativeHandle | null>(null);
  React.useEffect(() => {
    const frame = requestAnimationFrame(() => {
      const group = paneGroupRef.current;
      const layout = group && fitPanelLayout(split ? { "pane-0": 50, "pane-1": 50 } : { [`pane-${primary}`]: 100 }, Object.keys(group.getLayout()));
      if (layout) group.setLayout(layout);
    });
    return () => cancelAnimationFrame(frame);
  }, [split, primary]);

  if (workspaceError) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-3 p-6 text-center">
        <CloudOff className="size-8 text-muted-foreground" />
        <p className="text-sm font-medium">{t("chat_panel.unavailable")}</p>
        <p className="text-xs text-muted-foreground">{t("chat_panel.unavailable_desc")}</p>
        <Button type="button" size="sm" variant="outline" onClick={() => void loadWorkspace()}>
          {t("chat_panel.retry")}
        </Button>
      </div>
    );
  }

  const segments = path ? path.split("/") : [];

  const focusButton = onFocusChange && (focus || canFocus) ? (
    <Button
      type="button"
      size="icon-sm"
      variant={focus ? "secondary" : "ghost"}
      onClick={() => onFocusChange(!focus)}
      title={`${t(focus ? "focus.exit" : "focus.enter")}（${t("focus.shortcut")}）`}
      aria-label={t(focus ? "focus.exit" : "focus.enter")}
      aria-pressed={focus}
      data-testid="workspace-focus-toggle"
    >
      {focus ? <Minimize2 className="size-4" /> : <Maximize2 className="size-4" />}
    </Button>
  ) : null;

  const splitButton = splittable && (split || anyFileOpen) ? (
    <Button
      type="button"
      size="icon-sm"
      variant={split ? "secondary" : "ghost"}
      onClick={() => (split ? exitSplit() : enterSplit())}
      title={`${t(split ? "split.exit" : "split.enter")}（${t("split.shortcut")}）`}
      aria-label={t(split ? "split.exit" : "split.enter")}
      aria-pressed={split}
      data-testid="workspace-split-toggle"
    >
      <Columns2 className="size-4" />
    </Button>
  ) : null;

  const refreshButton = (
    <Button
      type="button"
      size="icon-sm"
      variant="ghost"
      onClick={refresh}
      title={t("chat_panel.refresh")}
    >
      <RefreshCw className="size-4" />
    </Button>
  );

  // 列表头：返回、路径、上传/新建、刷新。普通单栏打开文件时路径末尾带文件名。
  const renderListHeader = ({ fileCrumb, actions, extra }: { fileCrumb: boolean; actions: boolean; extra?: React.ReactNode }) => (
    <div className="flex items-center gap-1 border-b px-2 py-1.5">
      <Button
        type="button"
        size="icon-sm"
        variant="ghost"
        disabled={listBesideEditor ? !path : !main.openFile && !path}
        onClick={goBack}
        title={t("chat_panel.back")}
      >
        <ArrowLeft className="size-4" />
      </Button>
        <nav className="flex min-w-0 flex-1 items-center gap-0.5 text-xs">
          <button
            type="button"
            className="shrink-0 rounded px-1 py-0.5 text-muted-foreground hover:bg-muted hover:text-foreground"
            onClick={() => goToPath("")}
          >
            {workspace?.name ?? t("chat_panel.root")}
          </button>
          {segments.map((segment, index) => {
            const target = segments.slice(0, index + 1).join("/");
            const isLast = index === segments.length - 1 && !fileCrumb;
            return (
              <React.Fragment key={target}>
                <span className="shrink-0 text-muted-foreground">/</span>
                {isLast ? (
                  <span className="truncate px-1 py-0.5">{segment}</span>
                ) : (
                  <button
                    type="button"
                    className="truncate rounded px-1 py-0.5 text-muted-foreground hover:bg-muted hover:text-foreground"
                    onClick={() => goToPath(target)}
                  >
                    {segment}
                  </button>
                )}
              </React.Fragment>
            );
          })}
          {fileCrumb && main.openFile ? (
            <>
              <span className="shrink-0 text-muted-foreground">/</span>
              <span className="truncate px-1 py-0.5 font-medium">{main.openFile.name}</span>
            </>
          ) : null}
        </nav>
        {actions ? (
          <>
            <Button
              type="button"
              size="icon-sm"
              variant="ghost"
              onClick={() => uploadRef.current?.click()}
              title={t("files.upload")}
            >
              <Upload className="size-4" />
            </Button>
            <Button
              type="button"
              size="icon-sm"
              variant="ghost"
              onClick={openCreateFile}
              title={t("files.new_file")}
            >
              <FilePlus className="size-4" />
            </Button>
          </>
        ) : null}
      {refreshButton}
      {extra}
    </div>
  );

  const selectionBarNode = (
    !listLoading && sortedEntries.length > 0 ? (
        <WorkspaceSelectionBar
          compact
          className="mx-2 mt-2"
          entries={sortedEntries}
          selected={selection.selected}
          busy={listLoading}
          pending={batchPending}
          onToggleAll={(all) => selection.setAll(sortedEntries, all)}
          onClear={selection.clear}
          onArchive={() => void handleArchive()}
          onDelete={handleBatchDelete}
          onMove={handleBatchMove}
          onCopy={handleBatchCopy}
        />
      ) : null
  );

  // 列表里高亮：打开到的那一栏的文件；双栏时另一栏的文件加粗。
  const targetPath = listBesideEditor ? panes[listTarget].openFile?.path : undefined;
  const otherPath = split ? panes[otherPane(listTarget)].openFile?.path : undefined;

  const listBody = (
    listLoading ? (
          <div className="flex h-full items-center justify-center">
            <Spinner className="size-5 text-muted-foreground" />
          </div>
        ) : listError ? (
          <div className="flex h-full flex-col items-center justify-center gap-3 p-6 text-center">
            <p className="text-sm text-muted-foreground">{t("chat_panel.load_failed")}</p>
            <Button type="button" size="sm" variant="outline" onClick={() => void loadEntries()}>
              {t("chat_panel.retry")}
            </Button>
          </div>
        ) : sortedEntries.length === 0 ? (
          <div className="flex h-full items-center justify-center p-6 text-center">
            <p className="text-sm text-muted-foreground">{t("chat_panel.empty_dir")}</p>
          </div>
        ) : (
          <ul>
            {sortedEntries.map((entry) => {
              const kind = detectWorkspaceFileKind(entry.name);
              const Icon = entry.isDirectory ? Folder : kind === "image" ? ImageIcon : FileText;
              const current = !entry.isDirectory && targetPath === entry.path;
              return (
                <li
                  key={`${entry.path}:${entry.isDirectory ? "dir" : "file"}`}
                  className="group border-b last:border-b-0"
                >
                  <div className="flex items-center gap-1 pr-1">
                    <div className="pl-2">
                      <WorkspaceEntryCheckbox
                        path={entry.path}
                        name={entry.name}
                        selected={selection.selected.has(entry.path)}
                        disabled={batchPending}
                        onToggle={selection.toggle}
                      />
                    </div>
                    <button
                      type="button"
                      className={cn(
                        "flex min-w-0 flex-1 items-center gap-2.5 px-2 py-2 text-left hover:bg-muted/60",
                        current && "bg-muted font-medium",
                        !entry.isDirectory && otherPath === entry.path && "font-medium",
                      )}
                      aria-current={current ? "true" : undefined}
                      onClick={() => openEntry(entry)}
                    >
                      <Icon
                        className={cn(
                          "size-4 shrink-0",
                          entry.isDirectory ? "text-primary" : "text-muted-foreground",
                        )}
                      />
                      <span className="min-w-0 flex-1 truncate text-sm">{entry.name}</span>
                      <span className="shrink-0 text-xs text-muted-foreground">
                        {entry.isDirectory ? "" : formatWorkspaceBytes(entry.sizeBytes)}
                      </span>
                      <span className="hidden shrink-0 text-xs text-muted-foreground sm:inline">
                        {formatEntryTime(entry.updatedAt)}
                      </span>
                    </button>
                    <DropdownMenu>
                      <DropdownMenuTrigger asChild>
                        <Button
                          type="button"
                          size="icon-sm"
                          variant="ghost"
                          className="shrink-0 text-muted-foreground"
                          title={t("files.more")}
                          aria-label={t("files.more")}
                          onClick={(event) => event.stopPropagation()}
                        >
                          <MoreVertical className="size-4" />
                        </Button>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent align="end">
                        {splittable && !entry.isDirectory ? (
                          <DropdownMenuItem onClick={() => openInOtherPane(entry)} data-testid="workspace-open-other-pane">
                            <Columns2 className="size-4" />
                            {t("split.open_in_other")}
                          </DropdownMenuItem>
                        ) : null}
                        <DropdownMenuItem onClick={() => openRename(entry)}>
                          <Pencil className="size-4" />
                          {t("files.rename")}
                        </DropdownMenuItem>
                        <DropdownMenuItem onClick={() => openMove(entry)}>
                          <FolderInput className="size-4" />
                          {t("files.move")}
                        </DropdownMenuItem>
                        <DropdownMenuItem onClick={() => openCopy(entry)} data-testid="workspace-copy-to">
                          <CopyPlus className="size-4" />
                          {t("files.copy_to")}
                        </DropdownMenuItem>
                        <DropdownMenuItem onClick={() => void copyPath(entry)}>
                          <Copy className="size-4" />
                          {t("files.copy_path")}
                        </DropdownMenuItem>
                        {!entry.isDirectory && isWorkspaceArchiveName(entry.name) ? (
                          <DropdownMenuItem onClick={() => openExtract(entry)}>
                            <PackageOpen className="size-4" />
                            {t("extract.menu")}
                          </DropdownMenuItem>
                        ) : null}
                        {!entry.isDirectory ? (
                          <DropdownMenuItem
                            onClick={() => {
                              window.open(workspaceFileUrl(workspaceId, "FILES", entry.path), "_blank", "noopener,noreferrer");
                            }}
                          >
                            <Download className="size-4" />
                            {t("files.download")}
                          </DropdownMenuItem>
                        ) : null}
                      </DropdownMenuContent>
                    </DropdownMenu>
                  </div>
                </li>
              );
            })}
          </ul>
        )
  );

  const listFooter = (
      <div className="border-t px-3 py-1.5">
        <Link
          to={`/workspaces/${workspaceId}`}
          className="inline-flex items-center gap-1 text-xs text-muted-foreground transition-colors hover:text-foreground"
        >
          <ExternalLink className="size-3" />
          {t("chat_panel.open_workspace")}
        </Link>
      </div>

  );

  const dropOverlay = dragActive ? (
    <div className="pointer-events-none absolute inset-0 z-20 flex items-center justify-center border-2 border-dashed border-primary/50 bg-background/80 px-4 text-center text-sm font-medium text-primary">
      {t("files.drop_to_upload")}
    </div>
  ) : null;
  const dropZoneClass = cn(
    "relative min-h-0 flex-1 overflow-auto",
    dragActive && "border-primary/40 bg-primary/5 ring-2 ring-inset ring-primary/30",
  );

  /** 普通模式下盖在一栏里的文件列表（单栏没打开文件时、双栏的空栏或「换文件」时）。 */
  const inPaneList = (pane: PaneIndex) => (
    <div
      className={cn("flex h-full min-h-0 flex-col", panes[pane].openFile && "absolute inset-0 z-10 bg-background")}
      data-testid={split ? `workspace-pane-list-${pane}` : undefined}
    >
      {split ? renderListHeader({ fileCrumb: false, actions: true }) : null}
      {selectionBarNode}
      <div className={dropZoneClass} {...dragHandlers}>
        {dropOverlay}
        {listBody}
      </div>
    </div>
  );

  const paneHeader = (index: PaneIndex) => {
    const pane = panes[index];
    const isActive = active === index;
    return (
      <div
        className={cn(
          "flex items-center gap-1 border-b px-2 py-1",
          isActive ? "bg-muted/60 shadow-[inset_0_2px_0_var(--primary)]" : "text-muted-foreground",
        )}
        data-testid={`workspace-pane-header-${index}`}
        data-active={isActive ? "true" : undefined}
      >
        <FileText className="size-3.5 shrink-0" />
        <span className="min-w-0 flex-1 truncate text-xs font-medium" title={pane.openFile?.path}>
          {pane.openFile?.name ?? t("split.empty")}
        </span>
        <SyncBadge
          status={pane.openFile ? pane.session.status : "idle"}
          savedAt={pane.session.savedAt}
          onRetry={() => void pane.session.save()}
        />
        {!focus && pane.openFile ? (
          picking === index ? (
            <Button type="button" size="sm" variant="ghost" className="h-6 px-2 text-xs" onClick={() => setPicking(null)}>
              {t("split.cancel_pick")}
            </Button>
          ) : (
            <Button
              type="button"
              size="icon-sm"
              variant="ghost"
              title={t("split.change_file")}
              aria-label={t("split.change_file")}
              onClick={() => { setActive(index); setPicking(index); }}
              data-testid={`workspace-pane-change-${index}`}
            >
              <FolderOpen className="size-4" />
            </Button>
          )
        ) : null}
        <Button
          type="button"
          size="icon-sm"
          variant="ghost"
          title={t("split.close_pane")}
          aria-label={t("split.close_pane")}
          onClick={() => exitSplit(otherPane(index))}
          data-testid={`workspace-pane-close-${index}`}
        >
          <X className="size-4" />
        </Button>
      </div>
    );
  };

  const paneFrame = (index: PaneIndex) => {
    const pane = panes[index];
    return (
      <div
        className="flex h-full min-h-0 flex-col"
        onPointerDownCapture={() => { if (active !== index) setActive(index); }}
        onFocusCapture={() => { if (active !== index) setActive(index); }}
        data-testid={split ? `workspace-pane-${index}` : undefined}
      >
        {split ? paneHeader(index) : null}
        {split && pane.openFile ? pane.notice : null}
        <div className="relative min-h-0 flex-1 overflow-auto">
          <WorkspaceFilePaneBody
            pane={pane}
            workspaceId={workspaceId}
            centered={focus}
            onBackToList={() => pane.close(() => { if (split) setPicking(index); })}
          />
          {!focus && listPane === index ? inPaneList(index) : null}
          {focus && !pane.openFile ? (
            <div className="flex h-full items-center justify-center p-6 text-center">
              <p className="text-sm text-muted-foreground">{t("focus.pick_file")}</p>
            </div>
          ) : null}
        </div>
      </div>
    );
  };

  // 单栏/双栏、普通/专注都是同一棵树、同样的插槽位置：两栏按固定 key 挂在同一个分组里，
  // 切换时 React 只更新属性，CodeMirror 实例（撤销历史、选区、推敲面板）不会重建。
  return (
    <div className="flex h-full min-h-0 flex-col" data-testid={focus ? "workspace-focus-editor" : undefined}>
      <WorkspaceEditorRouterGuard entries={panes.map(pane => ({ session: pane.session, requestLeave: pane.requestLeave }))} />
      {paneA.guard}
      {paneB.guard}
      {!split && main.openFile ? main.notice : null}
      {split ? (
        <div className="flex items-center gap-1 border-b px-3 py-1.5" data-testid="workspace-split-header">
          <span className="min-w-0 flex-1 truncate text-xs font-medium">
            {focus ? t("split.title") : workspace?.name ?? t("chat_panel.root")}
          </span>
          {refreshButton}
          {splitButton}
          {focusButton}
        </div>
      ) : focus ? (
        <div className="flex items-center gap-2 border-b px-3 py-1.5">
          <span className="min-w-0 flex-1 truncate text-xs font-medium">
            {main.openFile ? main.openFile.name : t("focus.files")}
          </span>
          <SyncBadge
            status={main.openFile ? main.session.status : "idle"}
            savedAt={main.session.savedAt}
            onRetry={() => void main.session.save()}
          />
          {splitButton}
          {focusButton}
        </div>
      ) : renderListHeader({
        fileCrumb: true,
        actions: !main.openFile,
        extra: (
          <>
            {main.openFile ? splitButton : null}
            {main.openFile ? focusButton : null}
            <SyncBadge
              status={main.openFile ? main.session.status : "idle"}
              savedAt={main.session.savedAt}
              onRetry={() => void main.session.save()}
            />
          </>
        ),
      })}

      <div className="relative min-h-0 flex-1">
        <ResizablePanelGroup orientation="horizontal" groupRef={paneGroupRef}>
          {split || primary === 0 ? (
            <ResizablePanel key="pane-0" id="pane-0" defaultSize={split ? "50%" : "100%"} minSize="20%" className="flex min-h-0 flex-col">
              {paneFrame(0)}
            </ResizablePanel>
          ) : null}
          {split ? <ResizableHandle key="pane-handle" withHandle /> : null}
          {split || primary === 1 ? (
            <ResizablePanel key="pane-1" id="pane-1" defaultSize={split ? "50%" : "100%"} minSize="20%" className="flex min-h-0 flex-col">
              {paneFrame(1)}
            </ResizablePanel>
          ) : null}
        </ResizablePanelGroup>
      </div>

      {focus ? null : listFooter}

      <input
        ref={uploadRef}
        type="file"
        multiple
        className="hidden"
        onChange={handleFileInputChange}
      />

      {fileDialogs}

      {focus && listContainer
        ? createPortal(
            <div className="flex h-full min-h-0 flex-col" data-testid="workspace-focus-list">
              {renderListHeader({ fileCrumb: false, actions: true })}
              {selectionBarNode}
              <div className={dropZoneClass} {...dragHandlers}>
                {dropOverlay}
                {listBody}
              </div>
              {listFooter}
            </div>,
            listContainer,
          )
        : null}
    </div>
  );
}
