import * as React from "react";

import { ChevronDown, ChevronUp, ClipboardCheck, RefreshCw } from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Badge } from "~/components/ui/badge";
import { Button } from "~/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "~/components/ui/dialog";
import { ScrollArea } from "~/components/ui/scroll-area";
import {
  type CreatorApprovalResult,
  type CreatorProposal,
  type CreatorProposalList,
  orderProposals,
  pendingProposals,
  shortPath,
  statusKey,
  summarizeApproval,
} from "~/lib/creator-proposals";
import { cn } from "~/lib/utils";
import api, { ApiError } from "~/services/api";
import { type CreatorProposalsEvent, EVENT_CREATOR_PROPOSALS, subscribeToEvent } from "~/services/events";

const COLLAPSED_DIFF_LINES = 6;

export interface CreatorProposalsButtonProps {
  conversationId: string;
  className?: string;
}

/**
 * The changes the creator assistant proposed in this chat: a button with the number waiting for you, and a dialog that shows each
 * change line by line and lets you apply or reject it. Approving quotes the digest of the diff shown here; if the proposal changed
 * since, nothing is applied and the list is reloaded.
 */
export function CreatorProposalsButton({ conversationId, className }: CreatorProposalsButtonProps) {
  const { t } = useTranslation();
  const [open, setOpen] = React.useState(false);
  const [data, setData] = React.useState<CreatorProposalList | null>(null);
  const [loading, setLoading] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const [busy, setBusy] = React.useState<string | null>(null);
  const [now, setNow] = React.useState(() => Date.now());
  const requestRef = React.useRef(0);

  const load = React.useCallback(() => {
    const request = ++requestRef.current;
    setLoading(true);
    api
      .get<CreatorProposalList>(`creator/proposals?conversationId=${encodeURIComponent(conversationId)}`)
      .then((result) => {
        if (request !== requestRef.current) return;
        setData(result);
        setError(null);
        setNow(Date.now());
      })
      .catch((loadError) => {
        if (request !== requestRef.current) return;
        setError(loadError instanceof Error ? loadError.message : t("creator_proposals.load_failed"));
      })
      .finally(() => {
        if (request === requestRef.current) setLoading(false);
      });
  }, [conversationId, t]);

  React.useEffect(() => {
    setData(null);
    load();
    return () => {
      requestRef.current += 1;
    };
  }, [load]);

  // The server tells us whenever a proposal of a creator chat is created, applied, rejected or cancelled (from here or from the app).
  React.useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | null = null;
    const stop = subscribeToEvent<CreatorProposalsEvent>(EVENT_CREATOR_PROPOSALS, (event) => {
      if (event.conversationId !== conversationId) return;
      if (timer) clearTimeout(timer);
      timer = setTimeout(load, 150);
    });
    return () => {
      stop();
      if (timer) clearTimeout(timer);
    };
  }, [conversationId, load]);

  React.useEffect(() => {
    if (open) load();
  }, [open, load]);

  React.useEffect(() => {
    if (!open) return;
    const timer = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(timer);
  }, [open]);

  const proposals = React.useMemo(() => orderProposals(data?.proposals ?? [], now), [data, now]);
  const pending = React.useMemo(() => pendingProposals(data?.proposals ?? [], now), [data, now]);
  const gateClosed = data ? !data.writeGateOpen : false;

  const approve = React.useCallback(
    async (proposal: CreatorProposal) => {
      setBusy(proposal.id);
      try {
        await api.post<CreatorProposal>(`creator/proposals/${encodeURIComponent(proposal.id)}/approve`, { digest: proposal.digest });
        toast.success(t("creator_proposals.approved_one"));
      } catch (approveError) {
        if (approveError instanceof ApiError && approveError.errorCode === "APPROVAL_DIGEST_MISMATCH") {
          toast.error(t("creator_proposals.changed"));
        } else {
          toast.error(approveError instanceof Error && approveError.message ? approveError.message : t("creator_proposals.approve_failed"));
        }
      } finally {
        setBusy(null);
        load();
      }
    },
    [load, t],
  );

  const approveAll = React.useCallback(async () => {
    setBusy("all");
    try {
      const response = await api.post<{ results: CreatorApprovalResult[] }>("creator/proposals/approve-all", { conversationId });
      const summary = summarizeApproval(response.results);
      if (summary.failed === 0) toast.success(t("creator_proposals.approved_many", { applied: summary.applied, failed: 0 }));
      else toast.error(summary.firstError ?? t("creator_proposals.approved_many", { applied: summary.applied, failed: summary.failed }));
    } catch (approveError) {
      toast.error(approveError instanceof Error && approveError.message ? approveError.message : t("creator_proposals.approve_failed"));
    } finally {
      setBusy(null);
      load();
    }
  }, [conversationId, load, t]);

  const reject = React.useCallback(
    async (proposal: CreatorProposal) => {
      setBusy(proposal.id);
      try {
        await api.post<CreatorProposal>(`creator/proposals/${encodeURIComponent(proposal.id)}/reject`, {});
      } catch {
        toast.error(t("creator_proposals.reject_failed"));
      } finally {
        setBusy(null);
        load();
      }
    },
    [load, t],
  );

  return (
    <>
      <Button
        type="button"
        variant={pending.length > 0 ? "default" : "ghost"}
        size="sm"
        className={cn("gap-1.5", className)}
        onClick={() => setOpen(true)}
        aria-label={t("creator_proposals.open")}
      >
        <ClipboardCheck className="size-4" />
        <span>{pending.length > 0 ? t("creator_proposals.pending_count", { count: pending.length }) : t("creator_proposals.title")}</span>
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-h-[85svh] max-w-2xl overflow-hidden p-0">
          <div className="flex items-center gap-2 border-b px-4 py-3 pr-12">
            <ClipboardCheck className="size-4 shrink-0 text-muted-foreground" />
            <DialogTitle className="flex-1 text-base">{t("creator_proposals.title")}</DialogTitle>
            <Button type="button" variant="ghost" size="icon-sm" aria-label={t("creator_proposals.refresh")} onClick={load} disabled={loading}>
              <RefreshCw className={cn("size-4", loading && "animate-spin")} />
            </Button>
          </div>
          <DialogDescription className="sr-only">{t("creator_proposals.open")}</DialogDescription>
          {gateClosed ? <p className="border-b bg-muted/50 px-4 py-2 text-sm">{t("creator_proposals.write_gate")}</p> : null}
          {error ? <p className="border-b px-4 py-2 text-sm text-destructive">{error}</p> : null}
          {pending.length > 1 ? (
            <div className="flex justify-end border-b px-4 py-2">
              <Button type="button" size="sm" onClick={() => void approveAll()} disabled={busy !== null || gateClosed}>
                {busy === "all" ? t("creator_proposals.approving") : t("creator_proposals.approve_all", { count: pending.length })}
              </Button>
            </div>
          ) : null}
          <ScrollArea className="max-h-[65svh]">
            <div className="flex flex-col gap-3 p-4">
              {proposals.length === 0 && !loading ? (
                <p className="py-8 text-center text-sm text-muted-foreground">{t("creator_proposals.empty")}</p>
              ) : null}
              {proposals.map((proposal) => (
                <ProposalCard
                  key={proposal.id}
                  proposal={proposal}
                  now={now}
                  busy={busy}
                  gateClosed={gateClosed}
                  onApprove={() => void approve(proposal)}
                  onReject={() => void reject(proposal)}
                />
              ))}
            </div>
          </ScrollArea>
        </DialogContent>
      </Dialog>
    </>
  );
}

interface ProposalCardProps {
  proposal: CreatorProposal;
  now: number;
  busy: string | null;
  gateClosed: boolean;
  onApprove: () => void;
  onReject: () => void;
}

function ProposalCard({ proposal, now, busy, gateClosed, onApprove, onReject }: ProposalCardProps) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = React.useState(false);
  const status = statusKey(proposal, now);
  const isPending = status === "pending_approval";
  const lines = expanded ? proposal.diff : proposal.diff.slice(0, COLLAPSED_DIFF_LINES);
  const hidden = proposal.diff.length - lines.length;
  const domains = proposal.domains.map((domain) => t(`creator_proposals.domain.${domain}`, { defaultValue: domain })).join(" + ");
  const created = Object.entries(proposal.createdIds);

  return (
    <section className={cn("rounded-lg border p-3", isPending && "border-primary/50")}>
      <header className="flex flex-wrap items-center gap-2">
        <span className="text-sm font-medium">{domains}</span>
        <Badge variant={isPending ? "default" : "secondary"}>{t(`creator_proposals.status.${status}`)}</Badge>
        <Badge variant={proposal.risk === "high" ? "destructive" : "outline"}>{t(`creator_proposals.risk.${proposal.risk}`, { defaultValue: proposal.risk })}</Badge>
        {proposal.requiresEnhancedConfirm ? <span className="text-xs text-destructive">{t("creator_proposals.enhanced_confirm")}</span> : null}
      </header>
      {proposal.statusReason && !isPending ? <p className="mt-1 text-xs text-muted-foreground">{proposal.statusReason}</p> : null}
      <ul className="mt-2 flex flex-col gap-1.5 text-xs">
        {lines.map((line, index) => (
          <li key={`${line.path}-${index}`} className="rounded bg-muted/40 px-2 py-1">
            <div className="break-all font-mono text-muted-foreground">{shortPath(line.path)}</div>
            {line.secret ? (
              <div className="italic text-muted-foreground">{t("creator_proposals.secret_hidden")}</div>
            ) : (
              <DiffValues before={line.before} after={line.after} />
            )}
          </li>
        ))}
      </ul>
      {hidden > 0 || expanded ? (
        <Button type="button" variant="ghost" size="sm" className="mt-1 h-7 gap-1 px-2 text-xs" onClick={() => setExpanded((value) => !value)}>
          {expanded ? <ChevronUp className="size-3" /> : <ChevronDown className="size-3" />}
          {expanded ? t("creator_proposals.hide_diff") : t("creator_proposals.show_diff")}
        </Button>
      ) : null}
      {proposal.omittedDiffLines > 0 ? (
        <p className="mt-1 text-xs text-muted-foreground">{t("creator_proposals.omitted", { count: proposal.omittedDiffLines })}</p>
      ) : null}
      {created.length > 0 ? (
        <p className="mt-1 break-all text-xs text-muted-foreground">
          {t("creator_proposals.created_ids")}: {created.map(([label, id]) => `${label} → ${id.slice(0, 8)}`).join(", ")}
        </p>
      ) : null}
      {isPending ? (
        <footer className="mt-3 flex flex-wrap items-center justify-end gap-2">
          <span className="mr-auto text-xs text-muted-foreground">
            {t("creator_proposals.expires", { time: new Date(proposal.expiresAt).toLocaleString() })}
          </span>
          <Button type="button" variant="outline" size="sm" onClick={onReject} disabled={busy !== null}>
            {t("creator_proposals.reject")}
          </Button>
          <Button type="button" size="sm" onClick={onApprove} disabled={busy !== null || gateClosed}>
            {busy === proposal.id ? t("creator_proposals.approving") : t("creator_proposals.approve")}
          </Button>
        </footer>
      ) : null}
    </section>
  );
}

function DiffValues({ before, after }: { before?: string | null; after?: string | null }) {
  const { t } = useTranslation();
  const show = (value?: string | null) => (value == null || value === "" ? t("creator_proposals.empty_value") : value);
  return (
    <div className="grid gap-0.5">
      {before != null ? <div className="whitespace-pre-wrap break-words text-red-700 dark:text-red-400">− {show(before)}</div> : null}
      {after != null ? <div className="whitespace-pre-wrap break-words text-green-700 dark:text-green-400">+ {show(after)}</div> : null}
    </div>
  );
}
