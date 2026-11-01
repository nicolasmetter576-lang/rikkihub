import * as React from "react";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router";
import {
  ArrowLeft,
  CopyPlus,
  Download,
  FilePlus,
  FileText,
  Folder,
  RotateCcw,
  Trash2,
} from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import { cn } from "~/lib/utils";
import { ApiError } from "~/services/api";
import {
  ConfirmDeleteDialog,
  CreateNameDialog,
  SettingsPageHeader,
} from "@overlay/components/settings/settings-ui";
import { WorkspaceTextEditor } from "@overlay/components/workspace/workspace-text-editor";
import { formatBytes } from "@overlay/lib/storage-utils";
import {
  normalizeNewSkillFilePath,
  SKILL_MD,
  skillDownloadUrl,
  skillErrorCode,
  skillFileRows,
  skillFilesQueryKey,
  skillsApi,
  skillsQueryKey,
  type SkillFile,
  type SkillFilesResponse,
} from "@overlay/lib/skills";
import { SkillBadges, skillErrorMessage } from "@overlay/routes/settings.skills";

interface EditorState {
  path: string;
  content: string;
  original: string;
  revision: string;
}

export function SkillFileTree({
  files,
  selected,
  onSelect,
}: {
  files: SkillFile[];
  selected: string | null;
  onSelect: (path: string) => void;
}) {
  return (
    <ul className="space-y-0.5" role="tree">
      {skillFileRows(files).map((row) =>
        row.kind === "dir" ? (
          <li
            key={`dir:${row.path}`}
            className="flex items-center gap-1.5 px-2 py-1 text-xs text-muted-foreground"
            style={{ paddingLeft: `${0.5 + row.depth * 0.875}rem` }}
          >
            <Folder className="size-3.5 shrink-0" />
            <span className="truncate">{row.name}</span>
          </li>
        ) : (
          <li key={row.file.path} role="treeitem" aria-selected={selected === row.file.path}>
            <button
              type="button"
              title={row.file.path}
              className={cn(
                "flex w-full items-center gap-1.5 rounded-md px-2 py-1 text-left text-sm transition",
                selected === row.file.path
                  ? "bg-accent text-foreground"
                  : "text-muted-foreground hover:bg-accent/60 hover:text-foreground",
              )}
              style={{ paddingLeft: `${0.5 + row.depth * 0.875}rem` }}
              onClick={() => onSelect(row.file.path)}
            >
              <FileText className="size-3.5 shrink-0" />
              <span className="min-w-0 flex-1 truncate">{row.name}</span>
            </button>
          </li>
        ),
      )}
    </ul>
  );
}

export default function SettingsSkillDetailPage() {
  const { t } = useTranslation("settings");
  const { name = "" } = useParams();
  const navigate = useNavigate();
  const queries = useQueryClient();
  const filesQuery = useQuery({
    queryKey: skillFilesQueryKey(name),
    queryFn: () => skillsApi.files(name),
    enabled: name.length > 0,
    retry: (count, error) => !(error instanceof ApiError && error.code === 404) && count < 2,
  });
  const skillsQuery = useQuery({ queryKey: skillsQueryKey, queryFn: skillsApi.list });
  const summary = skillsQuery.data?.find((skill) => skill.name === name);
  const listing: SkillFilesResponse | undefined = filesQuery.data;
  const readOnly = listing?.readOnly ?? true;

  const [selected, setSelected] = React.useState<string | null>(null);
  const [editor, setEditor] = React.useState<EditorState | null>(null);
  const [loadingFile, setLoadingFile] = React.useState(false);
  const [conflict, setConflict] = React.useState(false);
  const [saving, setSaving] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [newFileOpen, setNewFileOpen] = React.useState(false);
  const [deleteFileOpen, setDeleteFileOpen] = React.useState(false);
  const [deleteSkillOpen, setDeleteSkillOpen] = React.useState(false);
  const loadRequestRef = React.useRef(0);

  const dirty = editor !== null && editor.content !== editor.original;
  const selectedFile = listing?.files.find((file) => file.path === selected) ?? null;

  const refreshListing = React.useCallback(() => {
    void queries.invalidateQueries({ queryKey: skillFilesQueryKey(name) });
    void queries.invalidateQueries({ queryKey: skillsQueryKey, exact: true });
  }, [name, queries]);

  const loadFile = React.useCallback(
    async (path: string) => {
      const requestId = ++loadRequestRef.current;
      setLoadingFile(true);
      setConflict(false);
      try {
        const file = await skillsApi.readFile(name, path);
        if (requestId !== loadRequestRef.current) return;
        setEditor({
          path: file.path,
          content: file.content,
          original: file.content,
          revision: file.revision,
        });
      } catch (error) {
        if (requestId !== loadRequestRef.current) return;
        setEditor(null);
        toast.error(skillErrorMessage(t, error, t("common.not_found")));
      } finally {
        if (requestId === loadRequestRef.current) setLoadingFile(false);
      }
    },
    [name, t],
  );

  // Open SKILL.md first; follow the selection when the file list changes underneath (e.g. deleted elsewhere).
  React.useEffect(() => {
    if (!listing) return;
    const exists = selected !== null && listing.files.some((file) => file.path === selected);
    if (!exists)
      setSelected(
        listing.files.some((file) => file.path === SKILL_MD)
          ? SKILL_MD
          : (listing.files[0]?.path ?? null),
      );
  }, [listing, selected]);

  React.useEffect(() => {
    if (!selected || !selectedFile) return;
    if (editor?.path === selected) return;
    if (selectedFile.text) void loadFile(selected);
    else {
      loadRequestRef.current++;
      setEditor(null);
      setLoadingFile(false);
    }
  }, [selected, selectedFile, editor?.path, loadFile]);

  React.useEffect(() => {
    if (!dirty) return;
    const onBeforeUnload = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, [dirty]);

  const select = (path: string) => {
    if (path === selected) return;
    if (dirty && !window.confirm(t("skills.detail.discard_confirm"))) return;
    setEditor(null);
    setSelected(path);
  };

  const save = async () => {
    if (!editor || readOnly) return;
    setSaving(true);
    try {
      const saved = await skillsApi.writeFile(name, editor.path, {
        content: editor.content,
        expectedRevision: editor.revision,
      });
      setEditor((current) =>
        current && current.path === saved.path
          ? { ...current, original: saved.content, revision: saved.revision }
          : current,
      );
      setConflict(false);
      toast.success(t("common.saved"));
      refreshListing();
    } catch (error) {
      if (skillErrorCode(error) === "FILE_REVISION_MISMATCH") setConflict(true);
      else toast.error(skillErrorMessage(t, error, t("common.save_failed")));
    } finally {
      setSaving(false);
    }
  };

  const run = async (operation: () => Promise<void>, fallback: string) => {
    setBusy(true);
    try {
      await operation();
    } catch (error) {
      toast.error(skillErrorMessage(t, error, fallback));
      refreshListing();
    } finally {
      setBusy(false);
    }
  };

  if (filesQuery.isError) {
    const missing = filesQuery.error instanceof ApiError && filesQuery.error.code === 404;
    return (
      <div className="space-y-3">
        <BackLink />
        <p role="alert" className="text-sm text-muted-foreground">
          {missing
            ? t("skills.detail.not_found")
            : skillErrorMessage(t, filesQuery.error, t("skills.load_failed"))}
        </p>
      </div>
    );
  }
  if (!listing) {
    return <p className="text-sm text-muted-foreground">{t("common.loading")}</p>;
  }

  return (
    <div className="space-y-4">
      <BackLink />
      <SettingsPageHeader
        title={listing.name}
        description={summary?.description}
        actions={
          <div className="flex flex-wrap gap-2">
            {listing.builtin ? (
              <Button
                type="button"
                disabled={busy}
                onClick={() =>
                  void run(async () => {
                    await skillsApi.override(name);
                    toast.success(t("skills.detail.copied"));
                    setEditor(null);
                    refreshListing();
                  }, t("common.create_failed"))
                }
              >
                <CopyPlus className="size-4" />
                {t("skills.detail.copy_to_edit")}
              </Button>
            ) : (
              <Button
                type="button"
                variant="destructive"
                disabled={busy}
                onClick={() => setDeleteSkillOpen(true)}
              >
                <Trash2 className="size-4" />
                {t("skills.detail.delete_skill")}
              </Button>
            )}
          </div>
        }
      />
      {summary ? (
        <div className="flex flex-wrap gap-2">
          <SkillBadges skill={summary} />
        </div>
      ) : null}
      {listing.builtin ? (
        <p className="rounded-md bg-muted px-3 py-2 text-xs text-muted-foreground">
          {t("skills.detail.readonly_builtin")}
        </p>
      ) : summary?.overridesBuiltin ? (
        <p className="rounded-md bg-muted px-3 py-2 text-xs text-muted-foreground">
          {t("skills.detail.override_hint")}
        </p>
      ) : null}

      <div className="grid gap-4 md:grid-cols-[14rem_minmax(0,1fr)]">
        <aside className="space-y-2 rounded-xl border p-2">
          <div className="flex items-center justify-between gap-2 px-1">
            <span className="text-xs font-medium text-muted-foreground">
              {t("skills.detail.files")}
            </span>
            {!readOnly ? (
              <Button
                type="button"
                variant="ghost"
                size="icon-sm"
                aria-label={t("skills.detail.new_file")}
                title={t("skills.detail.new_file")}
                disabled={busy}
                onClick={() => setNewFileOpen(true)}
              >
                <FilePlus className="size-4" />
              </Button>
            ) : null}
          </div>
          <div className="max-h-[40vh] overflow-y-auto md:max-h-[60vh]">
            <SkillFileTree files={listing.files} selected={selected} onSelect={select} />
          </div>
        </aside>

        <section className="flex min-w-0 flex-col gap-2 rounded-xl border p-2">
          {selectedFile ? (
            <>
              <div className="flex flex-wrap items-center gap-2 px-1">
                <code className="min-w-0 flex-1 truncate text-xs">{selectedFile.path}</code>
                {dirty ? (
                  <span className="text-xs text-amber-600 dark:text-amber-400">
                    {t("skills.detail.unsaved")}
                  </span>
                ) : null}
                <Button type="button" variant="ghost" size="icon-sm" asChild>
                  <a
                    href={skillDownloadUrl(name, selectedFile.path)}
                    download
                    aria-label={t("skills.detail.download")}
                    title={t("skills.detail.download")}
                  >
                    <Download className="size-4" />
                  </a>
                </Button>
                {!readOnly && selectedFile.path !== SKILL_MD ? (
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    aria-label={t("skills.detail.delete_file")}
                    title={t("skills.detail.delete_file")}
                    disabled={busy}
                    onClick={() => setDeleteFileOpen(true)}
                  >
                    <Trash2 className="size-4" />
                  </Button>
                ) : null}
              </div>
              {conflict ? (
                <div
                  role="alert"
                  className="flex flex-wrap items-center gap-2 rounded-md border border-destructive/40 px-3 py-2 text-sm text-destructive"
                >
                  <span className="flex-1">{t("skills.detail.conflict")}</span>
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    onClick={() => void loadFile(selectedFile.path)}
                  >
                    <RotateCcw className="size-4" />
                    {t("skills.detail.reload")}
                  </Button>
                </div>
              ) : null}
              {!selectedFile.text ? (
                <p className="px-1 py-6 text-sm text-muted-foreground">
                  {t("skills.detail.binary_hint", { size: formatBytes(selectedFile.size) })}
                </p>
              ) : editor && editor.path === selectedFile.path ? (
                <>
                  <WorkspaceTextEditor
                    value={editor.content}
                    onChange={(content) =>
                      setEditor((current) => (current ? { ...current, content } : current))
                    }
                    readOnly={readOnly}
                    readOnlyNotice={null}
                    fileName={selectedFile.path}
                    tuiqiaoKey={`skill:${name}:${selectedFile.path}`}
                    className="h-[60vh] min-h-72 rounded-md border"
                  />
                  {!readOnly ? (
                    <div className="flex gap-2">
                      <Button type="button" disabled={!dirty || saving} onClick={() => void save()}>
                        {saving ? t("common.saving") : t("common.save")}
                      </Button>
                    </div>
                  ) : null}
                </>
              ) : (
                <p className="px-1 py-6 text-sm text-muted-foreground">
                  {loadingFile ? t("common.loading") : ""}
                </p>
              )}
            </>
          ) : (
            <p className="px-1 py-6 text-sm text-muted-foreground">
              {t("skills.detail.select_file")}
            </p>
          )}
        </section>
      </div>

      <CreateNameDialog
        open={newFileOpen}
        onOpenChange={setNewFileOpen}
        title={t("skills.detail.new_file_title")}
        placeholder={t("skills.detail.new_file_placeholder")}
        loading={busy}
        onSubmit={async (raw) => {
          const path = normalizeNewSkillFilePath(raw);
          if (!path) {
            toast.error(t("skills.detail.new_file_invalid"));
            return;
          }
          if (dirty && !window.confirm(t("skills.detail.discard_confirm"))) return;
          await run(async () => {
            await skillsApi.writeFile(name, path, { content: "", overwrite: false });
            setNewFileOpen(false);
            // Select only once the listing has the file, or the selection would fall back to SKILL.md.
            await queries.invalidateQueries({ queryKey: skillFilesQueryKey(name) });
            setEditor(null);
            setSelected(path);
          }, t("common.create_failed"));
        }}
      />
      <ConfirmDeleteDialog
        open={deleteFileOpen}
        onOpenChange={setDeleteFileOpen}
        title={t("skills.detail.delete_file")}
        description={t("skills.detail.delete_file_confirm", { path: selectedFile?.path ?? "" })}
        loading={busy}
        onConfirm={() =>
          run(async () => {
            if (!selectedFile) return;
            await skillsApi.deleteFile(name, selectedFile.path);
            setDeleteFileOpen(false);
            setEditor(null);
            setSelected(SKILL_MD);
            refreshListing();
          }, t("common.delete_failed"))
        }
      />
      <ConfirmDeleteDialog
        open={deleteSkillOpen}
        onOpenChange={setDeleteSkillOpen}
        title={t("skills.detail.delete_skill")}
        description={t(
          summary?.overridesBuiltin
            ? "skills.detail.delete_override_confirm"
            : "skills.detail.delete_skill_confirm",
          { name },
        )}
        loading={busy}
        onConfirm={() =>
          run(async () => {
            const result = await skillsApi.delete(name);
            setDeleteSkillOpen(false);
            setEditor(null);
            refreshListing();
            if (result.revealedBuiltin) toast.success(t("skills.detail.deleted_revealed"));
            else {
              toast.success(t("common.deleted"));
              void navigate("/settings/skills");
            }
          }, t("common.delete_failed"))
        }
      />
    </div>
  );
}

function BackLink() {
  const { t } = useTranslation("settings");
  return (
    <Button variant="ghost" size="sm" asChild>
      <Link to="/settings/skills">
        <ArrowLeft className="size-4" />
        {t("skills.detail.back")}
      </Link>
    </Button>
  );
}
