import * as React from "react";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Download, MessageSquare, Plus, Star, Trash2, X } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Link, useNavigate, useParams } from "react-router";
import { toast } from "sonner";

import { Badge } from "~/components/ui/badge";
import { Button } from "~/components/ui/button";
import { Checkbox } from "~/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "~/components/ui/dialog";
import { cn } from "~/lib/utils";
import { extractErrorMessage } from "~/lib/error";
import { useSettingsStore } from "~/stores";
import { reportSettingsError, SettingsSelectField } from "@overlay/components/settings/settings-ui";
import { CommitInput, CommitInt, CommitTextarea } from "@overlay/components/settings/commit-fields";
import { CharacterAvatar } from "@overlay/components/tavern/character-avatar";
import { FieldLabel, Hint, SwitchField } from "@overlay/components/tavern/tavern-ui";
import {
  characterExportHref,
  characterQueryKey,
  charactersApi,
  charactersQueryKey,
  checkAvatarFile,
  startDownload,
  tagsToText,
  textToTags,
  type CharacterPatch,
} from "@overlay/lib/characters";
import type { CharacterDetail, CharacterExportFormat, CharacterTextField, DepthPromptRole } from "@overlay/types/character";

const ROLES: readonly DepthPromptRole[] = ["system", "user", "assistant"];

/** One character: every card field, the world books tied to it, the card's own regex scripts, export and chat. */
/** A fixed fallback: a fresh `[]` in the selector makes the store hook re-render forever while settings are still loading. */
const NO_BOOKS: never[] = [];

export default function SettingsCharacterDetailPage() {
  const { t } = useTranslation("tavern");
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const lorebooks = useSettingsStore((state) => state.settings?.lorebooks) ?? NO_BOOKS;
  const [deleteOpen, setDeleteOpen] = React.useState(false);
  const [starting, setStarting] = React.useState(false);
  const avatarInput = React.useRef<HTMLInputElement>(null);

  const query = useQuery({
    queryKey: characterQueryKey(id),
    queryFn: () => charactersApi.get(id),
    enabled: id !== "",
    retry: false,
  });
  const detail = query.data;

  const publish = (next: CharacterDetail) => {
    queryClient.setQueryData(characterQueryKey(next.id), next);
    void queryClient.invalidateQueries({ queryKey: charactersQueryKey, exact: false });
  };

  /** Each edit names only what it changes, so an edit from the app to another field is not overwritten. */
  const run = async (action: () => Promise<CharacterDetail>) => {
    try {
      publish(await action());
    } catch (error) {
      reportSettingsError(error, t("common.saveFailed"));
      void query.refetch();
    }
  };
  const save = (patch: CharacterPatch) => run(() => charactersApi.patch(id, patch));

  if (query.isError || (!query.isLoading && !detail)) {
    return (
      <div className="space-y-4">
        <BackLink />
        <p className="text-sm text-muted-foreground">{t("characters.notFound")}</p>
      </div>
    );
  }
  if (!detail) return null;

  const fields = detail.fields;
  const textField = (key: CharacterTextField, label: string, rows: number, hint?: string) => (
    <div className="flex flex-col gap-1.5">
      <FieldLabel>{label}</FieldLabel>
      <CommitTextarea
        rows={rows}
        value={fields[key]}
        onCommit={(value) => void save({ [key]: value })}
      />
      {hint ? <Hint>{hint}</Hint> : null}
    </div>
  );
  const lineField = (key: CharacterTextField, label: string) => (
    <div className="flex flex-col gap-1.5">
      <FieldLabel>{label}</FieldLabel>
      <CommitInput value={fields[key]} onCommit={(value) => void save({ [key]: value })} />
    </div>
  );

  const attached = new Set([...(detail.embeddedBookId ? [detail.embeddedBookId] : []), ...detail.extraBookIds]);
  const availableBooks = lorebooks.filter((book) => !attached.has(book.id));
  const greetings = fields.alternate_greetings;
  const depth = fields.depth_prompt;

  return (
    <div className="space-y-6">
      <BackLink />

      <div className="flex flex-wrap items-start gap-4">
        <input
          ref={avatarInput}
          type="file"
          accept="image/*"
          className="hidden"
          onChange={(event) => {
            const file = event.target.files?.[0];
            event.target.value = "";
            if (!file) return;
            const check = checkAvatarFile(file);
            if (check !== "ok") {
              toast.error(check === "too_large" ? t("characters.avatarTooLarge") : t("characters.avatarNotImage"));
              return;
            }
            void run(() => charactersApi.setAvatar(id, file));
          }}
        />
        <button
          type="button"
          title={t("characters.changePicture")}
          aria-label={t("characters.changePicture")}
          className="rounded-full transition hover:opacity-80"
          onClick={() => avatarInput.current?.click()}
        >
          <CharacterAvatar name={detail.name} avatarUrl={detail.avatarUrl} className="size-24" />
        </button>
        <div className="min-w-0 flex-1 space-y-2">
          <h2 className="truncate text-lg font-semibold">{detail.name || t("characters.fieldName")}</h2>
          {detail.sourceName ? (
            <p className="text-xs text-muted-foreground">{t("characters.source", { name: detail.sourceName })}</p>
          ) : null}
          {detail.assetCount > 0 ? (
            <p className="text-xs text-muted-foreground">{t("characters.assets", { count: detail.assetCount })}</p>
          ) : null}
          <div className="flex flex-wrap gap-2">
            <Button
              type="button"
              disabled={starting}
              onClick={async () => {
                setStarting(true);
                try {
                  const chat = await charactersApi.startChat(id);
                  void navigate(`/c/${chat.conversationId}`);
                } catch (error) {
                  toast.error(extractErrorMessage(error, t("characters.startFailed")));
                } finally {
                  setStarting(false);
                }
              }}
            >
              <MessageSquare className="size-4" />
              {t("characters.startChat")}
            </Button>
            <Button
              type="button"
              variant="outline"
              aria-pressed={detail.fav}
              onClick={() => void save({ fav: !detail.fav })}
            >
              <Star className={cn("size-4", detail.fav ? "fill-primary text-primary" : undefined)} />
              {detail.fav ? t("characters.unfavorite") : t("characters.favorite")}
            </Button>
            <Button type="button" variant="destructive" onClick={() => setDeleteOpen(true)}>
              <Trash2 className="size-4" />
              {t("characters.delete")}
            </Button>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-xs text-muted-foreground">{t("characters.export")}</span>
            {(
              [
                ["png", "characters.exportPng"],
                ["json", "characters.exportJson"],
                ["charx", "characters.exportCharx"],
              ] as Array<[CharacterExportFormat, string]>
            ).map(([format, key]) => (
              <Button
                key={format}
                type="button"
                size="sm"
                variant="outline"
                onClick={() => startDownload(characterExportHref(id, format))}
              >
                <Download className="size-3.5" />
                {t(key)}
              </Button>
            ))}
          </div>
        </div>
      </div>

      <div className="grid max-w-3xl gap-5">
        {lineField("name", t("characters.fieldName"))}
        <div className="flex flex-col gap-1.5">
          <FieldLabel>{t("characters.fieldTags")}</FieldLabel>
          <CommitInput value={tagsToText(detail.tags)} onCommit={(value) => void save({ tags: textToTags(value) })} />
        </div>
        {textField("description", t("characters.fieldDescription"), 6)}
        {textField("personality", t("characters.fieldPersonality"), 3)}
        {textField("scenario", t("characters.fieldScenario"), 3)}
        {textField("first_mes", t("characters.fieldFirstMessage"), 5)}

        <div className="flex flex-col gap-2">
          <FieldLabel>{t("characters.fieldAlternateGreetings")}</FieldLabel>
          {greetings.map((greeting, index) => (
            <div key={index} className="flex items-start gap-2">
              <CommitTextarea
                rows={3}
                className="flex-1"
                value={greeting}
                onCommit={(value) =>
                  void save({ alternate_greetings: greetings.map((item, at) => (at === index ? value : item)) })
                }
              />
              <Button
                type="button"
                size="icon-sm"
                variant="ghost"
                aria-label={t("characters.removeGreeting")}
                onClick={() => void save({ alternate_greetings: greetings.filter((_, at) => at !== index) })}
              >
                <X className="size-4" />
              </Button>
            </div>
          ))}
          <div>
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={() => void save({ alternate_greetings: [...greetings, ""] })}
            >
              <Plus className="size-3.5" />
              {t("characters.fieldAddGreeting")}
            </Button>
          </div>
        </div>

        {textField("mes_example", t("characters.fieldExampleDialogue"), 6)}
        {textField("creator_notes", t("characters.fieldCreatorNotes"), 3)}

        <h3 className="pt-2 text-sm font-semibold">{t("characters.sectionAdvanced")}</h3>
        {textField("system_prompt", t("characters.fieldSystemPrompt"), 3)}
        {textField("post_history_instructions", t("characters.fieldPostHistory"), 3)}
        <div className="flex flex-col gap-1.5">
          <FieldLabel>{t("characters.fieldDepthPrompt")}</FieldLabel>
          <CommitTextarea
            rows={3}
            value={depth.prompt}
            onCommit={(value) => void save({ depth_prompt: { ...depth, prompt: value } })}
          />
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="flex flex-col gap-1.5">
              <FieldLabel>{t("characters.fieldDepth")}</FieldLabel>
              <CommitInt
                value={depth.depth}
                min={0}
                max={10000}
                onCommit={(value) => void save({ depth_prompt: { ...depth, depth: value } })}
              />
            </div>
            <SettingsSelectField
              label={t("characters.fieldDepthRole")}
              value={depth.role}
              onValueChange={(value) => void save({ depth_prompt: { ...depth, role: value as DepthPromptRole } })}
              options={ROLES.map((role) => ({
                value: role,
                label: t(`characters.role${role[0].toUpperCase()}${role.slice(1)}`),
              }))}
            />
          </div>
        </div>
        <div className="grid gap-3 sm:grid-cols-2">
          {lineField("creator", t("characters.fieldCreator"))}
          {lineField("character_version", t("characters.fieldVersion"))}
        </div>

        <div className="space-y-2">
          <h3 className="pt-2 text-sm font-semibold">{t("characters.sectionBooks")}</h3>
          <Hint>{t("characters.booksHint")}</Hint>
          {detail.books.length > 0 ? (
            <div className="divide-y overflow-hidden rounded-xl border">
              {detail.books.map((book) => (
                <div key={book.id} className="flex items-center gap-2 px-3 py-2">
                  <span className="min-w-0 flex-1 truncate text-sm">{book.name}</span>
                  {book.embedded ? <Badge variant="secondary">{t("characters.bookFromCard")}</Badge> : null}
                  <Button
                    type="button"
                    size="icon-sm"
                    variant="ghost"
                    aria-label={t("characters.bookRemove")}
                    title={t("characters.bookRemove")}
                    onClick={() =>
                      void run(() =>
                        book.embedded
                          ? charactersApi.setBooks(id, { embeddedBookId: null })
                          : charactersApi.setBooks(id, { extraBookIds: detail.extraBookIds.filter((item) => item !== book.id) }),
                      )
                    }
                  >
                    <X className="size-4" />
                  </Button>
                </div>
              ))}
            </div>
          ) : null}
          {availableBooks.length > 0 ? (
            <SettingsSelectField
              label={t("characters.bookAdd")}
              value="__none__"
              onValueChange={(value) => {
                if (value !== "__none__") void run(() => charactersApi.setBooks(id, { extraBookIds: [...detail.extraBookIds, value] }));
              }}
              options={[
                { value: "__none__", label: t("characters.bookAdd") },
                ...availableBooks.map((book) => ({ value: book.id, label: book.name })),
              ]}
            />
          ) : (
            <Hint>{t("characters.bookNoneLeft")}</Hint>
          )}
        </div>

        <div className="space-y-2">
          <h3 className="pt-2 text-sm font-semibold">{t("characters.sectionRegex")}</h3>
          {detail.regexScripts.length === 0 ? (
            <Hint>{t("characters.regexNone")}</Hint>
          ) : (
            <>
              <SwitchField
                title={t("characters.regexAllow")}
                description={t("characters.regexHint")}
                checked={detail.regexAllowed}
                onCheckedChange={(on) => void run(() => charactersApi.setRegexAllowed(id, on))}
              />
              <div className="divide-y overflow-hidden rounded-xl border">
                {detail.regexScripts.map((script) => (
                  <div key={script.id} className="px-3 py-2">
                    <p className="text-sm">
                      {script.name || script.id}
                      {script.disabled ? (
                        <span className="ml-2 text-xs text-muted-foreground">{t("characters.regexScriptsOff")}</span>
                      ) : null}
                    </p>
                    <p className="line-clamp-2 font-mono text-xs break-all text-muted-foreground">
                      {script.findRegex} → {script.replaceString || "∅"}
                    </p>
                  </div>
                ))}
              </div>
            </>
          )}
        </div>
      </div>

      <DeleteDialog
        open={deleteOpen}
        onOpenChange={setDeleteOpen}
        name={detail.name}
        hasBook={detail.embeddedBookId !== null}
        onConfirm={async (deleteBook) => {
          try {
            await charactersApi.remove(id, deleteBook);
            queryClient.removeQueries({ queryKey: characterQueryKey(id) });
            void queryClient.invalidateQueries({ queryKey: charactersQueryKey });
            void navigate("/settings/characters");
          } catch (error) {
            reportSettingsError(error, t("common.saveFailed"));
          }
        }}
      />
    </div>
  );
}

function BackLink() {
  const { t } = useTranslation("tavern");
  return (
    <Button variant="ghost" size="sm" asChild>
      <Link to="/settings/characters">
        <ArrowLeft className="size-4" />
        {t("characters.title")}
      </Link>
    </Button>
  );
}

function DeleteDialog({
  open,
  onOpenChange,
  name,
  hasBook,
  onConfirm,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  name: string;
  hasBook: boolean;
  onConfirm: (deleteBook: boolean) => Promise<void>;
}) {
  const { t } = useTranslation("tavern");
  const [alsoBook, setAlsoBook] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  React.useEffect(() => {
    if (open) setAlsoBook(false);
  }, [open]);
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("characters.delete")}</DialogTitle>
          <DialogDescription>{t("characters.deleteConfirm", { name })}</DialogDescription>
        </DialogHeader>
        {hasBook ? (
          <label className="flex items-center gap-2 text-sm">
            <Checkbox checked={alsoBook} onCheckedChange={(checked) => setAlsoBook(checked === true)} />
            {t("characters.deleteWithBook")}
          </label>
        ) : null}
        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
            {t("common.cancel")}
          </Button>
          <Button
            type="button"
            variant="destructive"
            disabled={busy}
            onClick={async () => {
              setBusy(true);
              try {
                await onConfirm(alsoBook);
              } finally {
                setBusy(false);
              }
            }}
          >
            {busy ? t("characters.deleting") : t("characters.delete")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
