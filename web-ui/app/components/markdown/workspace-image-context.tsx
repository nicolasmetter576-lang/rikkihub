import * as React from "react";

import type { WorkspaceImageContext } from "~/lib/workspace-image";

/**
 * 当前内容所属会话的工作区环境：聊天消息里写成工作区路径的 Markdown 图片据此改到
 * `/api/workspaces/{id}/rootfs-file`。聊天区以外（工作区预览、设置页）不提供，行为不变。
 */
const WorkspaceImageReactContext = React.createContext<WorkspaceImageContext | null>(null);

export const WorkspaceImageProvider = WorkspaceImageReactContext.Provider;

export function useWorkspaceImages(): WorkspaceImageContext | null {
  return React.useContext(WorkspaceImageReactContext);
}
