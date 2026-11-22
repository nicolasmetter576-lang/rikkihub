import * as React from "react";

import { Eraser } from "lucide-react";
import { useTranslation } from "react-i18next";
import { useParams } from "react-router";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import { Popover, PopoverContent, PopoverTitle, PopoverTrigger } from "~/components/ui/popover";
import { Switch } from "~/components/ui/switch";
import { ApiError } from "~/services/api";
import {
  fetchQubaguConfig,
  postQubaguMessageAction,
  qubaguOnOneLine,
  qubaguRuleLabelKey,
  qubaguRuleName,
  type QubaguConfigResponse,
  type QubaguMessageAction,
  type QubaguPartRecord,
} from "@overlay/lib/qubagu";

/**
 * "Cliché cleanup" on a reply: the list of changes the server recorded on the message (see `QubaguRecord`), each with
 * a switch to undo or apply it, plus apply all / restore all / AI refine / scan again. A reply without a record gets
 * the same button so it can be scanned on demand. The message itself is updated through the conversation stream.
 */
export function QubaguButton({
  messageId,
  records,
  disabled,
  alignRight,
}: {
  messageId: string;
  records: QubaguPartRecord[];
  disabled?: boolean;
  alignRight?: boolean;
}) {
  const { t } = useTranslation("settings");
  const { id: conversationId } = useParams();
  const [open, setOpen] = React.useState(false);
  const [busy, setBusy] = React.useState<QubaguMessageAction | null>(null);
  const [scanned, setScanned] = React.useState(false);
  const [source, setSource] = React.useState<QubaguConfigResponse | null>(null);

  React.useEffect(() => {
    if (!open || source) return;
    let mounted = true;
    // 只为了显示规则名；取不到就显示统称
    fetchQubaguConfig().then(value => { if (mounted) setSource(value); }).catch(() => {});
    return () => { mounted = false; };
  }, [open, source]);

  if (!conversationId) return null;

  const total = records.reduce((sum, record) => sum + record.edits.length, 0);
  const applied = records.reduce((sum, record) => sum + record.edits.filter(edit => edit.applied).length, 0);
  const label = total === 0 ? t("qubagu.button") : applied > 0 ? t("qubagu.button_applied", { count: applied }) : t("qubagu.button_found", { count: total });

  const run = (action: QubaguMessageAction, target?: { part: number; edit: number }) => {
    setBusy(action);
    postQubaguMessageAction(conversationId, messageId, action, target)
      .then(() => { if (action === "scan") setScanned(true); })
      .catch(error => {
        const code = error instanceof ApiError ? error.errorCode : undefined;
        const known = code ? t(`qubagu.refine_error.${code}`, { defaultValue: "" }) : "";
        toast.error(known || (error instanceof Error && error.message) || t("qubagu.action_failed"));
      })
      .finally(() => setBusy(null));
  };

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button aria-label={label} title={label} disabled={disabled} size={total > 0 ? "xs" : "icon-xs"} type="button" variant="ghost" data-qubagu-button>
          <Eraser className="size-3.5" />
          {total > 0 ? <span className="text-xs tabular-nums">{applied > 0 ? applied : total}</span> : null}
        </Button>
      </PopoverTrigger>
      <PopoverContent side="top" align={alignRight ? "end" : "start"} className="w-[min(92vw,26rem)] space-y-2 p-3">
        <PopoverTitle className="text-sm">{t("qubagu.title")}</PopoverTitle>
        <p className="text-xs text-muted-foreground" data-qubagu-summary>
          {total > 0 ? t("qubagu.summary", { applied, total }) : scanned ? t("qubagu.none_found") : t("qubagu.mode_hint.REVIEW")}
        </p>
        <div className="flex flex-wrap gap-1.5">
          {total > 0 ? (
            <>
              <Button size="xs" variant="outline" disabled={busy !== null || applied === total} onClick={() => run("apply")}>
                {t("qubagu.apply_all")}
              </Button>
              <Button size="xs" variant="outline" disabled={busy !== null || applied === 0} onClick={() => run("restore")}>
                {t("qubagu.restore_all")}
              </Button>
              <Button size="xs" variant="outline" disabled={busy !== null} onClick={() => run("refine")} data-qubagu-refine>
                {busy === "refine" ? t("qubagu.refining") : t("qubagu.refine")}
              </Button>
            </>
          ) : null}
          <Button size="xs" variant="outline" disabled={busy !== null} onClick={() => run("scan")} data-qubagu-scan>
            {total > 0 ? t("qubagu.rescan") : t("qubagu.scan")}
          </Button>
        </div>
        {total > 0 ? (
          <ul className="max-h-72 space-y-2 overflow-y-auto">
            {records.flatMap(record =>
              record.edits.map((edit, index) => (
                <li key={`${record.part}-${index}`} className="flex items-center justify-between gap-3 text-sm" data-qubagu-edit>
                  <div className="min-w-0">
                    <p className="truncate text-xs text-muted-foreground">{qubaguRuleName(edit.rule, source?.config) ?? t(qubaguRuleLabelKey(edit.rule))}</p>
                    <p className="break-words">
                      <span className="text-muted-foreground line-through">{qubaguOnOneLine(edit.before)}</span>
                      {" → "}
                      <span className="text-primary">{qubaguOnOneLine(edit.after) || t("qubagu.deletes")}</span>
                    </p>
                  </div>
                  <Switch
                    checked={edit.applied}
                    disabled={busy !== null}
                    onCheckedChange={on => run(on ? "apply" : "restore", { part: record.part, edit: index })}
                  />
                </li>
              )),
            )}
          </ul>
        ) : null}
      </PopoverContent>
    </Popover>
  );
}
