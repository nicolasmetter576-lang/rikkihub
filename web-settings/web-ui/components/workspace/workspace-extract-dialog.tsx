import * as React from "react";

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
import { Input } from "~/components/ui/input";
import { Spinner } from "~/components/ui/spinner";
import { SettingsSelectField } from "@overlay/components/settings/settings-ui";
import {
  WORKSPACE_EXTRACT_ENCODINGS,
  workspaceApi,
  workspaceExtractErrorKey,
} from "@overlay/lib/workspace";
import type { WorkspaceExtractResult, WorkspaceFileEntry } from "@overlay/types/workspace";

const AUTO = "auto";

/**
 * Extract an archive next to itself. Password and file-name encoding are optional;
 * a missing/wrong password or other archive error is shown in place so the user can retry.
 */
export function WorkspaceExtractDialog({
  workspaceId,
  entry,
  onOpenChange,
  onExtracted,
}: {
  workspaceId: string;
  entry: WorkspaceFileEntry | null;
  onOpenChange: (open: boolean) => void;
  onExtracted: (result: WorkspaceExtractResult) => void | Promise<void>;
}) {
  const { t } = useTranslation("workspace");
  const [password, setPassword] = React.useState("");
  const [encoding, setEncoding] = React.useState(AUTO);
  const [running, setRunning] = React.useState(false);
  const [errorKey, setErrorKey] = React.useState<string | null>(null);
  const passwordRef = React.useRef<HTMLInputElement>(null);

  React.useEffect(() => {
    if (!entry) return;
    setPassword("");
    setEncoding(AUTO);
    setErrorKey(null);
  }, [entry]);

  const passwordProblem = errorKey === "extract.password_required" || errorKey === "extract.wrong_password";

  const submit = async () => {
    if (!entry || running) return;
    setRunning(true);
    setErrorKey(null);
    try {
      const result = await workspaceApi.extract(
        workspaceId,
        entry.path,
        password || null,
        encoding === AUTO ? null : encoding,
      );
      toast.success(
        result.skipped > 0
          ? t("extract.done_skipped", { name: result.name, count: result.skipped })
          : t("extract.done", { name: result.name }),
      );
      onOpenChange(false);
      await onExtracted(result);
    } catch (error) {
      const key = workspaceExtractErrorKey(error);
      setErrorKey(key);
      if (key === "extract.password_required" || key === "extract.wrong_password") {
        window.setTimeout(() => passwordRef.current?.focus(), 0);
      }
    } finally {
      setRunning(false);
    }
  };

  return (
    <Dialog
      open={entry != null}
      onOpenChange={(open) => {
        if (!running) onOpenChange(open);
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle className="break-all">{t("extract.title", { name: entry?.name ?? "" })}</DialogTitle>
          <DialogDescription>{t("extract.desc")}</DialogDescription>
        </DialogHeader>
        <form
          className="space-y-3"
          onSubmit={(event) => {
            event.preventDefault();
            void submit();
          }}
        >
          <div className="space-y-1.5">
            <label className="text-sm font-medium" htmlFor="workspace-extract-password">
              {t("extract.password")}
            </label>
            <Input
              id="workspace-extract-password"
              ref={passwordRef}
              type="password"
              autoComplete="off"
              value={password}
              disabled={running}
              aria-invalid={passwordProblem}
              placeholder={t("extract.password_placeholder")}
              onChange={(event) => setPassword(event.target.value)}
            />
          </div>
          <SettingsSelectField
            label={t("extract.encoding")}
            value={encoding}
            onValueChange={setEncoding}
            options={WORKSPACE_EXTRACT_ENCODINGS.map((option) => ({
              value: option ?? AUTO,
              label: option ?? t("extract.encoding_auto"),
            }))}
          />
          {running ? (
            <p className="flex items-center gap-2 text-sm text-muted-foreground" data-testid="extract-running">
              <Spinner className="size-4" />
              {t("extract.running")}
            </p>
          ) : errorKey ? (
            <p className="text-sm text-destructive" role="alert" data-testid="extract-error">
              {t(errorKey)}
            </p>
          ) : null}
          <DialogFooter>
            <Button type="button" variant="outline" disabled={running} onClick={() => onOpenChange(false)}>
              {t("files.cancel")}
            </Button>
            <Button type="submit" disabled={running}>
              {t("extract.submit")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
