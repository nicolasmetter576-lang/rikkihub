import * as React from "react";
import { useQueryClient } from "@tanstack/react-query";

import { EVENT_WORKSPACE_UPDATE, subscribeToEvent } from "~/services/events";
import { invalidateWorkspaceQueriesEvent } from "@overlay/lib/workspace";
import type { WorkspaceUpdateEvent } from "@overlay/types/workspace";

/**
 * 订阅 `/api/events` 的 `workspace_update`：工作区行在 App/Web 任一侧变化时
 * invalidate React Query 的 detail/list 缓存，跨端修改自动可见。
 * 挂在 workspaces layout 上，整个 /workspaces* 路由树共享一次订阅。
 */
export function useWorkspaceUpdates(): void {
  const queryClient = useQueryClient();

  React.useEffect(
    () =>
      subscribeToEvent<WorkspaceUpdateEvent>(EVENT_WORKSPACE_UPDATE, (data) => {
        invalidateWorkspaceQueriesEvent(queryClient, data);
      }),
    [queryClient],
  );
}
