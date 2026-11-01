import * as React from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { Button } from "~/components/ui/button";
import { Input } from "~/components/ui/input";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "~/components/ui/dialog";
import {
  createFileSubmissionGate, validateWorkspaceCreationName,
  type FileCreationOutcome,
} from "@overlay/lib/workspace-file-create";

/** Existing creation UI, shared by the FILES browser, chat panel and device browser. */
export function WorkspaceCreateFileDialog({
  open, onOpenChange, parentPath, loading, onSubmit, initialExtension = "md",
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  parentPath: string;
  loading: boolean;
  onSubmit: (name: string, extension: string) => Promise<void>;
  initialExtension?: string;
}) {
  const { t } = useTranslation("workspace");
  const [name, setName] = React.useState("untitled");
  const [extension, setExtension] = React.useState(initialExtension);
  const gate = React.useRef(createFileSubmissionGate()).current;
  const errorId = React.useId();
  const helpId = React.useId();
  const invalid = validateWorkspaceCreationName(name, extension);
  React.useEffect(() => {
    if (open) { setName("untitled"); setExtension(initialExtension); }
  }, [open, initialExtension]);

  return (
    <Dialog open={open} onOpenChange={(next) => { if (!loading) onOpenChange(next); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("files.creation_title")}</DialogTitle>
          <DialogDescription>{t("files.create_in_directory", { path: `/${parentPath}` })}</DialogDescription>
        </DialogHeader>
        <form className="space-y-4" onSubmit={(event) => {
          event.preventDefault();
          if (loading || invalid) return;
          void gate(() => onSubmit(name, extension));
        }}>
          <label className="block space-y-1.5 text-sm">
            <span>{t("files.filename_label")}</span>
            <Input
              value={name}
              onChange={(event) => setName(event.target.value)}
              aria-invalid={invalid?.startsWith("filename_") ?? false}
              aria-describedby={invalid ? errorId : undefined}
              disabled={loading}
              autoFocus
            />
          </label>
          <label className="block space-y-1.5 text-sm">
            <span>{t("files.extension_label")}</span>
            <Input
              value={extension}
              onChange={(event) => setExtension(event.target.value)}
              aria-invalid={invalid?.startsWith("extension_") ?? false}
              aria-describedby={invalid ? `${helpId} ${errorId}` : helpId}
              disabled={loading}
            />
          </label>
          <p id={helpId} className="text-xs text-muted-foreground">{t("files.extension_help")}</p>
          {invalid ? <p id={errorId} role="alert" className="text-xs text-destructive">{t(`files.${invalid}`)}</p> : null}
          <DialogFooter>
            <Button type="button" variant="outline" disabled={loading} onClick={() => onOpenChange(false)}>
              {t("files.cancel")}
            </Button>
            <Button type="submit" disabled={loading || invalid != null}>
              {loading ? t("files.creating_entry") : t(extension === "" ? "files.new_folder" : "files.new_file")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

export function reportFileCreationOutcome(
  outcome: FileCreationOutcome,
  translate: (key: string) => string,
  isDirectory = false,
) {
  if (outcome.status === "failed") {
    toast.error(translate(`files.${outcome.errorKey}`));
  } else if (outcome.status === "uncertain") {
    const key = outcome.present === true ? "create_result_present"
      : outcome.present === false ? "create_result_absent" : "create_result_unknown";
    toast.warning(translate(`files.${key}`));
  } else if (outcome.status === "created_open_failed") {
    toast.warning(translate("files.file_created_open_failed"));
  } else if (outcome.status === "created_not_opened") {
    toast.message(translate("files.file_created_not_opened"));
  } else if (outcome.refreshFailed) {
    toast.warning(translate(isDirectory ? "files.folder_created_refresh_failed" : "files.file_created_refresh_failed"));
  } else {
    toast.success(translate(isDirectory ? "files.folder_created" : "files.file_created"));
  }
}
