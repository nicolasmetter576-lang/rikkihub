import * as React from "react";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router";
import { AlertTriangle, Github, Plus, RefreshCw, Search, Trash2, Upload } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";
import { toast } from "sonner";

import { Badge } from "~/components/ui/badge";
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
import { Textarea } from "~/components/ui/textarea";
import { extractErrorMessage } from "~/lib/error";
import { cn } from "~/lib/utils";
import {
  ConfirmDeleteDialog,
  SettingsListCard,
  SettingsPageHeader,
} from "@overlay/components/settings/settings-ui";
import {
  filterInvalidSkillDirs,
  filterSkills,
  importWithOverwriteConfirm,
  invalidSkillDirsQueryKey,
  skillBadges,
  skillDetailHref,
  skillErrorCode,
  skillsApi,
  skillsQueryKey,
  SKILL_UPLOAD_MAX_BYTES,
  type InvalidSkillDir,
  type SkillSummary,
} from "@overlay/lib/skills";

/** Localized message for a failed skill call: the server's machine code first, then its text. */
export function skillErrorMessage(t: TFunction, error: unknown, fallback: string): string {
  const code = skillErrorCode(error);
  if (code) {
    const key = `skills.errors.${code}`;
    const text = t(key, { defaultValue: "" });
    if (text) return text;
  }
  return extractErrorMessage(error, fallback);
}

export function SkillBadges({ skill }: { skill: SkillSummary }) {
  const { t } = useTranslation("settings");
  return (
    <>
      {skillBadges(skill).map((badge) => (
        <Badge key={badge} variant={badge === "enabled_by" ? "outline" : "secondary"}>
          {t(`skills.badge_${badge}`, { count: skill.enabledByAssistants })}
        </Badge>
      ))}
    </>
  );
}

export function SkillListView({ skills, query }: { skills: SkillSummary[]; query: string }) {
  const { t } = useTranslation("settings");
  if (skills.length === 0) {
    return (
      <p className="rounded-xl border border-dashed px-4 py-10 text-center text-sm text-muted-foreground">
        {t("skills.empty")}
      </p>
    );
  }
  const visible = filterSkills(skills, query);
  if (visible.length === 0) {
    return (
      <p className="px-1 py-6 text-center text-sm text-muted-foreground">{t("skills.no_match")}</p>
    );
  }
  return (
    <div className="space-y-3">
      {visible.map((skill) => (
        <SettingsListCard
          key={skill.name}
          href={skillDetailHref(skill.name)}
          title={skill.name}
          description={skill.description || undefined}
          badges={<SkillBadges skill={skill} />}
        />
      ))}
    </div>
  );
}

/** Folders the app cannot read as skills; each can be deleted after a confirmation. */
export function InvalidSkillDirsView({
  dirs,
  query,
  onDelete,
}: {
  dirs: InvalidSkillDir[];
  query: string;
  onDelete: (dir: InvalidSkillDir) => void;
}) {
  const { t } = useTranslation("settings");
  const visible = filterInvalidSkillDirs(dirs, query);
  if (visible.length === 0) return null;
  return (
    <section className="space-y-3" data-invalid-skill-dirs>
      <h2 className="flex items-center gap-2 px-1 text-sm font-medium text-destructive">
        <AlertTriangle className="size-4" />
        {t("skills.invalid_dirs.title")}
      </h2>
      {visible.map((dir) => (
        <SettingsListCard
          key={dir.dirName}
          title={dir.dirName}
          description={t(`skills.invalid_dirs.reason_${dir.reason}`)}
          actions={
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              aria-label={t("common.delete")}
              title={t("common.delete")}
              onClick={() => onDelete(dir)}
            >
              <Trash2 className="size-4 text-destructive" />
            </Button>
          }
        />
      ))}
    </section>
  );
}

export default function SettingsSkillsPage() {
  const { t } = useTranslation("settings");
  const navigate = useNavigate();
  const queries = useQueryClient();
  const skills = useQuery({ queryKey: skillsQueryKey, queryFn: skillsApi.list });
  const invalidDirs = useQuery({
    queryKey: invalidSkillDirsQueryKey,
    queryFn: skillsApi.listInvalidDirs,
  });
  const [deleteDir, setDeleteDir] = React.useState<InvalidSkillDir | null>(null);
  const [deletingDir, setDeletingDir] = React.useState(false);
  const [query, setQuery] = React.useState("");
  const [createOpen, setCreateOpen] = React.useState(false);
  const [githubOpen, setGithubOpen] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const fileInputRef = React.useRef<HTMLInputElement>(null);

  const refresh = () => {
    void queries.invalidateQueries({ queryKey: skillsQueryKey });
    void queries.invalidateQueries({ queryKey: invalidSkillDirsQueryKey });
  };
  const confirmDeleteDir = async () => {
    if (!deleteDir) return;
    setDeletingDir(true);
    try {
      await skillsApi.deleteInvalidDir(deleteDir.dirName);
    } catch (error) {
      toast.error(skillErrorMessage(t, error, t("common.delete_failed")));
    } finally {
      setDeletingDir(false);
      setDeleteDir(null);
      refresh();
    }
  };
  const confirmOverwrite = (names: string[]) =>
    window.confirm(t("skills.overwrite_confirm", { names: names.join("\n") }));

  const runImport = async (run: (overwrite: boolean) => Promise<{ imported: string[] }>) => {
    setBusy(true);
    try {
      const imported = await importWithOverwriteConfirm(run, confirmOverwrite);
      if (imported === null) return false;
      toast.success(t("skills.imported", { names: imported.join(", ") }));
      return true;
    } catch (error) {
      toast.error(skillErrorMessage(t, error, t("common.create_failed")));
      return false;
    } finally {
      setBusy(false);
      refresh();
    }
  };

  const onFilePicked = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    if (file.size > SKILL_UPLOAD_MAX_BYTES) {
      toast.error(t("skills.too_large", { max: "16 MiB" }));
      return;
    }
    await runImport((overwrite) => skillsApi.importFile(file, overwrite));
  };

  return (
    <div className="space-y-6">
      <SettingsPageHeader
        title={t("skills.title")}
        description={t("skills.description")}
        actions={
          <div className="flex flex-wrap gap-2">
            <Button type="button" onClick={() => setCreateOpen(true)} disabled={busy}>
              <Plus className="size-4" />
              {t("skills.new")}
            </Button>
            <Button
              type="button"
              variant="outline"
              title={t("skills.import_file_hint")}
              disabled={busy}
              onClick={() => fileInputRef.current?.click()}
            >
              <Upload className="size-4" />
              {t("skills.import_file")}
            </Button>
            <Button
              type="button"
              variant="outline"
              disabled={busy}
              onClick={() => setGithubOpen(true)}
            >
              <Github className="size-4" />
              {t("skills.import_github")}
            </Button>
          </div>
        }
      />
      <input
        ref={fileInputRef}
        type="file"
        accept=".zip,.md,.markdown"
        className="hidden"
        aria-label={t("skills.import_file")}
        onChange={(event) => void onFilePicked(event)}
      />

      <div className="flex items-center gap-2">
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder={t("skills.search_placeholder")}
            aria-label={t("skills.search_placeholder")}
            className="pl-8"
          />
        </div>
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          aria-label={t("skills.refresh")}
          title={t("skills.refresh")}
          disabled={skills.isFetching}
          onClick={refresh}
        >
          <RefreshCw className={cn("size-4", skills.isFetching && "animate-spin")} />
        </Button>
      </div>

      {skills.isPending ? (
        <p className="text-sm text-muted-foreground">{t("common.loading")}</p>
      ) : null}
      {skills.isError ? (
        <div role="alert" className="flex items-center gap-2 text-sm text-destructive">
          {t("skills.load_failed")}
          <Button type="button" size="sm" variant="outline" onClick={refresh}>
            {t("skills.refresh")}
          </Button>
        </div>
      ) : null}
      {skills.data ? <SkillListView skills={skills.data} query={query} /> : null}
      {invalidDirs.data ? (
        <InvalidSkillDirsView dirs={invalidDirs.data} query={query} onDelete={setDeleteDir} />
      ) : null}

      <ConfirmDeleteDialog
        open={deleteDir !== null}
        onOpenChange={(open) => {
          if (!open) setDeleteDir(null);
        }}
        title={t("skills.invalid_dirs.delete_title")}
        description={t("skills.invalid_dirs.delete_confirm", { name: deleteDir?.dirName ?? "" })}
        loading={deletingDir}
        onConfirm={confirmDeleteDir}
      />
      <CreateSkillDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreated={(name) => {
          refresh();
          void navigate(skillDetailHref(name));
        }}
      />
      <GitHubImportDialog
        open={githubOpen}
        onOpenChange={setGithubOpen}
        busy={busy}
        onImport={async (url) => {
          if (await runImport((overwrite) => skillsApi.importGitHub(url, overwrite)))
            setGithubOpen(false);
        }}
      />
    </div>
  );
}

function CreateSkillDialog({
  open,
  onOpenChange,
  onCreated,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCreated: (name: string) => void;
}) {
  const { t } = useTranslation("settings");
  const [name, setName] = React.useState("");
  const [description, setDescription] = React.useState("");
  const [saving, setSaving] = React.useState(false);
  const formId = React.useId();

  React.useEffect(() => {
    if (open) {
      setName("");
      setDescription("");
    }
  }, [open]);

  const submit = async () => {
    if (!name.trim() || !description.trim()) {
      toast.error(t("skills.create_required"));
      return;
    }
    setSaving(true);
    try {
      const created = await skillsApi.create(name.trim(), description.trim());
      toast.success(t("common.created"));
      onOpenChange(false);
      onCreated(created.imported[0] ?? name.trim());
    } catch (error) {
      toast.error(skillErrorMessage(t, error, t("common.create_failed")));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("skills.create_title")}</DialogTitle>
          <DialogDescription>{t("skills.name_hint")}</DialogDescription>
        </DialogHeader>
        <form
          id={formId}
          className="space-y-3"
          onSubmit={(event) => {
            event.preventDefault();
            void submit();
          }}
        >
          <label className="flex flex-col gap-1.5 text-sm">
            <span className="font-medium">{t("skills.name")}</span>
            <Input
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder={t("skills.name_placeholder")}
              autoFocus
            />
          </label>
          <label className="flex flex-col gap-1.5 text-sm">
            <span className="font-medium">{t("skills.description_label")}</span>
            <Textarea
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              placeholder={t("skills.description_placeholder")}
              rows={3}
            />
          </label>
        </form>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
            {t("common.cancel")}
          </Button>
          <Button type="submit" form={formId} disabled={saving}>
            {saving ? t("common.creating") : t("common.create")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function GitHubImportDialog({
  open,
  onOpenChange,
  busy,
  onImport,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  busy: boolean;
  onImport: (url: string) => Promise<void>;
}) {
  const { t } = useTranslation("settings");
  const [url, setUrl] = React.useState("");
  const formId = React.useId();

  React.useEffect(() => {
    if (open) setUrl("");
  }, [open]);

  return (
    <Dialog open={open} onOpenChange={(next) => (busy ? undefined : onOpenChange(next))}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("skills.github_title")}</DialogTitle>
          <DialogDescription>{t("skills.github_hint")}</DialogDescription>
        </DialogHeader>
        <form
          id={formId}
          onSubmit={(event) => {
            event.preventDefault();
            if (url.trim()) void onImport(url.trim());
          }}
        >
          <label className="flex flex-col gap-1.5 text-sm">
            <span className="font-medium">{t("skills.github_url")}</span>
            <Input
              value={url}
              onChange={(event) => setUrl(event.target.value)}
              placeholder={t("skills.github_url_placeholder")}
              inputMode="url"
              autoFocus
            />
          </label>
        </form>
        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            disabled={busy}
            onClick={() => onOpenChange(false)}
          >
            {t("common.cancel")}
          </Button>
          <Button type="submit" form={formId} disabled={busy || !url.trim()}>
            {busy ? t("skills.importing") : t("skills.import")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
