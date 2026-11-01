import * as React from "react";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { LoaderCircle, PackageIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";

import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import { usePickerPopover } from "~/hooks/use-picker-popover";
import { getDisplayName } from "~/lib/display";
import { extractErrorMessage } from "~/lib/error";
import { safeStringArray } from "~/lib/type-guards";
import { cn } from "~/lib/utils";
import api from "~/services/api";
import { useChatInputStore } from "~/stores";
import type { ConversationDto, LorebookProfile, ModeInjectionProfile, QuickMessage } from "~/types";
import { Button } from "~/components/ui/button";
import { Checkbox } from "~/components/ui/checkbox";
import { Popover, PopoverContent, PopoverTitle, PopoverTrigger } from "~/components/ui/popover";
import { skillsApi, skillsQueryKey, toggleSkillName } from "@overlay/lib/skills";

import { PickerErrorAlert } from "./picker-error-alert";

export interface ExtensionPickerButtonProps {
  disabled?: boolean;
  className?: string;
  conversationId?: string | null;
  conversationModeInjectionIds?: string[];
  conversationLorebookIds?: string[];
  draftKey?: string | null;
}

function getModeInjections(source: unknown): ModeInjectionProfile[] {
  if (!Array.isArray(source)) {
    return [];
  }

  return source.filter((item): item is ModeInjectionProfile =>
    Boolean(item && typeof item === "object" && typeof item.id === "string"),
  );
}

function getLorebooks(source: unknown): LorebookProfile[] {
  if (!Array.isArray(source)) {
    return [];
  }

  return source.filter((item): item is LorebookProfile =>
    Boolean(item && typeof item === "object" && typeof item.id === "string"),
  );
}

function getQuickMessages(source: unknown): QuickMessage[] {
  if (!Array.isArray(source)) {
    return [];
  }

  return source.filter((item): item is QuickMessage =>
    Boolean(
      item &&
      typeof item === "object" &&
      typeof item.id === "string" &&
      typeof item.content === "string" &&
      item.content.trim().length > 0,
    ),
  );
}

type ActiveTab = "quickmessages" | "mode" | "lorebook" | "skills";
/** Same four tabs as the App's ExtensionSelector, always shown (empty ones link to their manager). */
export const EXTENSION_TABS: ReadonlyArray<ActiveTab> = ["quickmessages", "mode", "lorebook", "skills"];
export const EXTENSION_MANAGE_LINKS: Record<ActiveTab, string> = {
  quickmessages: "/settings/extensions?tab=quick",
  mode: "/settings/extensions?tab=mode",
  lorebook: "/settings/extensions?tab=lorebook",
  skills: "/settings/skills",
};

function ExtensionEmptyState({
  message,
  to,
  onNavigate,
}: {
  message: string;
  to: string;
  onNavigate: () => void;
}) {
  const { t } = useTranslation("input");
  return (
    <div className="flex flex-col items-center gap-2 px-2 py-4 text-center text-sm text-muted-foreground">
      <span>{message}</span>
      <Link to={to} className="text-xs text-primary hover:underline" onClick={onNavigate}>
        {t("injection.go_manage")}
      </Link>
    </div>
  );
}
const EMPTY_ID_LIST: string[] = [];

export function ExtensionPickerButton({
  disabled = false,
  className,
  conversationId = null,
  conversationModeInjectionIds,
  conversationLorebookIds,
  draftKey = null,
}: ExtensionPickerButtonProps) {
  const { t } = useTranslation("input");
  const { settings, currentAssistant } = useCurrentAssistant();
  const draftModeInjectionIds = useChatInputStore(
    React.useCallback(
      (state) =>
        draftKey ? (state.drafts[draftKey]?.modeInjectionIds ?? EMPTY_ID_LIST) : EMPTY_ID_LIST,
      [draftKey],
    ),
  );
  const draftLorebookIds = useChatInputStore(
    React.useCallback(
      (state) =>
        draftKey ? (state.drafts[draftKey]?.lorebookIds ?? EMPTY_ID_LIST) : EMPTY_ID_LIST,
      [draftKey],
    ),
  );
  const setDraftPromptInjectionIds = useChatInputStore((state) => state.setPromptInjectionIds);

  const [activeTab, setActiveTab] = React.useState<ActiveTab>("quickmessages");

  const canUse = Boolean(settings && currentAssistant && !disabled);
  const { error, setError, popoverProps } = usePickerPopover(canUse);
  const queryClient = useQueryClient();
  // Skills live on disk, not in settings; the list refreshes on focus like the settings page.
  const skillsQuery = useQuery({
    queryKey: skillsQueryKey,
    queryFn: skillsApi.list,
    enabled: Boolean(settings && currentAssistant),
    staleTime: 30_000,
  });
  const skills = skillsQuery.data ?? [];
  const skillNameSet = React.useMemo(() => new Set(skills.map((skill) => skill.name)), [skills]);
  const selectedSkillNames = React.useMemo(
    () => safeStringArray(currentAssistant?.enabledSkills).filter((name) => skillNameSet.has(name)),
    [currentAssistant?.enabledSkills, skillNameSet],
  );

  const modeInjections = React.useMemo(
    () => getModeInjections(settings?.modeInjections),
    [settings?.modeInjections],
  );
  const lorebooks = React.useMemo(() => getLorebooks(settings?.lorebooks), [settings?.lorebooks]);
  const quickMessages = React.useMemo(
    () => getQuickMessages(settings?.quickMessages),
    [settings?.quickMessages],
  );

  const modeInjectionIdSet = React.useMemo(
    () => new Set(modeInjections.map((item) => item.id)),
    [modeInjections],
  );
  const lorebookIdSet = React.useMemo(() => new Set(lorebooks.map((item) => item.id)), [lorebooks]);
  const quickMessageIdSet = React.useMemo(
    () => new Set(quickMessages.map((item) => item.id)),
    [quickMessages],
  );

  const assistantModeInjectionIds = React.useMemo(
    () => safeStringArray(currentAssistant?.modeInjectionIds),
    [currentAssistant?.modeInjectionIds],
  );
  const assistantLorebookIds = React.useMemo(
    () => safeStringArray(currentAssistant?.lorebookIds),
    [currentAssistant?.lorebookIds],
  );
  const selectedQuickMessageIds = React.useMemo(
    () => safeStringArray(currentAssistant?.quickMessageIds),
    [currentAssistant?.quickMessageIds],
  );
  const useConversationInjections = currentAssistant?.allowConversationPromptInjection === true;
  const selectedModeInjectionIds = React.useMemo(
    () =>
      useConversationInjections
        ? safeStringArray(conversationModeInjectionIds ?? draftModeInjectionIds)
        : assistantModeInjectionIds,
    [
      assistantModeInjectionIds,
      conversationModeInjectionIds,
      draftModeInjectionIds,
      useConversationInjections,
    ],
  );
  const selectedLorebookIds = React.useMemo(
    () =>
      useConversationInjections
        ? safeStringArray(conversationLorebookIds ?? draftLorebookIds)
        : assistantLorebookIds,
    [assistantLorebookIds, conversationLorebookIds, draftLorebookIds, useConversationInjections],
  );

  const selectedCount =
    selectedModeInjectionIds.length +
    selectedLorebookIds.length +
    selectedQuickMessageIds.length +
    selectedSkillNames.length;
  React.useEffect(() => {
    if (!canUse) {
      popoverProps.onOpenChange(false);
    }
  }, [canUse]);

  React.useEffect(() => {
    if (quickMessages.length > 0) {
      setActiveTab("quickmessages");
    } else if (modeInjections.length > 0) {
      setActiveTab("mode");
    } else if (lorebooks.length > 0) {
      setActiveTab("lorebook");
    } else if (skills.length > 0) {
      setActiveTab("skills");
    }
  }, [quickMessages.length, modeInjections.length, lorebooks.length, skills.length]);

  const updateAssistantExtensionsMutation = useMutation({
    mutationFn: ({
      assistantId,
      modeInjectionIds,
      lorebookIds,
      quickMessageIds,
    }: {
      assistantId: string;
      modeInjectionIds: string[];
      lorebookIds: string[];
      quickMessageIds: string[];
      key: string;
    }) =>
      api.post<{ status: string }>("settings/assistant/injections", {
        assistantId,
        modeInjectionIds,
        lorebookIds,
        quickMessageIds,
      }),
    onError: (updateError) => {
      setError(extractErrorMessage(updateError, t("injection.update_failed")));
    },
    onSuccess: () => setError(null),
  });

  const updateConversationInjectionsMutation = useMutation({
    mutationFn: ({
      conversationId,
      modeInjectionIds,
      lorebookIds,
    }: {
      conversationId: string;
      modeInjectionIds: string[];
      lorebookIds: string[];
      key: string;
    }) =>
      api.post<ConversationDto>(`conversations/${conversationId}/injections`, {
        modeInjectionIds,
        lorebookIds,
      }),
    onError: (updateError) => {
      setError(extractErrorMessage(updateError, t("injection.update_failed")));
    },
    onSuccess: () => setError(null),
  });

  const updateSkillsMutation = useMutation({
    mutationFn: ({
      assistantId,
      enabledSkills,
    }: {
      assistantId: string;
      enabledSkills: string[];
      key: string;
    }) => skillsApi.setAssistantSkills(assistantId, enabledSkills),
    onError: (updateError) => {
      setError(extractErrorMessage(updateError, t("injection.skills_update_failed")));
    },
    onSuccess: () => {
      setError(null);
      void queryClient.invalidateQueries({ queryKey: skillsQueryKey, exact: true });
    },
  });

  const isUpdating =
    updateAssistantExtensionsMutation.isPending ||
    updateConversationInjectionsMutation.isPending ||
    updateSkillsMutation.isPending;

  const buildAssistantPayload = React.useCallback(
    (overrides: {
      modeInjectionIds?: string[];
      lorebookIds?: string[];
      quickMessageIds?: string[];
    }) => ({
      assistantId: currentAssistant!.id,
      modeInjectionIds:
        overrides.modeInjectionIds ??
        assistantModeInjectionIds.filter((id) => modeInjectionIdSet.has(id)),
      lorebookIds:
        overrides.lorebookIds ?? assistantLorebookIds.filter((id) => lorebookIdSet.has(id)),
      quickMessageIds:
        overrides.quickMessageIds ??
        selectedQuickMessageIds.filter((id) => quickMessageIdSet.has(id)),
    }),
    [
      assistantLorebookIds,
      assistantModeInjectionIds,
      currentAssistant,
      lorebookIdSet,
      modeInjectionIdSet,
      quickMessageIdSet,
      selectedQuickMessageIds,
    ],
  );

  const updatePromptInjections = React.useCallback(
    (key: string, modeInjectionIds: string[], lorebookIds: string[]) => {
      if (useConversationInjections) {
        if (conversationId) {
          updateConversationInjectionsMutation.mutate({
            conversationId,
            modeInjectionIds,
            lorebookIds,
            key,
          });
        } else if (draftKey) {
          setDraftPromptInjectionIds(draftKey, {
            modeInjectionIds,
            lorebookIds,
          });
          setError(null);
        }
        return;
      }

      updateAssistantExtensionsMutation.mutate({
        ...buildAssistantPayload({ modeInjectionIds, lorebookIds }),
        key,
      });
    },
    [
      buildAssistantPayload,
      conversationId,
      draftKey,
      setDraftPromptInjectionIds,
      setError,
      updateAssistantExtensionsMutation,
      updateConversationInjectionsMutation,
      useConversationInjections,
    ],
  );

  const handleToggleModeInjection = React.useCallback(
    (id: string, checked: boolean) => {
      if (!canUse || !currentAssistant) return;
      const nextIds = new Set(
        selectedModeInjectionIds.filter((item) => modeInjectionIdSet.has(item)),
      );
      if (checked) nextIds.add(id);
      else nextIds.delete(id);
      updatePromptInjections(
        `mode:${id}`,
        Array.from(nextIds),
        selectedLorebookIds.filter((item) => lorebookIdSet.has(item)),
      );
    },
    [
      canUse,
      currentAssistant,
      lorebookIdSet,
      modeInjectionIdSet,
      selectedLorebookIds,
      selectedModeInjectionIds,
      updatePromptInjections,
    ],
  );

  const handleToggleLorebook = React.useCallback(
    (id: string, checked: boolean) => {
      if (!canUse || !currentAssistant) return;
      const nextIds = new Set(selectedLorebookIds.filter((item) => lorebookIdSet.has(item)));
      if (checked) nextIds.add(id);
      else nextIds.delete(id);
      updatePromptInjections(
        `lorebook:${id}`,
        selectedModeInjectionIds.filter((item) => modeInjectionIdSet.has(item)),
        Array.from(nextIds),
      );
    },
    [
      canUse,
      currentAssistant,
      lorebookIdSet,
      modeInjectionIdSet,
      selectedLorebookIds,
      selectedModeInjectionIds,
      updatePromptInjections,
    ],
  );

  const handleToggleQuickMessage = React.useCallback(
    (id: string, checked: boolean) => {
      if (!canUse || !currentAssistant) return;
      const nextIds = new Set(
        selectedQuickMessageIds.filter((item) => quickMessageIdSet.has(item)),
      );
      if (checked) nextIds.add(id);
      else nextIds.delete(id);
      updateAssistantExtensionsMutation.mutate({
        ...buildAssistantPayload({ quickMessageIds: Array.from(nextIds) }),
        key: `quickmessage:${id}`,
      });
    },
    [
      buildAssistantPayload,
      canUse,
      currentAssistant,
      quickMessageIdSet,
      selectedQuickMessageIds,
      updateAssistantExtensionsMutation,
    ],
  );

  const handleToggleSkill = (name: string, checked: boolean) => {
    if (!canUse || !currentAssistant) return;
    updateSkillsMutation.mutate({
      assistantId: currentAssistant.id,
      enabledSkills: toggleSkillName(selectedSkillNames, skillNameSet, name, checked),
      key: `skill:${name}`,
    });
  };

  return (
    <Popover {...popoverProps}>
      <PopoverTrigger asChild>
        <Button
          type="button"
          aria-label={t("injection.title")}
          title={t("injection.title")}
          variant="ghost"
          size="sm"
          disabled={!canUse || isUpdating}
          className={cn(
            "h-8 rounded-full px-2 text-muted-foreground hover:text-foreground",
            selectedCount > 0 && "text-primary hover:bg-primary/10",
            className,
          )}
        >
          {isUpdating ? (
            <LoaderCircle className="size-4 animate-spin" />
          ) : (
            <PackageIcon className="size-4" />
          )}
          {selectedCount > 0 ? (
            <span className="rounded-full bg-primary/10 px-1.5 py-0.5 text-[10px] text-primary">
              {selectedCount}
            </span>
          ) : null}
        </Button>
      </PopoverTrigger>

      <PopoverContent
        side="top"
        align="end"
        sideOffset={8}
        className="w-[min(92vw,20rem)] max-h-[var(--radix-popover-content-available-height)] overflow-y-auto space-y-3 p-4"
      >
        <PopoverTitle className="text-sm">{t("injection.title")}</PopoverTitle>

        <div className="space-y-3">
          <PickerErrorAlert error={error} />

          <div className="bg-muted flex flex-wrap rounded-xl p-1" role="tablist">
            {EXTENSION_TABS.map((tab) => (
              <button
                key={tab}
                type="button"
                role="tab"
                aria-selected={activeTab === tab}
                className={cn(
                  "rounded-full px-3 py-1 text-xs transition",
                  activeTab === tab
                    ? "bg-background text-foreground shadow-sm"
                    : "text-muted-foreground",
                )}
                onClick={() => {
                  setActiveTab(tab);
                }}
              >
                {t(`injection.tab_${tab}`)}
              </button>
            ))}
          </div>

          <div className="max-h-64 overflow-y-auto">
            {activeTab === "skills" ? (
              skills.length > 0 ? (
                <div className="space-y-2">
                  {skills.map((skill) => {
                    const checked = selectedSkillNames.includes(skill.name);
                    const switching =
                      updateSkillsMutation.isPending &&
                      updateSkillsMutation.variables?.key === `skill:${skill.name}`;

                    return (
                      <label
                        key={skill.name}
                        className={cn(
                          "flex cursor-pointer items-center gap-2 rounded-lg px-2 py-2 transition hover:bg-muted",
                          checked && "bg-primary/5",
                        )}
                      >
                        {switching ? (
                          <LoaderCircle className="size-4 animate-spin" />
                        ) : (
                          <Checkbox
                            checked={checked}
                            disabled={disabled || isUpdating}
                            onCheckedChange={(nextChecked) => {
                              handleToggleSkill(skill.name, Boolean(nextChecked));
                            }}
                          />
                        )}
                        <div className="min-w-0">
                          <div className="truncate text-sm font-medium">{skill.name}</div>
                          {skill.description ? (
                            <div className="text-muted-foreground mt-0.5 line-clamp-1 text-xs">
                              {skill.description}
                            </div>
                          ) : null}
                        </div>
                      </label>
                    );
                  })}
                </div>
              ) : (
                <ExtensionEmptyState
                  message={t("injection.empty_skills")}
                  to={EXTENSION_MANAGE_LINKS.skills}
                  onNavigate={() => popoverProps.onOpenChange(false)}
                />
              )
            ) : activeTab === "quickmessages" ? (
              quickMessages.length > 0 ? (
                <div className="space-y-2">
                  {quickMessages.map((item) => {
                    const checked = selectedQuickMessageIds.includes(item.id);
                    const switching =
                      updateAssistantExtensionsMutation.isPending &&
                      updateAssistantExtensionsMutation.variables?.key ===
                        `quickmessage:${item.id}`;

                    return (
                      <label
                        key={item.id}
                        className={cn(
                          "flex cursor-pointer items-center gap-2 rounded-lg px-2 py-2 transition hover:bg-muted",
                          checked && "bg-primary/5",
                        )}
                      >
                        {switching ? (
                          <LoaderCircle className="size-4 animate-spin" />
                        ) : (
                          <Checkbox
                            checked={checked}
                            disabled={disabled || isUpdating}
                            onCheckedChange={(nextChecked) => {
                              handleToggleQuickMessage(item.id, Boolean(nextChecked));
                            }}
                          />
                        )}
                        <div className="min-w-0">
                          <div className="truncate text-sm font-medium">
                            {getDisplayName(item.title, t("injection.unnamed_quickmessage"))}
                          </div>
                          <div className="text-muted-foreground mt-0.5 line-clamp-1 text-xs">
                            {item.content}
                          </div>
                        </div>
                      </label>
                    );
                  })}
                </div>
              ) : (
                <ExtensionEmptyState
                  message={t("injection.empty_quickmessages")}
                  to={EXTENSION_MANAGE_LINKS.quickmessages}
                  onNavigate={() => popoverProps.onOpenChange(false)}
                />
              )
            ) : activeTab === "mode" ? (
              modeInjections.length > 0 ? (
                <div className="space-y-2">
                  {modeInjections.map((item) => {
                    const checked = selectedModeInjectionIds.includes(item.id);
                    const switching =
                      useConversationInjections && conversationId != null
                        ? updateConversationInjectionsMutation.isPending &&
                          updateConversationInjectionsMutation.variables?.key === `mode:${item.id}`
                        : updateAssistantExtensionsMutation.isPending &&
                          updateAssistantExtensionsMutation.variables?.key === `mode:${item.id}`;

                    return (
                      <label
                        key={item.id}
                        className={cn(
                          "flex cursor-pointer items-center gap-2 rounded-lg px-2 py-2 transition hover:bg-muted",
                          checked && "bg-primary/5",
                        )}
                      >
                        {switching ? (
                          <LoaderCircle className="size-4 animate-spin" />
                        ) : (
                          <Checkbox
                            checked={checked}
                            disabled={disabled || isUpdating}
                            onCheckedChange={(nextChecked) => {
                              handleToggleModeInjection(item.id, Boolean(nextChecked));
                            }}
                          />
                        )}
                        <div className="min-w-0">
                          <div className="truncate text-sm font-medium">
                            {getDisplayName(item.name, t("injection.unnamed_mode"))}
                          </div>
                          {item.enabled === false ? (
                            <div className="text-muted-foreground mt-0.5 text-xs">
                              {t("injection.disabled")}
                            </div>
                          ) : null}
                        </div>
                      </label>
                    );
                  })}
                </div>
              ) : (
                <ExtensionEmptyState
                  message={t("injection.empty_mode")}
                  to={EXTENSION_MANAGE_LINKS.mode}
                  onNavigate={() => popoverProps.onOpenChange(false)}
                />
              )
            ) : lorebooks.length > 0 ? (
              <div className="space-y-2">
                {lorebooks.map((item) => {
                  const checked = selectedLorebookIds.includes(item.id);
                  const switching =
                    useConversationInjections && conversationId != null
                      ? updateConversationInjectionsMutation.isPending &&
                        updateConversationInjectionsMutation.variables?.key ===
                          `lorebook:${item.id}`
                      : updateAssistantExtensionsMutation.isPending &&
                        updateAssistantExtensionsMutation.variables?.key === `lorebook:${item.id}`;

                  return (
                    <label
                      key={item.id}
                      className={cn(
                        "flex cursor-pointer items-center gap-2 rounded-lg px-2 py-2 transition hover:bg-muted",
                        checked && "bg-primary/5",
                      )}
                    >
                      {switching ? (
                        <LoaderCircle className="size-4 animate-spin" />
                      ) : (
                        <Checkbox
                          checked={checked}
                          disabled={disabled || isUpdating}
                          onCheckedChange={(nextChecked) => {
                            handleToggleLorebook(item.id, Boolean(nextChecked));
                          }}
                        />
                      )}
                      <div className="min-w-0">
                        <div className="truncate text-sm font-medium">
                          {getDisplayName(item.name, t("injection.unnamed_lorebook"))}
                        </div>
                        {typeof item.description === "string" &&
                        item.description.trim().length > 0 ? (
                          <div className="text-muted-foreground mt-0.5 line-clamp-1 text-xs">
                            {item.description}
                          </div>
                        ) : null}
                        {item.enabled === false ? (
                          <div className="text-muted-foreground mt-0.5 text-xs">
                            {t("injection.disabled")}
                          </div>
                        ) : null}
                      </div>
                    </label>
                  );
                })}
              </div>
            ) : (
              <ExtensionEmptyState
                message={t("injection.empty_lorebook")}
                to={EXTENSION_MANAGE_LINKS.lorebook}
                onNavigate={() => popoverProps.onOpenChange(false)}
              />
            )}
          </div>
        </div>
      </PopoverContent>
    </Popover>
  );
}
