import * as React from "react";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Search, Star, Upload } from "lucide-react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router";
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
import { Input } from "~/components/ui/input";
import { cn } from "~/lib/utils";
import { extractErrorMessage } from "~/lib/error";
import {
  CreateNameDialog,
  SettingsPageHeader,
  reportSettingsError,
} from "@overlay/components/settings/settings-ui";
import { CharacterAvatar } from "@overlay/components/tavern/character-avatar";
import {
  characterQueryKey,
  charactersApi,
  charactersQueryKey,
  checkCharacterFile,
  sortCharacters,
} from "@overlay/lib/characters";
import type { CharacterImportResult, CharacterSummary } from "@overlay/types/character";

/** The character library: import cards, create blank ones, favourite, open one to edit or chat with. */
export default function SettingsCharactersPage() {
  const { t } = useTranslation("tavern");
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [query, setQuery] = React.useState("");
  const [debounced, setDebounced] = React.useState("");
  const [createOpen, setCreateOpen] = React.useState(false);
  const [creating, setCreating] = React.useState(false);
  const [importing, setImporting] = React.useState(false);
  const [report, setReport] = React.useState<CharacterImportResult | null>(null);
  const fileInput = React.useRef<HTMLInputElement>(null);

  React.useEffect(() => {
    const timer = setTimeout(() => setDebounced(query), 250);
    return () => clearTimeout(timer);
  }, [query]);

  const list = useQuery({
    queryKey: [...charactersQueryKey, "list", debounced],
    queryFn: () => charactersApi.list(debounced),
  });
  const characters = React.useMemo(() => sortCharacters(list.data ?? []), [list.data]);

  const favorite = useMutation({
    mutationFn: (character: CharacterSummary) => charactersApi.patch(character.id, { fav: !character.fav }),
    onSuccess: (detail) => {
      queryClient.setQueryData(characterQueryKey(detail.id), detail);
      void queryClient.invalidateQueries({ queryKey: charactersQueryKey });
    },
    onError: (error) => reportSettingsError(error, t("common.saveFailed")),
  });

  const importFile = async (file: File) => {
    const check = checkCharacterFile(file);
    if (check !== "ok") {
      toast.error(check === "empty" ? t("characters.fileEmpty") : t("characters.fileTooLarge"));
      return;
    }
    setImporting(true);
    try {
      const result = await charactersApi.import(file);
      void queryClient.invalidateQueries({ queryKey: charactersQueryKey });
      setReport(result);
    } catch (error) {
      toast.error(t("characters.importFailed", { message: extractErrorMessage(error, "") }));
    } finally {
      setImporting(false);
    }
  };

  return (
    <div className="space-y-6">
      <SettingsPageHeader
        title={t("characters.title")}
        description={t("characters.description")}
        actions={
          <>
            <input
              ref={fileInput}
              type="file"
              accept=".png,.json,.charx,image/png,application/json"
              className="hidden"
              onChange={(event) => {
                const file = event.target.files?.[0];
                event.target.value = "";
                if (file) void importFile(file);
              }}
            />
            <Button type="button" variant="outline" disabled={importing} onClick={() => fileInput.current?.click()}>
              <Upload className="size-4" />
              {importing ? t("characters.importing") : t("characters.import")}
            </Button>
            <Button type="button" onClick={() => setCreateOpen(true)}>
              <Plus className="size-4" />
              {t("characters.new")}
            </Button>
          </>
        }
      />

      <div className="relative">
        <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder={t("characters.search")}
          aria-label={t("characters.search")}
          className="pl-9"
        />
      </div>

      {characters.length === 0 && !list.isLoading ? (
        <p className="rounded-xl border border-dashed px-4 py-10 text-center text-sm text-muted-foreground">
          {debounced.trim() ? t("characters.noMatch") : t("characters.empty")}
        </p>
      ) : (
        <div className="divide-y overflow-hidden rounded-xl border">
          {characters.map((character) => (
            <div
              key={character.id}
              className="flex items-center gap-3 px-3 py-2 transition hover:bg-accent/30"
            >
              <button
                type="button"
                className="flex min-w-0 flex-1 items-center gap-3 text-left"
                onClick={() => void navigate(`/settings/characters/${character.id}`)}
              >
                <CharacterAvatar name={character.name} avatarUrl={character.avatarUrl} size="lg" />
                <span className="min-w-0">
                  <span className="block truncate font-medium">{character.name || t("characters.fieldName")}</span>
                  {character.tags.length > 0 ? (
                    <span className="block truncate text-xs text-muted-foreground">
                      {character.tags.slice(0, 4).join(" · ")}
                    </span>
                  ) : null}
                </span>
              </button>
              <Button
                type="button"
                size="icon-sm"
                variant="ghost"
                aria-label={character.fav ? t("characters.unfavorite") : t("characters.favorite")}
                aria-pressed={character.fav}
                disabled={favorite.isPending}
                onClick={() => favorite.mutate(character)}
              >
                <Star className={cn("size-4", character.fav ? "fill-primary text-primary" : "text-muted-foreground")} />
              </Button>
            </div>
          ))}
        </div>
      )}

      <CreateNameDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        title={t("characters.new")}
        placeholder={t("characters.newName")}
        submitInHeader
        submitLabel={t("characters.new")}
        loading={creating}
        onSubmit={async (name) => {
          setCreating(true);
          try {
            const created = await charactersApi.create(name.trim());
            void queryClient.invalidateQueries({ queryKey: charactersQueryKey });
            setCreateOpen(false);
            void navigate(`/settings/characters/${created.id}`);
          } catch (error) {
            reportSettingsError(error, t("common.saveFailed"));
          } finally {
            setCreating(false);
          }
        }}
      />

      <ImportReportDialog
        result={report}
        onClose={() => setReport(null)}
        onOpen={(id) => {
          setReport(null);
          void navigate(`/settings/characters/${id}`);
        }}
        onAllowed={() => void queryClient.invalidateQueries({ queryKey: charactersQueryKey })}
      />
    </div>
  );
}

/** What the card brought along; scripts only run once the user allows them, and this is the first chance to. */
function ImportReportDialog({
  result,
  onClose,
  onOpen,
  onAllowed,
}: {
  result: CharacterImportResult | null;
  onClose: () => void;
  onOpen: (id: string) => void;
  onAllowed: () => void;
}) {
  const { t } = useTranslation("tavern");
  const [allowing, setAllowing] = React.useState(false);
  const report = result?.report;
  return (
    <Dialog open={result !== null} onOpenChange={(open) => (open ? undefined : onClose())}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("characters.reportTitle", { name: result?.name ?? "" })}</DialogTitle>
          <DialogDescription>{report ? `${report.cardFormat.toUpperCase()} · ${report.container}` : ""}</DialogDescription>
        </DialogHeader>
        {report ? (
          <ul className="list-disc space-y-1 pl-5 text-sm">
            {report.greetings > 0 ? <li>{t("characters.reportGreetings", { count: report.greetings })}</li> : null}
            {report.book?.outcome === "created" ? (
              <li>{t("characters.reportBookCreated", { name: report.book.name, count: report.book.entries ?? 0 })}</li>
            ) : null}
            {report.book?.outcome === "reused" ? (
              <li>{t("characters.reportBookReused", { name: report.book.name })}</li>
            ) : null}
            {report.regexScripts > 0 ? <li>{t("characters.reportRegex", { count: report.regexScripts })}</li> : null}
            {report.assets > 0 ? <li>{t("characters.assets", { count: report.assets })}</li> : null}
          </ul>
        ) : null}
        <DialogFooter>
          {report && report.regexScripts > 0 && result ? (
            <Button
              type="button"
              variant="outline"
              disabled={allowing}
              onClick={async () => {
                setAllowing(true);
                try {
                  await charactersApi.setRegexAllowed(result.id, true);
                  onAllowed();
                  onClose();
                } catch (error) {
                  reportSettingsError(error, t("common.saveFailed"));
                } finally {
                  setAllowing(false);
                }
              }}
            >
              {t("characters.reportAllow")}
            </Button>
          ) : null}
          <Button type="button" onClick={() => (result ? onOpen(result.id) : onClose())}>
            {t("characters.reportOk")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
