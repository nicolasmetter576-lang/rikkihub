import * as React from "react";

import {
  ArrowUp,
  Check,
  Folder,
  FolderLock,
  FolderOpen,
  LoaderCircle,
  MessageSquareText,
  Settings2,
} from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "~/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "~/components/ui/dropdown-menu";
import { Switch } from "~/components/ui/switch";
import { Textarea } from "~/components/ui/textarea";
import { cn } from "~/lib/utils";
import api from "~/services/api";
import { workspaceApi, parentWorkspacePath } from "@overlay/lib/workspace";
import type { WorkspaceFileEntry } from "@overlay/types/workspace";
import type { AssistantProfile, ConversationDto } from "~/types";

const WORKSPACE_PREFIX = "/workspace";

function toAbsoluteWorkspacePath(relativePath: string): string {
  return relativePath ? `${WORKSPACE_PREFIX}/${relativePath}` : WORKSPACE_PREFIX;
}

function fromAbsoluteWorkspacePath(absolutePath: string | null | undefined): string {
  if (!absolutePath) return "";
  return absolutePath
    .replace(new RegExp(`^${WORKSPACE_PREFIX}/`), "")
    .replace(new RegExp(`^${WORKSPACE_PREFIX}$`), "");
}

interface SystemPromptDialogProps {
  open: boolean;
  initialPrompt: string;
  onOpenChange: (open: boolean) => void;
  onSave: (prompt: string | null) => Promise<void>;
}

function SystemPromptDialog({
  open,
  initialPrompt,
  onOpenChange,
  onSave,
}: SystemPromptDialogProps) {
  const { t } = useTranslation("page");
  const [text, setText] = React.useState(initialPrompt);
  const [saving, setSaving] = React.useState(false);

  React.useEffect(() => {
    if (open) {
      setText(initialPrompt);
    }
  }, [initialPrompt, open]);

  const submit = React.useCallback(
    async (prompt: string | null) => {
      if (saving) return;
      setSaving(true);
      try {
        await onSave(prompt);
        onOpenChange(false);
      } catch (saveError) {
        toast.error(
          saveError instanceof Error ? saveError.message : t("conversations.settings.save_failed"),
        );
      } finally {
        setSaving(false);
      }
    },
    [onOpenChange, onSave, saving, t],
  );

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{t("conversations.settings.system_prompt_title")}</DialogTitle>
          <DialogDescription>
            {t("conversations.settings.system_prompt_description")}
          </DialogDescription>
        </DialogHeader>
        <Textarea
          value={text}
          onChange={(event) => setText(event.target.value)}
          placeholder={t("conversations.settings.system_prompt_placeholder")}
          className="max-h-[50vh] min-h-32 resize-none"
        />
        <DialogFooter className="sm:justify-between">
          <Button
            type="button"
            variant="ghost"
            className="text-destructive hover:text-destructive"
            disabled={saving || initialPrompt.length === 0}
            onClick={() => void submit(null)}
          >
            {t("conversations.settings.system_prompt_clear")}
          </Button>
          <div className="flex gap-2">
            <Button
              type="button"
              variant="outline"
              disabled={saving}
              onClick={() => onOpenChange(false)}
            >
              {t("conversations.settings.cancel")}
            </Button>
            <Button
              type="button"
              disabled={saving}
              onClick={() => {
                const trimmed = text.trim();
                void submit(trimmed.length > 0 ? trimmed : null);
              }}
            >
              {saving ? t("conversations.settings.saving") : t("conversations.settings.save")}
            </Button>
          </div>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

interface WorkspaceCwdDialogProps {
  open: boolean;
  workspaceId: string;
  currentCwd: string | null | undefined;
  onOpenChange: (open: boolean) => void;
  onSave: (cwd: string | null) => Promise<void>;
}

export function WorkspaceCwdDialog({
  open,
  workspaceId,
  currentCwd,
  onOpenChange,
  onSave,
}: WorkspaceCwdDialogProps) {
  const { t } = useTranslation("page");
  const [browsePath, setBrowsePath] = React.useState(() => fromAbsoluteWorkspacePath(currentCwd));
  const [entries, setEntries] = React.useState<WorkspaceFileEntry[]>([]);
  const [loading, setLoading] = React.useState(false);
  const [loadError, setLoadError] = React.useState(false);
  const [saving, setSaving] = React.useState(false);

  React.useEffect(() => {
    if (open) {
      setBrowsePath(fromAbsoluteWorkspacePath(currentCwd));
    }
  }, [currentCwd, open]);

  React.useEffect(() => {
    if (!open) return;
    let mounted = true;
    setLoading(true);
    setLoadError(false);
    workspaceApi
      .listFiles(workspaceId, "FILES", browsePath)
      .then((result) => {
        if (!mounted) return;
        setEntries(result.entries.filter((entry) => entry.isDirectory));
      })
      .catch(() => {
        if (!mounted) return;
        setEntries([]);
        setLoadError(true);
      })
      .finally(() => {
        if (mounted) setLoading(false);
      });
    return () => {
      mounted = false;
    };
  }, [browsePath, open, workspaceId]);

  const submit = React.useCallback(
    async (cwd: string | null) => {
      if (saving) return;
      setSaving(true);
      try {
        await onSave(cwd);
        onOpenChange(false);
      } catch (saveError) {
        toast.error(
          saveError instanceof Error ? saveError.message : t("conversations.settings.save_failed"),
        );
      } finally {
        setSaving(false);
      }
    },
    [onOpenChange, onSave, saving, t],
  );

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{t("conversations.settings.cwd_title")}</DialogTitle>
          <DialogDescription>{t("conversations.settings.cwd_description")}</DialogDescription>
        </DialogHeader>

        <div className="flex items-center gap-1">
          <Button
            type="button"
            size="icon-sm"
            variant="ghost"
            disabled={!browsePath}
            aria-label={t("conversations.settings.cwd_up")}
            title={t("conversations.settings.cwd_up")}
            onClick={() => setBrowsePath(parentWorkspacePath(browsePath))}
          >
            <ArrowUp className="size-4" />
          </Button>
          <div className="min-w-0 flex-1 truncate rounded-md bg-muted px-3 py-1.5 text-sm text-muted-foreground">
            {toAbsoluteWorkspacePath(browsePath)}
          </div>
        </div>

        <div className="max-h-64 min-h-32 overflow-y-auto rounded-md border">
          {loading ? (
            <div className="flex h-32 items-center justify-center">
              <LoaderCircle className="size-5 animate-spin text-muted-foreground" />
            </div>
          ) : loadError ? (
            <div className="flex h-32 items-center justify-center px-4 text-center text-sm text-muted-foreground">
              {t("conversations.settings.cwd_load_failed")}
            </div>
          ) : entries.length === 0 ? (
            <div className="flex h-32 items-center justify-center px-4 text-center text-sm text-muted-foreground">
              {t("conversations.settings.cwd_no_subdirectories")}
            </div>
          ) : (
            <ul>
              {entries.map((entry) => (
                <li key={entry.path}>
                  <button
                    type="button"
                    className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm hover:bg-muted"
                    onClick={() => setBrowsePath(entry.path)}
                  >
                    <Folder className="size-4 shrink-0 text-primary" />
                    <span className="min-w-0 flex-1 truncate">{entry.name}</span>
                    <FolderOpen className="size-3.5 shrink-0 text-muted-foreground" />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>

        <DialogFooter className="sm:justify-between">
          <Button
            type="button"
            variant="ghost"
            className="text-destructive hover:text-destructive"
            disabled={saving || !currentCwd}
            onClick={() => void submit(null)}
          >
            {t("conversations.settings.cwd_reset")}
          </Button>
          <div className="flex gap-2">
            <Button
              type="button"
              variant="outline"
              disabled={saving}
              onClick={() => onOpenChange(false)}
            >
              {t("conversations.settings.cancel")}
            </Button>
            <Button
              type="button"
              disabled={saving}
              onClick={() => void submit(toAbsoluteWorkspacePath(browsePath))}
            >
              {saving ? t("conversations.settings.saving") : t("conversations.settings.cwd_set")}
            </Button>
          </div>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export interface ConversationSettingsMenuProps {
  conversation: ConversationDto;
  assistant: AssistantProfile | null;
  workspaceId: string | null;
  onUpdated: (conversation: ConversationDto) => void;
}

export function ConversationSettingsMenu({
  conversation,
  assistant,
  workspaceId,
  onUpdated,
}: ConversationSettingsMenuProps) {
  const { t } = useTranslation("page");
  const [menuOpen, setMenuOpen] = React.useState(false);
  const [promptDialogOpen, setPromptDialogOpen] = React.useState(false);
  const [cwdDialogOpen, setCwdDialogOpen] = React.useState(false);

  const allowSystemPrompt = assistant?.allowConversationSystemPrompt === true;
  const hasWorkspace = Boolean(workspaceId);

  const updateConversation = React.useCallback(
    async (patch: Record<string, unknown>) => {
      const next = await api.put<ConversationDto>(`conversations/${conversation.id}`, patch);
      onUpdated(next);
      return next;
    },
    [conversation.id, onUpdated],
  );

  const handleSavePrompt = React.useCallback(
    (prompt: string | null) => updateConversation({ customSystemPrompt: prompt }).then(() => {}),
    [updateConversation],
  );

  const handleSaveCwd = React.useCallback(
    (cwd: string | null) => updateConversation({ workspaceCwd: cwd }).then(() => {}),
    [updateConversation],
  );
  const [scopedSaving, setScopedSaving] = React.useState(false);

  if (!allowSystemPrompt && !hasWorkspace) {
    return null;
  }

  return (
    <>
      <DropdownMenu open={menuOpen} onOpenChange={setMenuOpen}>
        <DropdownMenuTrigger asChild>
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            aria-label={t("conversations.settings.open")}
            title={t("conversations.settings.open")}
          >
            <Settings2 className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-64">
          {allowSystemPrompt ? (
            <DropdownMenuItem
              onSelect={() => {
                setPromptDialogOpen(true);
              }}
            >
              <MessageSquareText className="size-4" />
              <span className="min-w-0 flex-1 truncate">
                {t("conversations.settings.system_prompt_title")}
              </span>
              {conversation.customSystemPrompt ? <Check className="size-3.5 text-primary" /> : null}
            </DropdownMenuItem>
          ) : null}

          {allowSystemPrompt && hasWorkspace ? <DropdownMenuSeparator /> : null}

          {hasWorkspace ? (
            <DropdownMenuItem
              onSelect={() => {
                setCwdDialogOpen(true);
              }}
            >
              <Folder className="size-4" />
              <span className="min-w-0 flex-1 truncate">
                {t("conversations.settings.cwd_title")}
              </span>
              <span
                className={cn(
                  "shrink-0 text-xs",
                  conversation.workspaceCwd ? "text-primary" : "text-muted-foreground",
                )}
              >
                {conversation.workspaceCwd ?? WORKSPACE_PREFIX}
              </span>
            </DropdownMenuItem>
          ) : null}
          {hasWorkspace ? (
            <DropdownMenuItem
              // 开关项：点击切换，不关菜单
              onSelect={(event) => event.preventDefault()}
              disabled={scopedSaving}
              onClick={() => {
                setScopedSaving(true);
                updateConversation({ workspaceCwdScoped: conversation.workspaceCwdScoped === false })
                  .catch((error: unknown) => toast.error(error instanceof Error ? error.message : String(error)))
                  .finally(() => setScopedSaving(false));
              }}
            >
              <FolderLock className="size-4" />
              <span className="min-w-0 flex-1 truncate">{t("conversations.settings.cwd_scoped")}</span>
              <Switch
                size="sm"
                checked={conversation.workspaceCwdScoped !== false}
                className="pointer-events-none"
                aria-hidden
                tabIndex={-1}
              />
            </DropdownMenuItem>
          ) : null}
        </DropdownMenuContent>
      </DropdownMenu>

      {allowSystemPrompt ? (
        <SystemPromptDialog
          open={promptDialogOpen}
          initialPrompt={conversation.customSystemPrompt ?? ""}
          onOpenChange={setPromptDialogOpen}
          onSave={handleSavePrompt}
        />
      ) : null}

      {hasWorkspace && workspaceId ? (
        <WorkspaceCwdDialog
          open={cwdDialogOpen}
          workspaceId={workspaceId}
          currentCwd={conversation.workspaceCwd}
          onOpenChange={setCwdDialogOpen}
          onSave={handleSaveCwd}
        />
      ) : null}
    </>
  );
}
