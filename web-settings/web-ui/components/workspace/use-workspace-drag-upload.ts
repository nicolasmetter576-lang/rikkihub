import * as React from "react";

import { toast } from "sonner";
import { useTranslation } from "react-i18next";

import { reportSettingsError } from "@overlay/components/settings/settings-ui";
import type { WorkspaceStorageArea } from "@overlay/types/workspace";
import {
  collectDroppedUploads,
  filesToUploadItems,
  uploadWorkspaceItems,
} from "@overlay/lib/workspace-upload";
import type { CollectedUploads } from "@overlay/lib/workspace-upload";

/**
 * 工作区文件面板共享的拖拽上传逻辑：dragDepth 计数（处理嵌套子元素的
 * dragenter/dragleave）、drop 收集目录结构、上传到当前目录后回调刷新。
 * enabled=false 时所有 drag 处理都短路（如聊天面板打开文件时）。
 */
export function useWorkspaceDragUpload({
  workspaceId,
  area,
  path,
  enabled,
  onUploaded,
}: {
  workspaceId: string;
  area: WorkspaceStorageArea;
  path: string;
  enabled: boolean;
  onUploaded: () => Promise<void> | void;
}) {
  const { t } = useTranslation("workspace");
  const [dragActive, setDragActive] = React.useState(false);
  const dragDepthRef = React.useRef(0);
  const uploadRef = React.useRef<HTMLInputElement>(null);

  const uploadCollected = React.useCallback(
    async (collected: CollectedUploads) => {
      if (collected.items.length === 0 && collected.directories.length === 0) return;
      try {
        const { failed } = await uploadWorkspaceItems(workspaceId, area, path, collected);
        if (failed === 0) {
          toast.success(t("files.uploaded"));
        } else {
          reportSettingsError(
            new Error(t("files.upload_failed_count", { count: failed })),
            t("files.upload_failed"),
          );
        }
      } catch (error) {
        reportSettingsError(error, t("files.upload_failed"));
      }
      await onUploaded();
    },
    [area, onUploaded, path, t, workspaceId],
  );

  const handleDragEnter = React.useCallback(
    (event: React.DragEvent<HTMLDivElement>) => {
      if (!enabled || !event.dataTransfer.types.includes("Files")) return;
      event.preventDefault();
      event.stopPropagation();
      dragDepthRef.current += 1;
      setDragActive(true);
    },
    [enabled],
  );

  const handleDragOver = React.useCallback(
    (event: React.DragEvent<HTMLDivElement>) => {
      if (!enabled || !event.dataTransfer.types.includes("Files")) return;
      event.preventDefault();
      event.stopPropagation();
      event.dataTransfer.dropEffect = "copy";
    },
    [enabled],
  );

  const handleDragLeave = React.useCallback((event: React.DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.stopPropagation();
    dragDepthRef.current = Math.max(0, dragDepthRef.current - 1);
    if (dragDepthRef.current === 0) {
      setDragActive(false);
    }
  }, []);

  const handleDrop = React.useCallback(
    (event: React.DragEvent<HTMLDivElement>) => {
      if (!enabled || !event.dataTransfer.types.includes("Files")) return;
      event.preventDefault();
      event.stopPropagation();
      dragDepthRef.current = 0;
      setDragActive(false);
      const { dataTransfer } = event;
      void (async () => {
        const collected = await collectDroppedUploads(dataTransfer);
        await uploadCollected(collected);
      })();
    },
    [enabled, uploadCollected],
  );

  const handleFileInputChange = React.useCallback(
    (event: React.ChangeEvent<HTMLInputElement>) => {
      const items = filesToUploadItems(event.target.files ?? []);
      event.target.value = "";
      void uploadCollected({ items, directories: [] });
    },
    [uploadCollected],
  );

  return {
    dragActive,
    uploadRef,
    uploadCollected,
    dragHandlers: {
      onDragEnter: handleDragEnter,
      onDragOver: handleDragOver,
      onDragLeave: handleDragLeave,
      onDrop: handleDrop,
    },
    handleFileInputChange,
  };
}
