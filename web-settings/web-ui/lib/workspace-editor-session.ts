import { workspaceEditorErrorKey } from "./workspace-editor-errors";
export type EditorSaveStatus = "idle" | "unsaved" | "saving" | "saved" | "conflict" | "unknown" | "error" | "missingRevision";
export interface EditorRead { path: string; content: string; revision?: string; editable: boolean }
export interface EditorWrite { revision?: string }
export type EditorWriter = (path: string, source: string, revision: string, keepalive: boolean) => Promise<EditorWrite>;
export function validFileRevision(value: unknown): value is string { return typeof value === "string" && /^[a-f0-9]{64}$/.test(value); }

/** One source and one confirmed raw-byte baseline. No optimistic rebase, blind retry,
 * or interpretation of English error messages. A late response cannot touch a new file. */
export class WorkspaceEditorSession {
  path = "";
  source = "";
  original = "";
  revision: string | undefined;
  editable = false;
  status: EditorSaveStatus = "idle";
  savedAt: Date | null = null;
  errorKey: string | null = null;
  private generation = 0;
  private inFlight: Promise<boolean> | null = null;
  private listeners = new Set<() => void>();
  constructor(public writer: EditorWriter) {}
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };
  private notify() { for (const listener of this.listeners) listener(); }
  get dirty() { return this.source !== this.original || this.status === "unknown"; }
  get canSave() { return this.editable && validFileRevision(this.revision) && !["conflict", "unknown", "missingRevision"].includes(this.status); }
  get canAutoSave() { return this.canSave && ["unsaved", "saved"].includes(this.status); }
  load(read: EditorRead) {
    if (this.dirty || this.status === "saving") return false;
    ++this.generation;
    this.path = read.path; this.source = this.original = read.content;
    this.revision = validFileRevision(read.revision) ? read.revision : undefined;
    this.editable = read.editable;
    this.savedAt = null; this.errorKey = null;
    this.status = read.editable && !this.revision ? "missingRevision" : "idle";
    this.notify(); return true;
  }
  change(source: string) {
    if (!this.editable || !this.revision || this.source === source) return;
    this.source = source;
    if (!["conflict", "unknown", "error", "saving"].includes(this.status)) this.status = this.dirty ? "unsaved" : "idle";
    this.notify();
  }
  discard() {
    ++this.generation;
    this.source = this.original; this.status = "idle"; this.inFlight = null;
    this.notify();
  }
  async save(keepalive = false): Promise<boolean> {
    if (this.inFlight) return this.inFlight;
    if (!this.canSave) return false;
    if (!this.dirty) return true; // no-op saves do not re-encode original bytes
    const { path, source, revision, generation } = this;
    this.status = "saving"; this.errorKey = null; this.notify();
    const request = (async () => {
      try {
        await Promise.resolve(); // inFlight must be assigned even when a writer throws synchronously
        const result = await this.writer(path, source, revision!, keepalive);
        if (generation !== this.generation) return false;
        if (!validFileRevision(result.revision)) { this.status = "unknown"; return false; }
        this.original = source; this.revision = result.revision; this.savedAt = new Date();
        this.status = this.dirty ? "unsaved" : "saved";
        return !this.dirty;
      } catch (error) {
        if (generation !== this.generation) return false;
        this.errorKey = workspaceEditorErrorKey(error, "editor_state.error");
        const failure = error as { code?: number; errorCode?: string };
        this.status = failure.errorCode === "FILE_REVISION_CONFLICT" ? "conflict"
          : typeof failure.code === "number" && failure.code >= 400 && failure.code < 500 ? "error" : "unknown";
        return false;
      } finally {
        if (generation === this.generation) { this.inFlight = null; this.notify(); }
      }
    })();
    this.inFlight = request;
    return request;
  }
}
