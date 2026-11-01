import * as React from "react";
import { useBlocker, UNSAFE_DataRouterContext } from "react-router";
import { useTranslation } from "react-i18next";
import { Button } from "~/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from "~/components/ui/dialog";
import { WorkspaceEditorSession, type EditorWriter } from "@overlay/lib/workspace-editor-session";

type LeaveRequest = { action: () => void; cancel?: () => void };
type LeaveHandler = (action: () => void, cancel?: () => void) => void;
const exitHandlers = new Set<LeaveHandler>();
/** Parents use this before hiding/unmounting editor-bearing panels. */
export function requestWorkspaceEditorLeave(action: () => void) {
  const handlers = [...exitHandlers];
  const next = (index: number) => index === handlers.length ? action() : handlers[index](() => next(index + 1));
  next(0);
}
export type WorkspaceEditorGuardEntry = { session: WorkspaceEditorSession; requestLeave: LeaveHandler };
/**
 * Blocks in-app navigation while any of [entries] has unsaved/saving text, asking each in turn.
 * A router supports one blocker at a time: several editors on one page (the two panes) share one guard.
 */
export function WorkspaceEditorRouterGuard({ entries }: { entries: WorkspaceEditorGuardEntry[] }) {
  return React.useContext(UNSAFE_DataRouterContext) ? <RouterGuardInner entries={entries} /> : null;
}
function RouterGuardInner({ entries }: { entries: WorkspaceEditorGuardEntry[] }) {
  const busy = (entry: WorkspaceEditorGuardEntry) => entry.session.dirty || entry.session.status === "saving";
  const entriesRef = React.useRef(entries);
  entriesRef.current = entries;
  const blocker = useBlocker(() => entriesRef.current.some(busy));
  const blocked = blocker.state === "blocked";
  const blockerRef = React.useRef(blocker);
  blockerRef.current = blocker;
  React.useEffect(() => {
    if (!blocked) return;
    const list = entriesRef.current;
    const proceed = () => { const current = blockerRef.current; if (current.state === "blocked") current.proceed(); };
    const reset = () => { const current = blockerRef.current; if (current.state === "blocked") current.reset(); };
    const next = (index: number) => index === list.length ? proceed() : list[index].requestLeave(() => next(index + 1), reset);
    next(0);
  }, [blocked]);
  return null;
}
function RouterGuard({ session, requestLeave }: WorkspaceEditorGuardEntry) {
  return <RouterGuardInner entries={[{ session, requestLeave }]} />;
}

/** [routerGuard] false = the caller renders one [WorkspaceEditorRouterGuard] for several sessions. */
export function useWorkspaceEditorSession(writer: EditorWriter, autoSave = false, routerGuard = true) {
  const { t } = useTranslation("workspace");
  const [session] = React.useState(() => new WorkspaceEditorSession(writer));
  session.writer = writer;
  const [, render] = React.useReducer(n => n + 1, 0);
  React.useEffect(() => session.subscribe(render), [session]);
  const dataRouter = React.useContext(UNSAFE_DataRouterContext);
  const [leave, setLeave] = React.useState<LeaveRequest | null>(null);
  const requestLeave = React.useCallback((action: () => void, cancel?: () => void) => {
    if (session.dirty || session.status === "saving") setLeave({ action, cancel });
    else action();
  }, [session]);
  React.useEffect(() => { exitHandlers.add(requestLeave); return () => { exitHandlers.delete(requestLeave); }; }, [requestLeave]);
  React.useEffect(() => {
    if (!autoSave || !session.dirty || !session.canAutoSave || leave) return;
    const timer = setTimeout(() => void session.save(), 800);
    return () => clearTimeout(timer);
  }, [autoSave, session, session.source, session.status, leave]);
  React.useEffect(() => {
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (session.dirty || session.status === "saving") { event.preventDefault(); event.returnValue = ""; }
    };
    // No unmount flush. Only pagehide may make a best-effort CAS write; keep dirty until
    // a known response arrives. Never retry conflict/unknown/rejected background writes.
    const pagehide = () => { if (autoSave && session.dirty && session.canAutoSave) void session.save(true); };
    window.addEventListener("beforeunload", beforeUnload);
    window.addEventListener("pagehide", pagehide);
    return () => { window.removeEventListener("beforeunload", beforeUnload); window.removeEventListener("pagehide", pagehide); };
  }, [session, autoSave]);
  const finishLeave = () => { const action = leave?.action; setLeave(null); action?.(); };
  const cancelLeave = () => { leave?.cancel?.(); setLeave(null); };
  const notice = session.status === "idle" ? null : (
    <p role="status" className={session.canSave ? "px-3 py-1 text-xs text-muted-foreground" : "px-3 py-1 text-xs text-destructive"}>
      {t(session.status === "error" && session.errorKey ? session.errorKey : `editor_state.${session.status}`)}
    </p>
  );
  const guard = <>
    {dataRouter && routerGuard ? <RouterGuard session={session} requestLeave={requestLeave} /> : null}
    <Dialog open={leave !== null} onOpenChange={open => { if (!open) cancelLeave(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>{t("editor_state.leave_title")}</DialogTitle><DialogDescription>{t("editor_state.leave_desc")}</DialogDescription></DialogHeader>
        <DialogFooter>
          <Button variant="outline" onClick={cancelLeave}>{t("files.cancel")}</Button>
          <Button variant="destructive" disabled={session.status === "saving"} onClick={() => { session.discard(); finishLeave(); }}>{t("editor_state.discard")}</Button>
          <Button disabled={!session.canSave || session.status === "saving"} onClick={async () => { if (await session.save()) finishLeave(); }}>{t("files.save")}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </>;
  return { session, requestLeave, notice, guard };
}
