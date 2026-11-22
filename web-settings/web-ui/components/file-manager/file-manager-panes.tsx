import * as React from "react";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ArrowUpLeft,
  Archive,
  CheckCircle2,
  Copy,
  Download,
  File as FileIcon,
  FileUp,
  Folder,
  FolderPlus,
  HardDrive,
  Library,
  MoreHorizontal,
  Move,
  Pencil,
  RefreshCw,
  Send,
  Trash2,
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
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "~/components/ui/dropdown-menu";
import { Input } from "~/components/ui/input";
import { Spinner } from "~/components/ui/spinner";
import { cn } from "~/lib/utils";
import type { UploadedFileDto } from "~/types/dto";
import { reportSettingsError } from "@overlay/components/settings/settings-ui";
import { useWorkspaceEditorSession } from "@overlay/components/workspace/use-workspace-editor-session";
import { WorkspaceCreateFileDialog } from "@overlay/components/workspace/workspace-create-file-dialog";
import { WorkspaceExtractDialog } from "@overlay/components/workspace/workspace-extract-dialog";
import { WorkspaceTextEditor } from "@overlay/components/workspace/workspace-text-editor";
import {
  deviceRoot,
  downloadFileManagerZip,
  fileManagerErrorKey,
  fileManagerApi,
  fileManagerFileUrl,
  isRestrictedDevicePath,
  LIBRARY_ROOT,
  pathCrumbs,
  visibleEntries,
  type FileManagerRoot,
  type FileManagerRoots,
  type FileManagerSort,
} from "@overlay/lib/file-manager";
import {
  detectWorkspaceFileKind,
  formatWorkspaceBytes,
  isWorkspaceArchiveName,
  joinWorkspacePath,
  parentWorkspacePath,
  saveBlobAs,
  summarizeWorkspaceTransfer,
  workspaceTransferErrorKey,
} from "@overlay/lib/workspace";
import type { WorkspaceFileEntry } from "@overlay/types/workspace";

type Side = "left" | "right";
const other = (side: Side): Side => (side === "left" ? "right" : "left");

interface PaneState {
  root: FileManagerRoot;
  path: string;
  selected: Set<string>;
}

const rootsKey = ["file-manager", "roots"] as const;
const listKey = (root: FileManagerRoot, path: string) => ["file-manager", "list", root, path] as const;

/**
 * Two panes side by side (MT style): the library on the left, the phone's storage on the right by
 * default; either can switch root. Copy / Move put the selection of one pane into the folder open
 * on the other. With [onSend] the panes also send the selected files to the chat.
 */
export function FileManagerPanes({
  onSend,
  className,
}: {
  onSend?: (files: UploadedFileDto[]) => void;
  className?: string;
}) {
  const { t } = useTranslation("fileManager");
  const { t: tw } = useTranslation("workspace");
  const queryClient = useQueryClient();
  const roots = useQuery({ queryKey: rootsKey, queryFn: fileManagerApi.roots });
  const primary = roots.data?.volumes.find((volume) => volume.primary) ?? roots.data?.volumes[0];

  const [panes, setPanes] = React.useState<Record<Side, PaneState>>({
    left: { root: LIBRARY_ROOT, path: "", selected: new Set() },
    right: { root: "", path: "", selected: new Set() },
  });
  React.useEffect(() => {
    if (primary && !panes.right.root) setPanes((current) => ({ ...current, right: { ...current.right, root: deviceRoot(primary.id) } }));
  }, [primary, panes.right.root]);
  const [active, setActive] = React.useState<Side>("left");
  const [sort, setSort] = React.useState<FileManagerSort>("name");
  const [showHidden, setShowHidden] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [entriesBySide, setEntriesBySide] = React.useState<Record<Side, WorkspaceFileEntry[]>>({ left: [], right: [] });

  const updatePane = (side: Side, patch: Partial<PaneState>) =>
    setPanes((current) => ({ ...current, [side]: { ...current[side], ...patch } }));
  const reload = () => void queryClient.invalidateQueries({ queryKey: ["file-manager"] });
  const clearSelection = () =>
    setPanes((current) => ({ left: { ...current.left, selected: new Set() }, right: { ...current.right, selected: new Set() } }));

  const opSide: Side | null = panes[active].selected.size > 0 ? active : panes[other(active)].selected.size > 0 ? other(active) : null;
  const selectedEntries = (side: Side) => entriesBySide[side].filter((entry) => panes[side].selected.has(entry.path));
  const single = opSide ? (selectedEntries(opSide).length === 1 ? selectedEntries(opSide)[0] : null) : null;
  const totalSelected = panes.left.selected.size + panes.right.selected.size;

  const reportError = (error: unknown, fallback: string) => {
    const key = fileManagerErrorKey(error);
    if (key) toast.error(t(key));
    else reportSettingsError(error, fallback);
  };

  const run = async (action: () => Promise<void>) => {
    setBusy(true);
    try {
      await action();
    } catch (error) {
      reportError(error, t("failed"));
    } finally {
      setBusy(false);
      reload();
    }
  };

  const transfer = (mode: "copy" | "move") => {
    if (!opSide) return;
    const from = panes[opSide];
    const to = panes[other(opSide)];
    void run(async () => {
      const result = await fileManagerApi.transfer(from.root, [...from.selected], to.root, to.path, mode);
      const summary = summarizeWorkspaceTransfer(result);
      clearSelection();
      if (summary.failures.length === 0) {
        toast.success(t(mode === "copy" ? "copied" : "moved", { count: summary.succeeded }));
      } else {
        toast.error(
          t("transfer_partial", { done: summary.succeeded, failed: summary.failures.length }) +
            "\n" +
            summary.failures.map((item) => `${item.source.split("/").pop()}: ${tw(workspaceTransferErrorKey(item.errorCode))}`).join("\n"),
        );
      }
    });
  };

  const [deleteOpen, setDeleteOpen] = React.useState(false);
  const [renameTarget, setRenameTarget] = React.useState<{ side: Side; entry: WorkspaceFileEntry } | null>(null);
  const [zipSide, setZipSide] = React.useState<Side | null>(null);
  const [extractTarget, setExtractTarget] = React.useState<{ side: Side; entry: WorkspaceFileEntry } | null>(null);
  const [createSide, setCreateSide] = React.useState<Side | null>(null);
  const [previewImage, setPreviewImage] = React.useState<string | null>(null);
  const uploadInput = React.useRef<HTMLInputElement>(null);

  const [editorTarget, setEditorTarget] = React.useState<{ root: FileManagerRoot; path: string } | null>(null);
  const editorTargetRef = React.useRef(editorTarget);
  editorTargetRef.current = editorTarget;
  const { session, requestLeave, notice, guard } = useWorkspaceEditorSession((path, source, revision) => {
    const target = editorTargetRef.current;
    if (!target) return Promise.reject(new Error("No open file"));
    return fileManagerApi.writeText(target.root, path, source, revision);
  });
  const openEditor = (root: FileManagerRoot, path: string) =>
    requestLeave(async () => {
      try {
        const text = await fileManagerApi.readText(root, path);
        session.discard();
        if (session.load({ path, content: text.content, revision: text.revision, editable: true })) setEditorTarget({ root, path });
      } catch (error) {
        reportError(error, t("open_failed"));
      }
    });
  const closeEditor = () => requestLeave(() => { session.discard(); setEditorTarget(null); });

  const openEntry = (side: Side, entry: WorkspaceFileEntry) => {
    const pane = panes[side];
    if (entry.isDirectory) {
      updatePane(side, { path: entry.path, selected: new Set() });
      return;
    }
    if (isWorkspaceArchiveName(entry.name)) {
      setExtractTarget({ side, entry });
      return;
    }
    switch (detectWorkspaceFileKind(entry.name)) {
      case "text":
        openEditor(pane.root, entry.path);
        break;
      case "image":
        setPreviewImage(fileManagerFileUrl(pane.root, entry.path));
        break;
      default:
        window.open(fileManagerFileUrl(pane.root, entry.path), "_blank", "noopener");
    }
  };

  const upload = (files: File[], side: Side = active) => {
    if (files.length === 0) return;
    const pane = panes[side];
    void run(async () => {
      const result = await fileManagerApi.upload(pane.root, pane.path, files);
      toast.success(t("uploaded", { count: result.entries.length }));
    });
  };

  const download = () => {
    if (!opSide) return;
    const pane = panes[opSide];
    if (single && !single.isDirectory) {
      window.location.href = fileManagerFileUrl(pane.root, single.path, true);
      return;
    }
    void run(async () => {
      const { blob, fileName } = await downloadFileManagerZip(pane.root, [...pane.selected], `${t("title")}.zip`);
      saveBlobAs(blob, fileName);
    });
  };

  const send = () => {
    if (!onSend) return;
    const bySide = (["left", "right"] as Side[]).map((side) => ({
      side,
      files: selectedEntries(side).filter((entry) => !entry.isDirectory).map((entry) => entry.path),
    }));
    if ((["left", "right"] as Side[]).some((side) => selectedEntries(side).some((entry) => entry.isDirectory))) {
      toast.info(t("send_folders_hint"));
    }
    if (bySide.every((group) => group.files.length === 0)) return;
    void run(async () => {
      const files: UploadedFileDto[] = [];
      for (const group of bySide) {
        if (group.files.length === 0) continue;
        const result = await fileManagerApi.attach(panes[group.side].root, group.files);
        files.push(...result.files);
        result.unsupported.forEach((name) => toast.warning(t("unsupported", { name })));
        result.failed.forEach((name) => toast.error(t("attach_failed", { name })));
      }
      clearSelection();
      if (files.length > 0) onSend(files);
    });
  };

  const rootOptions: { root: FileManagerRoot; label: string; device: boolean }[] = [
    { root: LIBRARY_ROOT, label: t("library"), device: false },
    ...(roots.data?.volumes ?? []).map((volume) => ({ root: deviceRoot(volume.id), label: volume.label, device: true })),
  ];

  return (
    <div
      className={cn("flex min-h-0 flex-col", className)}
      onDragOver={(event) => event.preventDefault()}
      onDrop={(event) => {
        event.preventDefault();
        upload(Array.from(event.dataTransfer.files));
      }}
    >
      {guard}
      <div className="flex flex-wrap items-center gap-1 border-b px-2 py-1.5">
        {opSide ? (
          <>
            <Button size="sm" variant="ghost" disabled={busy} onClick={() => transfer("copy")}><Copy className="size-4" />{t("copy")}</Button>
            <Button size="sm" variant="ghost" disabled={busy} onClick={() => transfer("move")}><Move className="size-4" />{t("move")}</Button>
            <Button size="sm" variant="ghost" disabled={busy} onClick={() => setDeleteOpen(true)}><Trash2 className="size-4" />{t("delete")}</Button>
            <Button size="sm" variant="ghost" disabled={busy} onClick={download}><Download className="size-4" />{t("download")}</Button>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button size="sm" variant="ghost" disabled={busy}><MoreHorizontal className="size-4" />{t("more")}</Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent>
                <DropdownMenuItem onSelect={() => {
                  const all = visibleEntries(entriesBySide[opSide], sort, showHidden).map((entry) => entry.path);
                  updatePane(opSide, { selected: panes[opSide].selected.size === all.length ? new Set() : new Set(all) });
                }}>{t("select_all")}</DropdownMenuItem>
                {single ? <DropdownMenuItem onSelect={() => setRenameTarget({ side: opSide, entry: single })}><Pencil className="size-4" />{t("rename")}</DropdownMenuItem> : null}
                <DropdownMenuItem onSelect={() => setZipSide(opSide)}><Archive className="size-4" />{t("zip")}</DropdownMenuItem>
                {single && !single.isDirectory && isWorkspaceArchiveName(single.name) ? (
                  <DropdownMenuItem onSelect={() => setExtractTarget({ side: opSide, entry: single })}>{t("extract")}</DropdownMenuItem>
                ) : null}
              </DropdownMenuContent>
            </DropdownMenu>
            <span className="text-xs text-muted-foreground">{t("selected", { count: panes[opSide].selected.size })}</span>
          </>
        ) : (
          <>
            <Button size="sm" variant="ghost" disabled={busy} onClick={() => setCreateSide(active)}><FolderPlus className="size-4" />{t("new")}</Button>
            <Button size="sm" variant="ghost" disabled={busy} onClick={() => uploadInput.current?.click()}><FileUp className="size-4" />{t("upload")}</Button>
            <span className="hidden text-xs text-muted-foreground sm:inline">{t(onSend ? "pick_hint" : "select_hint")}</span>
          </>
        )}
        <div className="ml-auto flex items-center gap-1">
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button size="sm" variant="ghost">{t("view")}</Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuRadioGroup value={sort} onValueChange={(value) => setSort(value as FileManagerSort)}>
                <DropdownMenuRadioItem value="name">{t("sort_name")}</DropdownMenuRadioItem>
                <DropdownMenuRadioItem value="time">{t("sort_time")}</DropdownMenuRadioItem>
                <DropdownMenuRadioItem value="size">{t("sort_size")}</DropdownMenuRadioItem>
              </DropdownMenuRadioGroup>
              <DropdownMenuSeparator />
              <DropdownMenuCheckboxItem checked={showHidden} onCheckedChange={(value) => setShowHidden(Boolean(value))}>{t("show_hidden")}</DropdownMenuCheckboxItem>
            </DropdownMenuContent>
          </DropdownMenu>
          <Button size="icon" variant="ghost" onClick={() => { void roots.refetch(); reload(); }} aria-label={t("refresh")}><RefreshCw className="size-4" /></Button>
          {onSend ? (
            <Button size="sm" disabled={busy || totalSelected === 0} onClick={send}><Send className="size-4" />{t("send", { count: totalSelected })}</Button>
          ) : null}
        </div>
        <input
          ref={uploadInput}
          type="file"
          multiple
          hidden
          onChange={(event) => {
            upload(Array.from(event.target.files ?? []));
            event.target.value = "";
          }}
        />
      </div>

      <div className="grid min-h-0 flex-1 grid-cols-2 divide-x">
        {(["left", "right"] as Side[]).map((side) => (
          <FilePane
            key={side}
            pane={panes[side]}
            roots={roots.data}
            rootOptions={rootOptions}
            active={active === side}
            sort={sort}
            showHidden={showHidden}
            onActivate={() => setActive(side)}
            onEntries={(entries) => setEntriesBySide((current) => (current[side] === entries ? current : { ...current, [side]: entries }))}
            onNavigate={(root, path) => updatePane(side, { root, path, selected: new Set() })}
            onToggle={(path) => {
              const selected = new Set(panes[side].selected);
              if (selected.has(path)) selected.delete(path);
              else selected.add(path);
              updatePane(side, { selected });
            }}
            onOpen={(entry) => openEntry(side, entry)}
            onDropFiles={(files) => { setActive(side); upload(files, side); }}
          />
        ))}
      </div>

      <Dialog open={deleteOpen} onOpenChange={setDeleteOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("delete_title", { count: opSide ? panes[opSide].selected.size : 0 })}</DialogTitle>
            <DialogDescription>{t("delete_desc")}</DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteOpen(false)}>{t("cancel")}</Button>
            <Button variant="destructive" onClick={() => {
              setDeleteOpen(false);
              if (!opSide) return;
              const pane = panes[opSide];
              void run(async () => {
                const result = await fileManagerApi.delete(pane.root, [...pane.selected]);
                clearSelection();
                if (result.failures.length === 0) toast.success(t("deleted", { count: result.deletedPaths.length }));
                else toast.error(t("delete_partial", { done: result.deletedPaths.length, failed: result.failures.length }));
              });
            }}>{t("delete")}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <NameDialog
        open={renameTarget !== null}
        title={t("rename")}
        initial={renameTarget?.entry.name ?? ""}
        onClose={() => setRenameTarget(null)}
        onSubmit={(name) => {
          const target = renameTarget;
          setRenameTarget(null);
          if (target) void run(async () => { await fileManagerApi.rename(panes[target.side].root, target.entry.path, name); });
        }}
      />

      <NameDialog
        open={zipSide !== null}
        title={t("zip_name")}
        initial={zipSide ? (single?.name.replace(/\.[^.]+$/, "") ?? (panes[zipSide].path.split("/").pop() || "archive")) : ""}
        onClose={() => setZipSide(null)}
        onSubmit={(name) => {
          const side = zipSide;
          setZipSide(null);
          if (!side) return;
          const pane = panes[side];
          void run(async () => {
            const entry = await fileManagerApi.zip(pane.root, pane.path, [...pane.selected], name);
            clearSelection();
            toast.success(t("zip_done", { name: entry.name }));
          });
        }}
      />

      <WorkspaceExtractDialog
        entry={extractTarget?.entry ?? null}
        extract={(path, password, nameEncoding) => fileManagerApi.extract(panes[extractTarget?.side ?? "left"].root, path, password, nameEncoding)}
        onOpenChange={(open) => { if (!open) setExtractTarget(null); }}
        onExtracted={() => { clearSelection(); reload(); }}
      />

      <WorkspaceCreateFileDialog
        open={createSide !== null}
        onOpenChange={(open) => { if (!open) setCreateSide(null); }}
        parentPath={createSide ? `/${panes[createSide].path}` : "/"}
        loading={busy}
        onSubmit={async (name, extension) => {
          const side = createSide;
          if (!side) return;
          const pane = panes[side];
          const path = joinWorkspacePath(pane.path, extension ? `${name}.${extension}` : name);
          try {
            if (extension) await fileManagerApi.createFile(pane.root, path);
            else await fileManagerApi.mkdir(pane.root, path);
            setCreateSide(null);
            reload();
            if (extension) openEditor(pane.root, path);
          } catch (error) {
            reportError(error, t("failed"));
          }
        }}
      />

      <Dialog open={editorTarget !== null} onOpenChange={(open) => { if (!open) closeEditor(); }}>
        <DialogContent className="max-h-[90svh] sm:max-w-4xl">
          <DialogHeader><DialogTitle className="break-all">{editorTarget?.path}</DialogTitle></DialogHeader>
          {notice}
          <WorkspaceTextEditor
            key={`${editorTarget?.root}:${editorTarget?.path}`}
            value={session.source}
            onChange={(value) => session.change(value)}
            readOnly={!session.editable || !session.revision}
            fileName={editorTarget?.path}
            tuiqiaoKey={`file-manager:${editorTarget?.root}:${editorTarget?.path}`}
            documentLimit="device"
            className="h-[60svh]"
          />
          <DialogFooter>
            <Button variant="outline" onClick={closeEditor}>{t("cancel")}</Button>
            <Button
              disabled={!session.canSave || !session.dirty || session.status === "saving"}
              onClick={async () => { if (await session.save()) { toast.success(t("saved")); setEditorTarget(null); reload(); } }}
            >{t("save")}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={previewImage !== null} onOpenChange={(open) => { if (!open) setPreviewImage(null); }}>
        <DialogContent className="sm:max-w-4xl">
          <DialogHeader><DialogTitle className="sr-only">{t("preview")}</DialogTitle></DialogHeader>
          {previewImage ? <img src={previewImage} alt="" className="max-h-[75svh] w-full object-contain" /> : null}
        </DialogContent>
      </Dialog>
    </div>
  );
}

function FilePane({
  pane,
  roots,
  rootOptions,
  active,
  sort,
  showHidden,
  onActivate,
  onEntries,
  onNavigate,
  onToggle,
  onOpen,
  onDropFiles,
}: {
  pane: PaneState;
  roots: FileManagerRoots | undefined;
  rootOptions: { root: FileManagerRoot; label: string; device: boolean }[];
  active: boolean;
  sort: FileManagerSort;
  showHidden: boolean;
  onActivate: () => void;
  onEntries: (entries: WorkspaceFileEntry[]) => void;
  onNavigate: (root: FileManagerRoot, path: string) => void;
  onToggle: (path: string) => void;
  onOpen: (entry: WorkspaceFileEntry) => void;
  onDropFiles: (files: File[]) => void;
}) {
  const { t } = useTranslation("fileManager");
  const isDevice = pane.root.startsWith("device:");
  const deviceBlocked = isDevice && roots !== undefined && roots.deviceAccess !== "READY";
  const restricted = isRestrictedDevicePath(pane.root, pane.path);
  const list = useQuery({
    queryKey: listKey(pane.root, pane.path),
    queryFn: () => fileManagerApi.list(pane.root, pane.path),
    enabled: Boolean(pane.root) && !deviceBlocked && !restricted,
  });
  const entries = React.useMemo(() => list.data ?? [], [list.data]);
  React.useEffect(() => onEntries(entries), [entries, onEntries]);
  const shown = React.useMemo(() => visibleEntries(entries, sort, showHidden), [entries, sort, showHidden]);
  const selecting = pane.selected.size > 0;
  const rootLabel = rootOptions.find((option) => option.root === pane.root)?.label ?? "";

  return (
    <section
      className="flex min-h-0 min-w-0 flex-col"
      onPointerDown={onActivate}
      onDragOver={(event) => event.preventDefault()}
      onDrop={(event) => {
        event.preventDefault();
        event.stopPropagation();
        onDropFiles(Array.from(event.dataTransfer.files));
      }}
    >
      <header className={cn("space-y-1 border-b px-2 py-1.5", active ? "bg-secondary" : "bg-muted/40")}>
        <div className="flex items-center gap-1">
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button size="sm" variant="ghost" className="min-w-0 justify-start px-1.5 font-semibold">
                {isDevice ? <HardDrive className="size-4" /> : <Library className="size-4" />}
                <span className="truncate">{rootLabel}</span>
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent>
              {rootOptions.map((option) => (
                <DropdownMenuItem key={option.root} onSelect={() => onNavigate(option.root, "")}>
                  {option.device ? <HardDrive className="size-4" /> : <Library className="size-4" />}
                  {option.label}
                </DropdownMenuItem>
              ))}
            </DropdownMenuContent>
          </DropdownMenu>
          <Button
            size="icon"
            variant="ghost"
            className="ml-auto size-7"
            disabled={!pane.path}
            onClick={() => onNavigate(pane.root, parentWorkspacePath(pane.path))}
            aria-label={t("up")}
          >
            <ArrowUpLeft className="size-4" />
          </Button>
        </div>
        <nav className="flex items-center gap-0.5 overflow-x-auto whitespace-nowrap text-xs text-muted-foreground">
          <button type="button" className="rounded px-1 hover:bg-accent" onClick={() => onNavigate(pane.root, "")}>/</button>
          {pathCrumbs(pane.path).map((crumb, index, all) => (
            <React.Fragment key={crumb.path}>
              <button type="button" className="rounded px-1 hover:bg-accent" onClick={() => onNavigate(pane.root, crumb.path)}>{crumb.label}</button>
              {index < all.length - 1 ? <span>/</span> : null}
            </React.Fragment>
          ))}
        </nav>
      </header>
      <div className="min-h-0 flex-1 overflow-y-auto p-1.5">
        {deviceBlocked ? (
          <p className="p-3 text-sm text-muted-foreground">
            {t(roots?.deviceAccess === "PASSWORD_REQUIRED" ? "device_password_required" : "device_permission_required")}
          </p>
        ) : restricted ? (
          <p className="p-3 text-sm text-muted-foreground">{t("restricted")}</p>
        ) : list.isPending ? (
          <div className="flex justify-center p-6"><Spinner /></div>
        ) : list.isError ? (
          <p className="p-3 text-sm text-destructive">{list.error instanceof Error ? list.error.message : t("failed")}</p>
        ) : shown.length === 0 ? (
          <p className="p-3 text-sm text-muted-foreground">{t("empty")}</p>
        ) : (
          <ul className="space-y-0.5">
            {shown.map((entry) => {
              const selected = pane.selected.has(entry.path);
              const image = !entry.isDirectory && detectWorkspaceFileKind(entry.name) === "image";
              return (
                <li key={entry.path}>
                  <button
                    type="button"
                    className={cn(
                      "flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left hover:bg-accent",
                      selected && "bg-primary/10 hover:bg-primary/15",
                    )}
                    onClick={(event) => {
                      if (selecting || event.ctrlKey || event.metaKey) onToggle(entry.path);
                      else onOpen(entry);
                    }}
                    onContextMenu={(event) => {
                      event.preventDefault();
                      onToggle(entry.path);
                    }}
                  >
                    <span className="flex size-8 shrink-0 items-center justify-center overflow-hidden rounded-md bg-muted">
                      {selected ? (
                        <CheckCircle2 className="size-4 text-primary" />
                      ) : image ? (
                        <img src={fileManagerFileUrl(pane.root, entry.path)} alt="" className="size-full object-cover" loading="lazy" />
                      ) : entry.isDirectory ? (
                        <Folder className="size-4 text-primary" />
                      ) : (
                        <FileIcon className="size-4 text-muted-foreground" />
                      )}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm">{entry.name}</span>
                      <span className="block truncate text-xs text-muted-foreground">
                        {entry.isDirectory ? "" : `${formatWorkspaceBytes(entry.sizeBytes)} · `}
                        {new Date(entry.updatedAt).toLocaleString(undefined, { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" })}
                      </span>
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </section>
  );
}

function NameDialog({
  open,
  title,
  initial,
  onClose,
  onSubmit,
}: {
  open: boolean;
  title: string;
  initial: string;
  onClose: () => void;
  onSubmit: (name: string) => void;
}) {
  const { t } = useTranslation("fileManager");
  const [name, setName] = React.useState(initial);
  React.useEffect(() => { if (open) setName(initial); }, [open, initial]);
  const valid = name.trim() === name && name.length > 0 && !/[/\\]/.test(name) && name !== "." && name !== "..";
  return (
    <Dialog open={open} onOpenChange={(value) => { if (!value) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>{title}</DialogTitle></DialogHeader>
        <form onSubmit={(event) => { event.preventDefault(); if (valid && name !== initial) onSubmit(name); }}>
          <Input autoFocus value={name} onChange={(event) => setName(event.target.value)} />
          <DialogFooter className="mt-4">
            <Button type="button" variant="outline" onClick={onClose}>{t("cancel")}</Button>
            <Button type="submit" disabled={!valid || name === initial}>{t("confirm")}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
