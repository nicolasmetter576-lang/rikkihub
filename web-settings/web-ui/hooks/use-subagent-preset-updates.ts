import * as React from "react";
import { useQueryClient } from "@tanstack/react-query";
import { EVENT_SUBAGENT_PRESETS_UPDATE, EVENT_WORKSPACE_UPDATE, subscribeToEvent, type SubagentPresetsUpdateEvent } from "~/services/events";
import { invalidateSubagentPresetLibrary } from "@overlay/lib/subagent-preset-library";

/** Invalidation only: controlled unsaved editor drafts and their original CAS are never replaced. */
export function useSubagentPresetUpdates(): void {
  const queryClient = useQueryClient();
  React.useEffect(() => {
    const library = subscribeToEvent<SubagentPresetsUpdateEvent>(EVENT_SUBAGENT_PRESETS_UPDATE, () => invalidateSubagentPresetLibrary(queryClient));
    const workspaces = subscribeToEvent(EVENT_WORKSPACE_UPDATE, () => invalidateSubagentPresetLibrary(queryClient));
    return () => { library(); workspaces(); };
  }, [queryClient]);
}
