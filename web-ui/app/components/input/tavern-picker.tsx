import * as React from "react";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { NotebookPen, Plus, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { usePickerPopover } from "~/hooks/use-picker-popover";
import { extractErrorMessage } from "~/lib/error";
import { cn } from "~/lib/utils";
import api from "~/services/api";
import { useSettingsStore } from "~/stores";
import { Button } from "~/components/ui/button";
import { Input } from "~/components/ui/input";
import { Popover, PopoverContent, PopoverTitle, PopoverTrigger } from "~/components/ui/popover";
import { CommitInput, CommitInt, CommitTextarea } from "@overlay/components/settings/commit-fields";
import { Chip, ChipRow, FieldLabel, Hint } from "@overlay/components/tavern/tavern-ui";
import { variableDisplay } from "@overlay/lib/tavern";
import type { ConversationTavernState } from "@overlay/types/tavern";

import { PickerErrorAlert } from "./picker-error-alert";

type Tab = "note" | "variables";
type Scope = "chat" | "global";

const NOTE_POSITIONS = [
  [0, "note.posAfterPrompt"],
  [1, "note.posInChat"],
  [2, "note.posBeforePrompt"],
] as const;
const NOTE_ROLES = [
  [0, "layout.roleSystem"],
  [1, "layout.roleUser"],
  [2, "layout.roleAssistant"],
] as const;

export interface TavernPickerButtonProps {
  conversationId: string | null;
  disabled?: boolean;
  className?: string;
}

/**
 * This conversation's author's note and variables: the note slipped into the prompt every few messages, and the
 * values {{setvar}} / {{setglobalvar}} keep. Changes are saved at once and apply to the next request.
 * A new chat has no server-side conversation yet, so the button waits for the first message.
 */
export function TavernPickerButton({ conversationId, disabled = false, className }: TavernPickerButtonProps) {
  const { t } = useTranslation("tavern");
  const canUse = Boolean(conversationId) && !disabled;
  const { error, setError, open, popoverProps } = usePickerPopover(canUse);
  const [tab, setTab] = React.useState<Tab>("note");
  const queryClient = useQueryClient();
  const queryKey = React.useMemo(() => ["conversation-tavern", conversationId] as const, [conversationId]);
  const state = useQuery({
    queryKey,
    queryFn: () => api.get<ConversationTavernState>(`conversations/${conversationId}/tavern`),
    enabled: canUse && open,
    // Generation writes variables back, so every open fetches fresh values.
    staleTime: 0,
    gcTime: 0,
  });

  /** Run a write, then bring the shown state up to date; an error shows inside the popover. */
  const write = React.useCallback(
    async (work: () => Promise<unknown>) => {
      try {
        await work();
        setError(null);
        await queryClient.invalidateQueries({ queryKey });
      } catch (writeError) {
        setError(extractErrorMessage(writeError, t("common.saveFailed")));
      }
    },
    [queryClient, queryKey, setError, t],
  );

  const label = t("sheet.title");
  return (
    <Popover {...popoverProps}>
      <PopoverTrigger asChild>
        <Button
          type="button"
          aria-label={label}
          title={canUse || disabled ? label : t("sheet.noConversation")}
          variant="ghost"
          size="sm"
          disabled={!canUse}
          className={cn("h-8 rounded-full px-2 text-muted-foreground hover:text-foreground", className)}
          data-tavern-picker
        >
          <NotebookPen className="size-4" />
        </Button>
      </PopoverTrigger>
      <PopoverContent
        side="top"
        align="end"
        sideOffset={8}
        className="w-[min(92vw,24rem)] max-h-[var(--radix-popover-content-available-height)] space-y-3 overflow-y-auto p-4"
      >
        <PopoverTitle className="text-sm">{label}</PopoverTitle>
        <PickerErrorAlert error={error} />
        <div className="bg-muted flex rounded-xl p-1" role="tablist">
          {(["note", "variables"] as const).map((item) => (
            <button
              key={item}
              type="button"
              role="tab"
              aria-selected={tab === item}
              className={cn(
                "flex-1 rounded-lg px-3 py-1.5 text-xs transition",
                tab === item ? "bg-background text-foreground shadow-sm" : "text-muted-foreground",
              )}
              onClick={() => setTab(item)}
            >
              {item === "note" ? t("sheet.note") : t("sheet.variables")}
            </button>
          ))}
        </div>
        {state.isError ? (
          <p className="text-sm text-destructive">{extractErrorMessage(state.error, t("sheet.loadFailed"))}</p>
        ) : !state.data ? (
          <p className="text-sm text-muted-foreground">{t("common.loading")}</p>
        ) : tab === "note" ? (
          <NoteTab conversationId={conversationId!} state={state.data} write={write} />
        ) : (
          <VariablesTab conversationId={conversationId!} state={state.data} write={write} />
        )}
      </PopoverContent>
    </Popover>
  );
}

type Write = (work: () => Promise<unknown>) => Promise<void>;

function NoteTab({ conversationId, state, write }: { conversationId: string; state: ConversationTavernState; write: Write }) {
  const { t } = useTranslation("tavern");
  const note = state.note;
  const save = (patch: Record<string, unknown>) => write(() => api.put(`conversations/${conversationId}/tavern/note`, patch));
  return (
    <div className="flex flex-col gap-3">
      <Hint>{t("note.desc")}</Hint>
      <div className="flex flex-col gap-1.5">
        <FieldLabel>{t("note.text")}</FieldLabel>
        <CommitTextarea rows={4} value={note.prompt} onCommit={(prompt) => void save({ prompt })} />
      </div>
      <div className="grid grid-cols-2 gap-3">
        <div className="flex flex-col gap-1.5">
          <FieldLabel>{t("note.interval")}</FieldLabel>
          <CommitInt value={note.interval} min={0} onCommit={(interval) => void save({ interval })} />
        </div>
        <div className="flex flex-col gap-1.5">
          <FieldLabel>{t("layout.depth")}</FieldLabel>
          <CommitInt value={note.depth} min={0} onCommit={(depth) => void save({ depth })} />
        </div>
      </div>
      <Hint>{t("note.intervalHint")}</Hint>
      <div className="flex flex-col gap-1.5">
        <FieldLabel>{t("layout.position")}</FieldLabel>
        <ChipRow>
          {NOTE_POSITIONS.map(([position, key]) => (
            <Chip key={position} selected={note.position === position} onClick={() => void save({ position })}>
              {t(key)}
            </Chip>
          ))}
        </ChipRow>
      </div>
      <div className="flex flex-col gap-1.5">
        <FieldLabel>{t("worldinfo.role")}</FieldLabel>
        <ChipRow>
          {NOTE_ROLES.map(([role, key]) => (
            <Chip key={role} selected={note.role === role} onClick={() => void save({ role })}>
              {t(key)}
            </Chip>
          ))}
        </ChipRow>
      </div>
      <Hint>{note.own ? t("note.own") : t("note.followingDefault")}</Hint>
      <div className="flex flex-wrap gap-2">
        {note.own ? (
          <Button type="button" size="sm" variant="outline" onClick={() => void write(() => api.delete(`conversations/${conversationId}/tavern/note`))}>
            {t("note.useDefault")}
          </Button>
        ) : null}
        <Button
          type="button"
          size="sm"
          variant="outline"
          onClick={() =>
            void write(async () => {
              await api.put("tavern/authors-note", {
                prompt: note.prompt,
                interval: note.interval,
                depth: note.depth,
                position: note.position,
                role: note.role,
              });
              toast.success(t("note.savedDefault"));
            })
          }
        >
          {t("note.saveDefault")}
        </Button>
      </div>
    </div>
  );
}

function VariablesTab({ conversationId, state, write }: { conversationId: string; state: ConversationTavernState; write: Write }) {
  const { t } = useTranslation("tavern");
  const [scope, setScope] = React.useState<Scope>("chat");
  const globals = useSettingsStore((store) => store.settings?.tavern?.globalVariables);
  const values = scope === "chat" ? state.variables : (globals ?? {});
  const names = Object.keys(values);
  const [newName, setNewName] = React.useState("");
  const [newValue, setNewValue] = React.useState("");

  const base = scope === "chat" ? `conversations/${conversationId}/tavern/variables` : "tavern/variables";
  const put = (name: string, text: string) => write(() => api.put(base, { name, text }));
  const remove = (name: string) => write(() => api.delete(base, { searchParams: { name } }));
  const add = async () => {
    const name = newName.trim();
    if (name === "") return;
    await put(name, newValue);
    setNewName("");
    setNewValue("");
  };

  return (
    <div className="flex flex-col gap-3">
      <Hint>{t("vars.desc")}</Hint>
      <ChipRow>
        <Chip selected={scope === "chat"} onClick={() => setScope("chat")}>
          {t("vars.chat")}
        </Chip>
        <Chip selected={scope === "global"} onClick={() => setScope("global")}>
          {t("vars.global")}
        </Chip>
      </ChipRow>
      {names.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t("vars.empty")}</p>
      ) : (
        <ul className="flex flex-col gap-2" data-variables={scope}>
          {names.map((name) => (
            <li key={`${scope}:${name}`} className="flex items-center gap-2">
              <span className="w-24 shrink-0 truncate font-mono text-xs" title={name}>
                {name}
              </span>
              <CommitInput
                className="h-8 flex-1"
                placeholder={t("vars.emptyValue")}
                value={variableDisplay(values[name])}
                onCommit={(text) => void put(name, text)}
              />
              <Button type="button" size="icon-sm" variant="ghost" aria-label={t("layout.delete")} onClick={() => void remove(name)}>
                <Trash2 className="size-4" />
              </Button>
            </li>
          ))}
        </ul>
      )}
      <div className="flex items-center gap-2">
        <Input className="h-8 w-24 shrink-0" placeholder={t("vars.name")} value={newName} onChange={(event) => setNewName(event.target.value)} />
        <Input
          className="h-8 flex-1"
          placeholder={t("vars.value")}
          value={newValue}
          onChange={(event) => setNewValue(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter" && !event.nativeEvent.isComposing) void add();
          }}
        />
        <Button type="button" size="icon-sm" variant="outline" aria-label={t("vars.add")} disabled={newName.trim() === ""} onClick={() => void add()}>
          <Plus className="size-4" />
        </Button>
      </div>
    </div>
  );
}
