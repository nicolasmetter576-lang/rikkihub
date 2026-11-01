import * as React from "react";

import { useTranslation } from "react-i18next";

import { Button } from "~/components/ui/button";
import { Spinner } from "~/components/ui/spinner";
import { useWorkspaceEditorSession } from "@overlay/components/workspace/use-workspace-editor-session";
import { WorkspaceTextEditor } from "@overlay/components/workspace/workspace-text-editor";
import {
  detectWorkspaceFileKind,
  parentWorkspacePath,
  workspaceApi,
  workspaceFileUrl,
  writeWorkspaceFileKeepalive,
} from "@overlay/lib/workspace";
import type { WorkspaceFileContent, WorkspaceFileKind } from "@overlay/types/workspace";

export type WorkspaceOpenFile = {
  path: string;
  name: string;
  kind: WorkspaceFileKind;
};

/**
 * 工作区面板里的一栏：打开的文件、它的编辑会话（防抖自动保存、CAS、离开确认）与读取状态。
 * 双栏时两栏各一个，互不影响；路由拦截由面板统一做（一个路由只能有一个 blocker）。
 */
export function useWorkspaceFilePane(workspaceId: string) {
  const { session, requestLeave, notice, guard } = useWorkspaceEditorSession(
    (path, source, revision, keepalive) => keepalive
      ? writeWorkspaceFileKeepalive(workspaceId, path, source, revision)
      : workspaceApi.writeFile(workspaceId, path, source, revision),
    true,
    false,
  );
  const [openFile, setOpenFile] = React.useState<WorkspaceOpenFile | null>(null);
  const [fileEditable, setFileEditable] = React.useState(true);
  const [fileLoading, setFileLoading] = React.useState(false);
  const [fileError, setFileError] = React.useState(false);
  const loadSeqRef = React.useRef(0);

  const reset = () => {
    setOpenFile(null);
    session.discard();
    setFileEditable(true);
    setFileError(false);
    setFileLoading(false);
  };

  /**
   * 在这一栏打开 [entry]：同一个文件且不是重新读取时什么都不做；换文件前先等自动保存写完，
   * 写不进去才弹离开确认。
   */
  const open = async (entry: { path: string; name: string }, reload = false): Promise<void> => {
    if (!reload && openFile?.path === entry.path) return;
    if (session.dirty && session.canAutoSave && session.status !== "saving" && await session.save()) return open(entry, reload);
    if (session.dirty || session.status === "saving") { requestLeave(() => { session.discard(); void open(entry, reload); }); return; }
    const kind = detectWorkspaceFileKind(entry.name);
    const seq = ++loadSeqRef.current;
    reset();
    setOpenFile({ path: entry.path, name: entry.name, kind });
    if (kind !== "text") return;
    setFileLoading(true);
    try {
      const content = await workspaceApi.readFile(workspaceId, "FILES", entry.path);
      if (seq !== loadSeqRef.current) return;
      session.load(content);
      setFileEditable(content.editable);
    } catch {
      if (seq !== loadSeqRef.current) return;
      setFileError(true);
    } finally {
      if (seq === loadSeqRef.current) setFileLoading(false);
    }
  };

  /** A file just created from the list: opened as text whatever its extension. */
  const openCreated = (content: WorkspaceFileContent, snapshot: { path: string; name: string }) => {
    ++loadSeqRef.current;
    reset();
    setOpenFile({ path: snapshot.path, name: snapshot.name, kind: "text" });
    session.load(content);
    setFileEditable(content.editable);
  };

  /** Re-reads the open text file; never overwrites/rebases a dirty or uncertain draft. Returns false when skipped. */
  const reload = (): boolean => {
    if (openFile?.kind !== "text") return true;
    if (session.dirty || session.status === "saving" || session.status === "conflict") return false;
    void open(openFile, true);
    return true;
  };

  /** Closes the file (asking first when it has unsaved text), then runs [after]. */
  const close = (after?: () => void) => requestLeave(() => { reset(); after?.(); });

  const canEdit = openFile?.kind === "text" && fileEditable && !!session.revision && !fileLoading && !fileError;

  return { session, requestLeave, notice, guard, openFile, fileLoading, fileError, canEdit, open, openCreated, reload, close };
}

export type WorkspaceFilePane = ReturnType<typeof useWorkspaceFilePane>;

/** 一栏的内容：加载中 / 读取失败 / 图片 / 不支持 / 文本编辑器。 */
export function WorkspaceFilePaneBody({
  pane,
  workspaceId,
  centered,
  onBackToList,
}: {
  pane: WorkspaceFilePane;
  workspaceId: string;
  centered: boolean;
  onBackToList: () => void;
}) {
  const { t } = useTranslation("workspace");
  const { openFile, session } = pane;
  if (!openFile) return null;
  if (pane.fileLoading) {
    return (
      <div className="flex h-full items-center justify-center">
        <Spinner className="size-5 text-muted-foreground" />
      </div>
    );
  }
  if (pane.fileError) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-3 p-6 text-center">
        <p className="text-sm text-muted-foreground">{t("chat_panel.read_failed")}</p>
        <Button type="button" size="sm" variant="outline" onClick={onBackToList}>
          {t("chat_panel.back_to_list")}
        </Button>
      </div>
    );
  }
  if (openFile.kind === "image") {
    return (
      <div className="flex h-full items-center justify-center p-3">
        <img
          src={workspaceFileUrl(workspaceId, "FILES", openFile.path)}
          alt={openFile.name}
          className="max-h-full max-w-full rounded-md object-contain"
        />
      </div>
    );
  }
  if (openFile.kind === "other") {
    return (
      <div className="flex h-full items-center justify-center p-6 text-center">
        <p className="text-sm text-muted-foreground">{t("chat_panel.unsupported")}</p>
      </div>
    );
  }
  return (
    <WorkspaceTextEditor
      key={`${workspaceId}:${openFile.path}`}
      value={session.source}
      onChange={value => session.change(value)}
      readOnly={!pane.canEdit}
      fileName={openFile.name}
      className="h-full"
      centered={centered}
      tuiqiaoKey={`${workspaceId}:FILES:${openFile.path}`}
      previewContext={{
        workspaceId,
        area: "FILES",
        baseDir: parentWorkspacePath(openFile.path),
      }}
    />
  );
}
