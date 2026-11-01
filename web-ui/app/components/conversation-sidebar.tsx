import * as React from "react";

import dayjs from "dayjs";
import type { TFunction } from "i18next";
import { toast } from "sonner";
import {
  Check,
  Folder,
  FolderInput,
  FolderPlus,
  Globe,
  HardDrive,
  Laptop,
  Languages,
  Moon,
  MoreHorizontal,
  MoveRight,
  Palette,
  PanelLeftClose,
  PanelLeftOpen,
  ArrowUp,
  Pencil,
  Pin,
  PinOff,
  Plus,
  RefreshCw,
  LogOut,
  Settings,
  FolderKanban,
  Sun,
  SwatchBook,
  Trash2,
} from "lucide-react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";

import { InfiniteScrollArea } from "~/components/extended/infinite-scroll-area";
import { Badge } from "~/components/ui/badge";
import { Button } from "~/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "~/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from "~/components/ui/dropdown-menu";
import { ScrollArea } from "~/components/ui/scroll-area";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupLabel,
  SidebarMenu,
  SidebarMenuAction,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarRail,
  useSidebar,
} from "~/components/ui/sidebar";
import { UIAvatar } from "~/components/ui/ui-avatar";
import {
  useTheme,
  type ColorTheme,
  type CustomThemeCss,
  type Theme,
} from "~/components/theme-provider";
import { ConversationFavoritesButton } from "~/components/conversation-favorites-button";
import { ConversationSearchButton } from "~/components/conversation-search-button";
import {
  SIDEBAR_ICON_MODE_BUTTON_CLASS,
  SIDEBAR_ICON_MODE_HIDDEN_CLASS,
  SidebarIconTooltip,
} from "~/components/sidebar-icon-mode";
import { ConversationRail } from "~/components/conversation-rail";
import { CustomThemeDialog } from "~/components/custom-theme-dialog";
import { getAssistantDisplayName } from "~/lib/display";
import { cn } from "~/lib/utils";
import { logoutWebAuth } from "~/services/api";
import type {
  AssistantAvatar,
  AssistantProfile,
  AssistantTag,
  ConversationListDto,
  FolderDto,
} from "~/types";

const THEME_OPTIONS: Array<{
  value: Theme;
  labelKey: string;
  icon: React.ComponentType<{ className?: string }>;
}> = [
  {
    value: "light",
    labelKey: "theme_light",
    icon: Sun,
  },
  {
    value: "dark",
    labelKey: "theme_dark",
    icon: Moon,
  },
  {
    value: "system",
    labelKey: "theme_system",
    icon: Laptop,
  },
];

const COLOR_THEME_OPTIONS: Array<{
  value: ColorTheme;
  labelKey: string;
}> = [
  {
    value: "default",
    labelKey: "color_default",
  },
  {
    value: "claude",
    labelKey: "color_claude",
  },
  {
    value: "t3-chat",
    labelKey: "color_t3_chat",
  },
  {
    value: "mono",
    labelKey: "color_mono",
  },
  {
    value: "bubblegum",
    labelKey: "color_bubblegum",
  },
  {
    value: "custom",
    labelKey: "color_custom",
  },
];

const LANGUAGE_OPTIONS = [
  { value: "zh-CN", label: "简体中文" },
  { value: "en-US", label: "English" },
] as const;

type ConversationListItem =
  | { type: "pinned-header" }
  | { type: "date-header"; date: string; label: string }
  | { type: "item"; conversation: ConversationListDto };

function getDateLabel(date: dayjs.Dayjs, t: TFunction): string {
  const today = dayjs().startOf("day");
  const yesterday = today.subtract(1, "day");

  if (date.isSame(today, "day")) return t("conversation_sidebar.today");
  if (date.isSame(yesterday, "day")) return t("conversation_sidebar.yesterday");

  const native = date.toDate();
  const sameYear = date.year() === today.year();
  const formatter = new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "numeric",
    ...(sameYear ? {} : { year: "numeric" }),
  });
  return formatter.format(native);
}

function groupConversations(
  conversations: ConversationListDto[],
  t: TFunction,
): ConversationListItem[] {
  const items: ConversationListItem[] = [];
  const pinned = conversations.filter((c) => c.isPinned);
  const unpinned = conversations.filter((c) => !c.isPinned);

  if (pinned.length > 0) {
    items.push({ type: "pinned-header" });
    for (const c of pinned) {
      items.push({ type: "item", conversation: c });
    }
  }

  let lastDate: string | null = null;
  for (const c of unpinned) {
    const date = dayjs(c.updateAt).startOf("day");
    const dateKey = date.format("YYYY-MM-DD");
    if (dateKey !== lastDate) {
      items.push({ type: "date-header", date: dateKey, label: getDateLabel(date, t) });
      lastDate = dateKey;
    }
    items.push({ type: "item", conversation: c });
  }

  return items;
}

export interface ConversationSidebarProps {
  conversations: ConversationListDto[];
  activeId: string | null;
  loading: boolean;
  error: string | null;
  hasMore: boolean;
  loadMore: () => void;
  userName: string;
  userAvatar?: AssistantAvatar | null;
  assistants: AssistantProfile[];
  assistantTags: AssistantTag[];
  currentAssistantId: string | null;
  onSelect: (id: string, nodeId?: string) => void;
  onAssistantChange: (assistantId: string) => Promise<void>;
  onPin?: (id: string) => Promise<void>;
  onRegenerateTitle?: (id: string) => Promise<void>;
  onMoveToAssistant?: (id: string, assistantId: string) => Promise<void>;
  onUpdateTitle?: (id: string, title: string) => Promise<void>;
  onDelete?: (id: string) => Promise<void>;
  onCreateConversation?: () => void;
  webAuthEnabled?: boolean;
  folders?: FolderDto[];
  selectedFolderId?: string | null;
  onSelectFolder?: (folderId: string | null) => void;
  onCreateFolder?: (name: string) => Promise<void>;
  onRenameFolder?: (id: string, name: string) => Promise<void>;
  onDeleteFolder?: (id: string) => Promise<void>;
  onMoveToFolder?: (conversationId: string, folderId: string | null) => Promise<void>;
}

interface ConversationListRowProps {
  conversation: ConversationListDto;
  isActive: boolean;
  assistants: AssistantProfile[];
  folders: FolderDto[];
  onSelect: (id: string) => void;
  onPin?: (id: string) => Promise<void>;
  onRegenerateTitle?: (id: string) => Promise<void>;
  onMoveToAssistant?: (id: string, assistantId: string) => Promise<void>;
  onMoveToFolder?: (conversationId: string, folderId: string | null) => Promise<void>;
  onUpdateTitle?: (id: string, title: string) => Promise<void>;
  onDelete?: (id: string) => Promise<void>;
}

const ConversationListRow = React.memo(
  ({
    conversation,
    isActive,
    assistants,
    folders,
    onSelect,
    onPin,
    onRegenerateTitle,
    onMoveToAssistant,
    onMoveToFolder,
    onUpdateTitle,
    onDelete,
  }: ConversationListRowProps) => {
    const { t } = useTranslation();
    const [menuOpen, setMenuOpen] = React.useState(false);
    const [pendingAction, setPendingAction] = React.useState<string | null>(null);

    const moveTargets = React.useMemo(
      () => assistants.filter((assistant) => assistant.id !== conversation.assistantId),
      [assistants, conversation.assistantId],
    );

    const hasMenuAction = Boolean(
      onPin ||
      onRegenerateTitle ||
      onMoveToAssistant ||
      onMoveToFolder ||
      onUpdateTitle ||
      onDelete,
    );

    const runAction = React.useCallback(
      async (
        actionId: string,
        action: () => Promise<void>,
        messages?: { success?: string; error?: string },
      ) => {
        setPendingAction(actionId);
        try {
          await action();
          setMenuOpen(false);
          if (messages?.success) {
            toast.success(messages.success);
          }
        } catch (error) {
          console.error("Conversation action failed", error);
          toast.error(messages?.error ?? t("conversation_sidebar.action_failed_retry"));
        } finally {
          setPendingAction(null);
        }
      },
      [t],
    );
    return (
      <SidebarMenuItem>
        <DropdownMenu open={menuOpen} onOpenChange={setMenuOpen}>
          <SidebarMenuButton
            isActive={isActive}
            onClick={() => onSelect(conversation.id)}
            onContextMenu={(event) => {
              if (!hasMenuAction) return;
              event.preventDefault();
              setMenuOpen(true);
            }}
          >
            <span className="flex w-full items-center gap-2">
              <span className="flex-1 truncate">
                {conversation.title || t("conversation_sidebar.unnamed_conversation")}
              </span>
              {conversation.isPinned && <Pin className="size-3 text-primary" aria-hidden />}
              {conversation.isGenerating && (
                <span
                  className="inline-block size-2 rounded-full bg-emerald-500"
                  aria-label={t("conversation_sidebar.generating")}
                  title={t("conversation_sidebar.generating")}
                />
              )}
            </span>
          </SidebarMenuButton>

          {hasMenuAction && (
            <>
              <DropdownMenuTrigger asChild>
                <SidebarMenuAction
                  showOnHover
                  aria-label={t("conversation_sidebar.conversation_actions")}
                  title={t("conversation_sidebar.conversation_actions")}
                  disabled={pendingAction !== null}
                  onClick={(event) => {
                    event.stopPropagation();
                  }}
                >
                  <MoreHorizontal className="size-4" />
                </SidebarMenuAction>
              </DropdownMenuTrigger>
              <DropdownMenuContent side="right" align="start" className="w-48">
                {onPin && (
                  <DropdownMenuItem
                    disabled={pendingAction !== null}
                    onSelect={(event) => {
                      event.preventDefault();
                      void runAction(
                        "pin",
                        async () => {
                          await onPin(conversation.id);
                        },
                        {
                          success: conversation.isPinned
                            ? t("conversation_sidebar.unpin_success")
                            : t("conversation_sidebar.pin_success"),
                          error: conversation.isPinned
                            ? t("conversation_sidebar.unpin_failed")
                            : t("conversation_sidebar.pin_failed"),
                        },
                      );
                    }}
                  >
                    {conversation.isPinned ? (
                      <PinOff className="size-4" />
                    ) : (
                      <Pin className="size-4" />
                    )}
                    <span>
                      {conversation.isPinned
                        ? t("conversation_sidebar.unpin")
                        : t("conversation_sidebar.pin")}
                    </span>
                  </DropdownMenuItem>
                )}

                {onRegenerateTitle && (
                  <DropdownMenuItem
                    disabled={pendingAction !== null}
                    onSelect={(event) => {
                      event.preventDefault();
                      void runAction(
                        "regenerate-title",
                        async () => {
                          await onRegenerateTitle(conversation.id);
                        },
                        {
                          success: t("conversation_sidebar.regenerate_title_success"),
                          error: t("conversation_sidebar.regenerate_title_failed"),
                        },
                      );
                    }}
                  >
                    <RefreshCw className="size-4" />
                    <span>{t("conversation_sidebar.regenerate_title")}</span>
                  </DropdownMenuItem>
                )}

                {onUpdateTitle && (
                  <DropdownMenuItem
                    disabled={pendingAction !== null}
                    onSelect={(event) => {
                      event.preventDefault();
                      const nextTitle = window
                        .prompt(t("conversation_sidebar.edit_title_prompt"), conversation.title)
                        ?.trim();
                      if (nextTitle == null) {
                        return;
                      }
                      if (nextTitle.length === 0) {
                        toast.error(t("conversation_sidebar.title_empty"));
                        return;
                      }
                      if (nextTitle === conversation.title) {
                        return;
                      }
                      void runAction(
                        "update-title",
                        async () => {
                          await onUpdateTitle(conversation.id, nextTitle);
                        },
                        {
                          success: t("conversation_sidebar.title_updated"),
                          error: t("conversation_sidebar.title_update_failed"),
                        },
                      );
                    }}
                  >
                    <Pencil className="size-4" />
                    <span>{t("conversation_sidebar.edit_title")}</span>
                  </DropdownMenuItem>
                )}

                {onMoveToAssistant && (
                  <DropdownMenuSub>
                    <DropdownMenuSubTrigger
                      disabled={pendingAction !== null || moveTargets.length === 0}
                    >
                      <MoveRight className="size-4" />
                      <span>{t("conversation_sidebar.move_to_assistant")}</span>
                    </DropdownMenuSubTrigger>
                    <DropdownMenuSubContent>
                      {moveTargets.length === 0 ? (
                        <DropdownMenuItem disabled>
                          {t("conversation_sidebar.no_available_assistants")}
                        </DropdownMenuItem>
                      ) : (
                        moveTargets.map((assistant) => (
                          <DropdownMenuItem
                            key={assistant.id}
                            disabled={pendingAction !== null}
                            onSelect={(event) => {
                              event.preventDefault();
                              void runAction(
                                `move:${assistant.id}`,
                                async () => {
                                  await onMoveToAssistant(conversation.id, assistant.id);
                                },
                                {
                                  success: t("conversation_sidebar.moved_to_assistant", {
                                    assistant: getAssistantDisplayName(assistant.name),
                                  }),
                                  error: t("conversation_sidebar.move_conversation_failed"),
                                },
                              );
                            }}
                          >
                            {getAssistantDisplayName(assistant.name)}
                          </DropdownMenuItem>
                        ))
                      )}
                    </DropdownMenuSubContent>
                  </DropdownMenuSub>
                )}

                {onMoveToFolder && (
                  <DropdownMenuSub>
                    <DropdownMenuSubTrigger disabled={pendingAction !== null}>
                      <FolderInput className="size-4" />
                      <span>{t("conversation_sidebar.move_to_folder")}</span>
                    </DropdownMenuSubTrigger>
                    <DropdownMenuSubContent>
                      <DropdownMenuItem
                        disabled={pendingAction !== null || !conversation.folderId}
                        onSelect={(event) => {
                          event.preventDefault();
                          void runAction(
                            "move-folder:none",
                            async () => {
                              await onMoveToFolder(conversation.id, null);
                            },
                            {
                              success: t("conversation_sidebar.moved_to_folder_none"),
                              error: t("conversation_sidebar.move_conversation_failed"),
                            },
                          );
                        }}
                      >
                        <span>{t("conversation_sidebar.remove_from_folder")}</span>
                      </DropdownMenuItem>
                      {folders.length > 0 && <DropdownMenuSeparator />}
                      {folders.map((folder) => (
                        <DropdownMenuItem
                          key={folder.id}
                          disabled={pendingAction !== null || folder.id === conversation.folderId}
                          onSelect={(event) => {
                            event.preventDefault();
                            void runAction(
                              `move-folder:${folder.id}`,
                              async () => {
                                await onMoveToFolder(conversation.id, folder.id);
                              },
                              {
                                success: t("conversation_sidebar.moved_to_folder", {
                                  folder: folder.name,
                                }),
                                error: t("conversation_sidebar.move_conversation_failed"),
                              },
                            );
                          }}
                        >
                          <Folder className="size-4" />
                          <span className="truncate">{folder.name}</span>
                          {folder.id === conversation.folderId && (
                            <Check className="ml-auto size-4" />
                          )}
                        </DropdownMenuItem>
                      ))}
                    </DropdownMenuSubContent>
                  </DropdownMenuSub>
                )}

                {onDelete && (
                  <>
                    <DropdownMenuSeparator />
                    <DropdownMenuItem
                      variant="destructive"
                      disabled={pendingAction !== null}
                      onSelect={(event) => {
                        event.preventDefault();
                        if (!window.confirm(t("conversation_sidebar.delete_confirm"))) {
                          return;
                        }
                        void runAction(
                          "delete",
                          async () => {
                            await onDelete(conversation.id);
                          },
                          {
                            success: t("conversation_sidebar.delete_success"),
                            error: t("conversation_sidebar.delete_failed"),
                          },
                        );
                      }}
                    >
                      <Trash2 className="size-4" />
                      <span>{t("conversation_sidebar.delete_conversation")}</span>
                    </DropdownMenuItem>
                  </>
                )}
              </DropdownMenuContent>
            </>
          )}
        </DropdownMenu>
      </SidebarMenuItem>
    );
  },
);

function resolveLanguage(language: string): (typeof LANGUAGE_OPTIONS)[number]["value"] {
  return language.startsWith("zh") ? "zh-CN" : "en-US";
}

const FOOTER_LINKS: Array<{
  to: string;
  labelKey: string;
  icon: React.ComponentType<{ className?: string }>;
}> = [
  { to: "/settings", labelKey: "conversation_sidebar.open_settings", icon: Settings },
  { to: "/workspaces", labelKey: "conversation_sidebar.open_workspaces", icon: FolderKanban },
  { to: "/translator", labelKey: "settings:nav.translator", icon: Globe },
  { to: "/storage", labelKey: "settings:nav.storage", icon: HardDrive },
];

// 宽度按底部那一行算：6 个 32px 按钮 + 5 个 4px 间距 + 两组之间多留 4px + 左右内边距 1rem + 右边框 1px，
// 不留空格。改底部按钮数要同步改这里（测试里有对照）
export const SIDEBAR_FOOTER_BUTTONS = 6;
export const CONVERSATION_SIDEBAR_WIDTH = `calc(${SIDEBAR_FOOTER_BUTTONS} * 2rem + ${SIDEBAR_FOOTER_BUTTONS} * 0.25rem + 1rem + 1px)`;
export const CONVERSATION_SIDEBAR_STYLE = {
  "--sidebar-width": CONVERSATION_SIDEBAR_WIDTH,
  // 图标栏右边框占 1px，加回来才放得下 32px 按钮
  "--sidebar-width-icon": "calc(3rem + 1px)",
} as React.CSSProperties;

interface DisplayMenuProps {
  webAuthEnabled: boolean;
  onEditCustomCss: () => void;
  onWebLogout: () => void;
}

// 明暗、配色、语言、退出 Web 会话合在一个菜单里，底部才排得下一行
function DisplayMenu({ webAuthEnabled, onEditCustomCss, onWebLogout }: DisplayMenuProps) {
  const { t, i18n } = useTranslation();
  const { theme, setTheme, colorTheme, setColorTheme } = useTheme();
  const { state } = useSidebar();

  const currentLanguage = resolveLanguage(i18n.resolvedLanguage ?? i18n.language);
  const currentLanguageOption =
    LANGUAGE_OPTIONS.find((option) => option.value === currentLanguage) ?? LANGUAGE_OPTIONS[0];
  const currentColorThemeOption =
    COLOR_THEME_OPTIONS.find((option) => option.value === colorTheme) ?? COLOR_THEME_OPTIONS[0];
  const label = t("conversation_sidebar.display_settings");

  return (
    <DropdownMenu>
      <SidebarIconTooltip label={label}>
        <DropdownMenuTrigger asChild>
          <Button
            variant="outline"
            size="icon-sm"
            className="text-foreground"
            type="button"
            aria-label={label}
          >
            <Palette className="size-4" />
          </Button>
        </DropdownMenuTrigger>
      </SidebarIconTooltip>
      <DropdownMenuContent
        className="min-w-52"
        side={state === "collapsed" ? "right" : "top"}
        align="end"
      >
        <DropdownMenuLabel>{t("conversation_sidebar.theme_mode")}</DropdownMenuLabel>
        {THEME_OPTIONS.map((option) => {
          const selected = option.value === theme;
          const ThemeOptionIcon = option.icon;
          return (
            <DropdownMenuItem
              key={option.value}
              onClick={() => {
                setTheme(option.value);
              }}
            >
              <ThemeOptionIcon className="size-4" />
              <span className="flex-1">{t(`conversation_sidebar.${option.labelKey}`)}</span>
              <Check className={selected ? "size-4" : "size-4 opacity-0"} />
            </DropdownMenuItem>
          );
        })}
        <DropdownMenuSeparator />
        <DropdownMenuSub>
          <DropdownMenuSubTrigger className="whitespace-nowrap">
            <SwatchBook className="size-4" />
            <span className="flex-1">{t("conversation_sidebar.theme_color")}</span>
            <span className="text-xs text-muted-foreground">
              {t(`conversation_sidebar.${currentColorThemeOption.labelKey}`)}
            </span>
          </DropdownMenuSubTrigger>
          <DropdownMenuSubContent className="w-44">
            {COLOR_THEME_OPTIONS.map((option) => {
              const selected = option.value === colorTheme;
              return (
                <DropdownMenuItem
                  key={option.value}
                  onClick={() => {
                    setColorTheme(option.value);
                  }}
                >
                  <span className="flex-1">{t(`conversation_sidebar.${option.labelKey}`)}</span>
                  <Check className={selected ? "size-4" : "size-4 opacity-0"} />
                </DropdownMenuItem>
              );
            })}
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={onEditCustomCss}>
              <span className="flex-1">{t("conversation_sidebar.edit_custom_css")}</span>
            </DropdownMenuItem>
          </DropdownMenuSubContent>
        </DropdownMenuSub>
        <DropdownMenuSub>
          <DropdownMenuSubTrigger className="whitespace-nowrap">
            <Languages className="size-4" />
            <span className="flex-1">{t("conversation_sidebar.language")}</span>
            <span className="text-xs text-muted-foreground">{currentLanguageOption.label}</span>
          </DropdownMenuSubTrigger>
          <DropdownMenuSubContent className="w-40">
            {LANGUAGE_OPTIONS.map((option) => {
              const selected = option.value === currentLanguage;
              return (
                <DropdownMenuItem
                  key={option.value}
                  onClick={() => {
                    void i18n.changeLanguage(option.value);
                  }}
                >
                  <span className="flex-1">{option.label}</span>
                  <Check className={selected ? "size-4" : "size-4 opacity-0"} />
                </DropdownMenuItem>
              );
            })}
          </DropdownMenuSubContent>
        </DropdownMenuSub>
        {webAuthEnabled && (
          <>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={onWebLogout}>
              <LogOut className="size-4" />
              <span className="flex-1">{t("conversation_sidebar.clear_web_session")}</span>
            </DropdownMenuItem>
          </>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function SidebarCollapseButton() {
  const { t } = useTranslation();
  const { state, isMobile, toggleSidebar } = useSidebar();
  // 手机上侧边栏是抽屉：看得见就是开着的，点它关掉抽屉（底部也凑满 6 个按钮）
  const collapsed = !isMobile && state === "collapsed";
  const label = collapsed
    ? t("conversation_sidebar.expand_sidebar")
    : t("conversation_sidebar.collapse_sidebar");
  const Icon = collapsed ? PanelLeftOpen : PanelLeftClose;

  return (
    <SidebarIconTooltip label={label}>
      <Button
        variant="ghost"
        size="icon-sm"
        className="text-foreground"
        type="button"
        aria-label={label}
        aria-expanded={!collapsed}
        onClick={toggleSidebar}
      >
        <Icon className="size-4" />
      </Button>
    </SidebarIconTooltip>
  );
}

interface FolderBarProps {
  folders: FolderDto[];
  selectedFolderId: string | null;
  onSelect: (folderId: string | null) => void;
  onCreate?: (name: string) => Promise<void>;
  onRename?: (id: string, name: string) => Promise<void>;
  onDelete?: (id: string) => Promise<void>;
}

function FolderBar({
  folders,
  selectedFolderId,
  onSelect,
  onCreate,
  onRename,
  onDelete,
}: FolderBarProps) {
  const { t } = useTranslation();

  const handleCreate = React.useCallback(() => {
    if (!onCreate) return;
    const name = window.prompt(t("conversation_sidebar.folder_name_prompt"))?.trim();
    if (!name) return;
    void onCreate(name).catch((folderError) => {
      console.error("Create folder failed", folderError);
      toast.error(t("conversation_sidebar.folder_create_failed"));
    });
  }, [onCreate, t]);

  const handleRename = React.useCallback(
    (folder: FolderDto) => {
      if (!onRename) return;
      const name = window
        .prompt(t("conversation_sidebar.folder_rename_prompt"), folder.name)
        ?.trim();
      if (!name || name === folder.name) return;
      void onRename(folder.id, name).catch((folderError) => {
        console.error("Rename folder failed", folderError);
        toast.error(t("conversation_sidebar.folder_rename_failed"));
      });
    },
    [onRename, t],
  );

  const handleDelete = React.useCallback(
    (folder: FolderDto) => {
      if (!onDelete) return;
      if (
        !window.confirm(t("conversation_sidebar.folder_delete_confirm", { folder: folder.name }))
      ) {
        return;
      }
      void onDelete(folder.id).catch((folderError) => {
        console.error("Delete folder failed", folderError);
        toast.error(t("conversation_sidebar.folder_delete_failed"));
      });
    },
    [onDelete, t],
  );

  return (
    <div className="flex items-center gap-1.5 overflow-x-auto px-1 pb-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
      <button
        type="button"
        onClick={() => onSelect(null)}
        className={cn(
          "inline-flex shrink-0 items-center gap-1 rounded-full px-3 py-1 text-xs font-medium transition",
          selectedFolderId === null
            ? "bg-primary text-primary-foreground"
            : "bg-muted text-muted-foreground hover:bg-muted/80",
        )}
      >
        {t("conversation_sidebar.folder_all")}
      </button>

      {folders.map((folder) => {
        const selected = folder.id === selectedFolderId;
        return (
          <DropdownMenu key={folder.id}>
            <div
              role="button"
              tabIndex={0}
              onClick={() => onSelect(folder.id)}
              onKeyDown={(event) => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  onSelect(folder.id);
                }
              }}
              onContextMenu={(event) => {
                if (!onRename && !onDelete) return;
                event.preventDefault();
              }}
              className={cn(
                "inline-flex shrink-0 cursor-pointer items-center gap-1 rounded-full px-3 py-1 text-xs font-medium transition",
                selected
                  ? "bg-primary text-primary-foreground"
                  : "bg-muted text-muted-foreground hover:bg-muted/80",
              )}
            >
              <Folder className="size-3" />
              <span className="max-w-[8rem] truncate">{folder.name}</span>
              {(onRename || onDelete) && (
                <DropdownMenuTrigger asChild>
                  <span
                    role="button"
                    tabIndex={-1}
                    aria-label={t("conversation_sidebar.folder_actions")}
                    onClick={(event) => event.stopPropagation()}
                    className="ml-0.5 inline-flex items-center opacity-70 hover:opacity-100"
                  >
                    <MoreHorizontal className="size-3" />
                  </span>
                </DropdownMenuTrigger>
              )}
            </div>
            <DropdownMenuContent side="bottom" align="start" className="w-40">
              {onRename && (
                <DropdownMenuItem onSelect={() => handleRename(folder)}>
                  <Pencil className="size-4" />
                  <span>{t("conversation_sidebar.folder_rename")}</span>
                </DropdownMenuItem>
              )}
              {onDelete && (
                <DropdownMenuItem variant="destructive" onSelect={() => handleDelete(folder)}>
                  <Trash2 className="size-4" />
                  <span>{t("conversation_sidebar.folder_delete")}</span>
                </DropdownMenuItem>
              )}
            </DropdownMenuContent>
          </DropdownMenu>
        );
      })}

      {onCreate && (
        <button
          type="button"
          onClick={handleCreate}
          aria-label={t("conversation_sidebar.folder_create")}
          title={t("conversation_sidebar.folder_create")}
          className="inline-flex shrink-0 items-center gap-1 rounded-full bg-muted px-2.5 py-1 text-xs font-medium text-muted-foreground transition hover:bg-muted/80"
        >
          <FolderPlus className="size-3.5" />
        </button>
      )}
    </div>
  );
}

export const ConversationSidebar = React.memo(
  ({
    conversations,
    activeId,
    loading,
    error,
    hasMore,
    loadMore,
    assistants,
    assistantTags,
    currentAssistantId,
    onSelect,
    onAssistantChange,
    onPin,
    onRegenerateTitle,
    onMoveToAssistant,
    onUpdateTitle,
    onDelete,
    onCreateConversation,
    webAuthEnabled = false,
    folders = [],
    selectedFolderId = null,
    onSelectFolder,
    onCreateFolder,
    onRenameFolder,
    onDeleteFolder,
    onMoveToFolder,
  }: ConversationSidebarProps) => {
    const { t, i18n } = useTranslation();
    const { setColorTheme, customThemeCss, setCustomThemeCss } = useTheme();
    const { state: sidebarState, isMobile } = useSidebar();

    const [pickerOpen, setPickerOpen] = React.useState(false);
    const [customThemeOpen, setCustomThemeOpen] = React.useState(false);
    const [selectedTagIds, setSelectedTagIds] = React.useState<string[]>([]);
    const [switchingAssistantId, setSwitchingAssistantId] = React.useState<string | null>(null);
    const [switchError, setSwitchError] = React.useState<string | null>(null);
    const [showBackToTop, setShowBackToTop] = React.useState(false);

    const handleCustomThemeSave = React.useCallback(
      (themeCss: CustomThemeCss) => {
        setCustomThemeCss(themeCss);
        setColorTheme("custom");
        toast.success(t("conversation_sidebar.custom_theme_saved"));
      },
      [setColorTheme, setCustomThemeCss, t],
    );

    const currentAssistant = React.useMemo(
      () =>
        assistants.find((assistant) => assistant.id === currentAssistantId) ??
        assistants[0] ??
        null,
      [assistants, currentAssistantId],
    );
    const assistantLabel = currentAssistant
      ? getAssistantDisplayName(currentAssistant.name)
      : t("conversation_sidebar.select_assistant");
    const railLabel =
      sidebarState === "collapsed"
        ? t("conversation_sidebar.expand_sidebar")
        : t("conversation_sidebar.collapse_sidebar");

    const groupedItems = React.useMemo(
      () => groupConversations(conversations, t),
      [conversations, i18n.resolvedLanguage, t],
    );

    const filteredAssistants = React.useMemo(() => {
      if (selectedTagIds.length === 0) {
        return assistants;
      }
      return assistants.filter((assistant) =>
        assistant.tags.some((tagId) => selectedTagIds.includes(tagId)),
      );
    }, [assistants, selectedTagIds]);

    const toggleTag = React.useCallback((tagId: string) => {
      setSelectedTagIds((current) =>
        current.includes(tagId) ? current.filter((id) => id !== tagId) : [...current, tagId],
      );
    }, []);

    const handleAssistantSelect = React.useCallback(
      async (assistantId: string) => {
        if (assistantId === currentAssistantId) {
          setPickerOpen(false);
          return;
        }
        setSwitchError(null);
        setSwitchingAssistantId(assistantId);
        try {
          await onAssistantChange(assistantId);
          setPickerOpen(false);
        } catch (switchAssistantError) {
          if (switchAssistantError instanceof Error) {
            setSwitchError(switchAssistantError.message);
          } else {
            setSwitchError(t("conversation_sidebar.switch_assistant_failed"));
          }
        } finally {
          setSwitchingAssistantId(null);
        }
      },
      [currentAssistantId, onAssistantChange],
    );

    const handleBackToTop = React.useCallback(() => {
      const scrollContainer = document.getElementById("conversationScrollTarget");
      if (!scrollContainer) return;
      scrollContainer.scrollTo({ top: 0, behavior: "smooth" });
    }, []);

    const handleWebLogout = React.useCallback(() => {
      void logoutWebAuth().finally(() => window.location.reload());
    }, []);

    React.useEffect(() => {
      const scrollContainer = document.getElementById("conversationScrollTarget");
      if (!scrollContainer) return;

      const showBackToTopRef = { current: false };
      let rafId = 0;
      const handleScroll = () => {
        if (rafId !== 0) return;
        rafId = requestAnimationFrame(() => {
          rafId = 0;
          const next = scrollContainer.scrollTop > 240;
          if (next !== showBackToTopRef.current) {
            showBackToTopRef.current = next;
            setShowBackToTop(next);
          }
        });
      };

      handleScroll();
      scrollContainer.addEventListener("scroll", handleScroll, { passive: true });
      return () => {
        scrollContainer.removeEventListener("scroll", handleScroll);
        if (rafId !== 0) {
          cancelAnimationFrame(rafId);
        }
      };
    }, [conversations.length]);

    return (
      <Sidebar collapsible="icon" variant="sidebar" mobileWidth={CONVERSATION_SIDEBAR_WIDTH}>
        <SidebarContent className="min-h-0">
          <SidebarGroup>
            <div className="space-y-1">
              <SidebarIconTooltip label={t("conversation_sidebar.new_conversation")} collapsedOnly>
                <Button
                  variant="ghost"
                  size="sm"
                  className={cn("w-full justify-start", SIDEBAR_ICON_MODE_BUTTON_CLASS)}
                  aria-label={t("conversation_sidebar.new_conversation")}
                  onClick={onCreateConversation}
                >
                  <Plus className="size-4" />
                  <span className={SIDEBAR_ICON_MODE_HIDDEN_CLASS}>
                    {t("conversation_sidebar.new_conversation")}
                  </span>
                </Button>
              </SidebarIconTooltip>

              <ConversationSearchButton onSelect={onSelect} assistantId={currentAssistantId} />
              <ConversationFavoritesButton onSelect={onSelect} />
            </div>
          </SidebarGroup>

          {/* 收成图标栏时：会话只显示标题首字，停留一秒浮出完整标题 */}
          <SidebarGroup className="hidden min-h-0 flex-1 flex-col px-0 group-data-[collapsible=icon]:flex">
            <ConversationRail
              conversations={conversations}
              activeId={activeId}
              hasMore={hasMore}
              loadMore={loadMore}
              onSelect={onSelect}
            />
          </SidebarGroup>

          <SidebarGroup
            className={cn("flex min-h-0 flex-1 flex-col", SIDEBAR_ICON_MODE_HIDDEN_CLASS)}
          >
            <SidebarGroupLabel>{t("conversation_sidebar.conversations")}</SidebarGroupLabel>
            {onSelectFolder && (
              <FolderBar
                folders={folders}
                selectedFolderId={selectedFolderId}
                onSelect={onSelectFolder}
                onCreate={onCreateFolder}
                onRename={onRenameFolder}
                onDelete={onDeleteFolder}
              />
            )}
            <InfiniteScrollArea
              dataLength={conversations.length}
              next={loadMore}
              hasMore={hasMore}
              scrollTargetId="conversationScrollTarget"
            >
              <SidebarMenu>
                {loading && (
                  <SidebarMenuItem>
                    <div className="px-2 py-2 text-xs text-muted-foreground">
                      {t("conversation_sidebar.loading")}
                    </div>
                  </SidebarMenuItem>
                )}
                {error && (
                  <SidebarMenuItem>
                    <div className="px-2 py-2 text-xs text-destructive">{error}</div>
                  </SidebarMenuItem>
                )}
                {!loading && !error && conversations.length === 0 && (
                  <SidebarMenuItem>
                    <div className="px-2 py-2 text-xs text-muted-foreground">
                      {t("conversation_sidebar.no_conversations")}
                    </div>
                  </SidebarMenuItem>
                )}
                {groupedItems.map((listItem) => {
                  if (listItem.type === "pinned-header") {
                    return (
                      <SidebarMenuItem key="pinned_header">
                        <div className="flex items-center gap-1.5 px-2 py-1.5 text-xs font-semibold text-primary">
                          <Pin className="size-3" />
                          {t("conversation_sidebar.pinned")}
                        </div>
                      </SidebarMenuItem>
                    );
                  }
                  if (listItem.type === "date-header") {
                    return (
                      <SidebarMenuItem key={`date_${listItem.date}`}>
                        <div className="px-2 py-1.5 text-xs font-semibold text-primary">
                          {listItem.label}
                        </div>
                      </SidebarMenuItem>
                    );
                  }
                  const item = listItem.conversation;
                  return (
                    <ConversationListRow
                      key={item.id}
                      conversation={item}
                      isActive={item.id === activeId}
                      assistants={assistants}
                      folders={folders}
                      onSelect={onSelect}
                      onPin={onPin}
                      onRegenerateTitle={onRegenerateTitle}
                      onMoveToAssistant={onMoveToAssistant}
                      onMoveToFolder={onMoveToFolder}
                      onUpdateTitle={onUpdateTitle}
                      onDelete={onDelete}
                    />
                  );
                })}
              </SidebarMenu>
            </InfiniteScrollArea>
            {showBackToTop && (
              <div className="pointer-events-none absolute right-0 bottom-4 left-0 flex justify-center">
                <Button
                  type="button"
                  variant="secondary"
                  size="icon-sm"
                  className="pointer-events-auto shadow-sm"
                  aria-label={t("conversation_sidebar.back_to_top")}
                  title={t("conversation_sidebar.back_to_top")}
                  onClick={handleBackToTop}
                >
                  <ArrowUp className="size-4" />
                </Button>
              </div>
            )}
          </SidebarGroup>
        </SidebarContent>
        <SidebarFooter>
          <Dialog
            open={pickerOpen}
            onOpenChange={(open) => {
              setPickerOpen(open);
              if (!open) {
                setSwitchError(null);
              }
            }}
          >
            <SidebarIconTooltip label={assistantLabel} collapsedOnly>
              <DialogTrigger asChild>
                <Button
                  variant="outline"
                  className={cn(
                    "w-full justify-start gap-2 text-foreground",
                    SIDEBAR_ICON_MODE_BUTTON_CLASS,
                  )}
                  type="button"
                  aria-label={assistantLabel}
                >
                  {currentAssistant ? (
                    <UIAvatar
                      key={currentAssistant.id}
                      size="sm"
                      name={assistantLabel}
                      avatar={currentAssistant.avatar}
                    />
                  ) : null}
                  <span className={cn("truncate", SIDEBAR_ICON_MODE_HIDDEN_CLASS)}>
                    {assistantLabel}
                  </span>
                </Button>
              </DialogTrigger>
            </SidebarIconTooltip>
            <DialogContent className="max-h-[80svh] max-w-xl overflow-hidden p-0">
              <DialogHeader className="border-b px-6 py-4">
                <DialogTitle>{t("conversation_sidebar.select_assistant")}</DialogTitle>
              </DialogHeader>
              <div className="space-y-4 px-6 py-4">
                {assistantTags.length > 0 && (
                  <div className="flex flex-wrap gap-2">
                    {assistantTags.map((tag) => {
                      const selected = selectedTagIds.includes(tag.id);
                      return (
                        <Button
                          key={tag.id}
                          type="button"
                          size="sm"
                          variant={selected ? "default" : "outline"}
                          onClick={() => toggleTag(tag.id)}
                        >
                          {tag.name}
                        </Button>
                      );
                    })}
                  </div>
                )}

                {switchError && (
                  <div className="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-xs text-destructive">
                    {switchError}
                  </div>
                )}

                <ScrollArea className="h-[380px]">
                  <div className="space-y-2">
                    {filteredAssistants.map((assistant) => {
                      const selected = assistant.id === currentAssistantId;
                      const switching = switchingAssistantId === assistant.id;
                      const displayName = getAssistantDisplayName(assistant.name);
                      return (
                        <button
                          key={assistant.id}
                          type="button"
                          className="flex w-full items-center gap-3 rounded-lg border px-3 py-2 text-left transition hover:bg-muted"
                          onClick={() => void handleAssistantSelect(assistant.id)}
                          disabled={switchingAssistantId !== null}
                        >
                          <UIAvatar size="sm" name={displayName} avatar={assistant.avatar} />
                          <span className="min-w-0 flex-1 truncate text-sm">{displayName}</span>
                          {selected && !switching && (
                            <Badge variant="secondary" className="gap-1">
                              <Check className="size-3" />
                              {t("conversation_sidebar.current")}
                            </Badge>
                          )}
                          {switching && (
                            <Badge variant="secondary" className="text-xs">
                              {t("conversation_sidebar.switching")}
                            </Badge>
                          )}
                        </button>
                      );
                    })}
                    {filteredAssistants.length === 0 && (
                      <div className="rounded-md border border-dashed px-3 py-8 text-center text-sm text-muted-foreground">
                        {t("conversation_sidebar.no_assistants_by_tag")}
                      </div>
                    )}
                  </div>
                </ScrollArea>
              </div>
            </DialogContent>
          </Dialog>

          <CustomThemeDialog
            open={customThemeOpen}
            onOpenChange={setCustomThemeOpen}
            initialCss={customThemeCss}
            onSave={handleCustomThemeSave}
          />

          <div
            data-testid="sidebar-footer-actions"
            className="flex items-center gap-1 group-data-[collapsible=icon]:flex-col"
          >
            {FOOTER_LINKS.map(({ to, labelKey, icon: Icon }) => (
              <SidebarIconTooltip key={to} label={t(labelKey)}>
                <Button
                  variant="outline"
                  size="icon-sm"
                  className="text-foreground"
                  type="button"
                  asChild
                >
                  <Link to={to} aria-label={t(labelKey)}>
                    <Icon className="size-4" />
                  </Link>
                </Button>
              </SidebarIconTooltip>
            ))}

            <div className="ml-auto flex items-center gap-1 group-data-[collapsible=icon]:ml-0 group-data-[collapsible=icon]:flex-col">
              <DisplayMenu
                webAuthEnabled={webAuthEnabled}
                onEditCustomCss={() => setCustomThemeOpen(true)}
                onWebLogout={handleWebLogout}
              />
              <SidebarCollapseButton />
            </div>
          </div>
        </SidebarFooter>
        {!isMobile && <SidebarRail aria-label={railLabel} title={railLabel} />}
      </Sidebar>
    );
  },
);
