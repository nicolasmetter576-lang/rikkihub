/** Shared contract for the existing FILES and direct-device creation dialogs. */
export type WorkspaceFileNameError =
  | "filename_empty"
  | "filename_whitespace"
  | "filename_dot"
  | "filename_separator"
  | "filename_control"
  | "filename_unicode"
  | "filename_reserved"
  | "filename_too_long";

function hasUnpairedSurrogate(value: string): boolean {
  // for-of combines valid pairs; a remaining surrogate is ill-formed UTF-16.
  for (const character of value) {
    const point = character.codePointAt(0)!;
    if (point >= 0xd800 && point <= 0xdfff) return true;
  }
  return false;
}

export function validateWorkspaceFileName(name: string): WorkspaceFileNameError | null {
  if (name.length === 0) return "filename_empty";
  // Checking is intentional; never trim, normalize Unicode, or rewrite a user's name.
  if (name !== name.trim()) return "filename_whitespace";
  if (name === "." || name === "..") return "filename_dot";
  if (/[\\/]/u.test(name)) return "filename_separator";
  if (/[\u0000-\u001f\u007f-\u009f]/u.test(name)) return "filename_control";
  if (hasUnpairedSurrogate(name)) return "filename_unicode";
  if (name.startsWith(".l2s.")) return "filename_reserved";
  if (new TextEncoder().encode(name).byteLength > 255) return "filename_too_long";
  return null;
}

export type WorkspaceCreationNameError = WorkspaceFileNameError
  | "extension_whitespace"
  | "extension_separator"
  | "extension_control"
  | "extension_unicode"
  | "extension_dot";

/** Empty extension explicitly selects an empty directory, not an extensionless file. */
export function validateWorkspaceCreationName(name: string, extension: string): WorkspaceCreationNameError | null {
  const invalidName = validateWorkspaceFileName(name);
  if (invalidName) return invalidName;
  if (extension === "") return null;
  if (extension !== extension.trim()) return "extension_whitespace";
  if (/[\\/]/u.test(extension)) return "extension_separator";
  if (/[\u0000-\u001f\u007f-\u009f]/u.test(extension)) return "extension_control";
  if (hasUnpairedSurrogate(extension)) return "extension_unicode";
  if (extension.startsWith(".") || extension.endsWith(".")) return "extension_dot";
  return validateWorkspaceFileName(`${name}.${extension}`);
}

export interface FileCreationSnapshot {
  readonly workspaceId: string;
  readonly area: "FILES" | "DEVICE";
  readonly parentPath: string;
  readonly baseName: string;
  readonly extension: string;
  readonly name: string;
  readonly isDirectory: boolean;
  readonly path: string;
  readonly mount?: Readonly<{ id: string; viewToken?: string }>;
}

export function snapshotFileCreation(
  workspaceId: string,
  area: FileCreationSnapshot["area"],
  parentPath: string,
  baseName: string,
  extension: string,
  mount?: { id: string; viewToken?: string },
): FileCreationSnapshot {
  const invalid = validateWorkspaceCreationName(baseName, extension);
  if (invalid) throw new Error(invalid);
  const name = extension === "" ? baseName : `${baseName}.${extension}`;
  return Object.freeze({
    workspaceId, area, parentPath, baseName, extension, name,
    isDirectory: extension === "",
    path: parentPath ? `${parentPath}/${name}` : name,
    ...(mount ? { mount: Object.freeze({ id: mount.id, viewToken: mount.viewToken }) } : {}),
  });
}

const createErrorKeys: Record<string, string> = {
  FILE_ALREADY_EXISTS: "file_already_exists",
  FILE_INVALID_PATH: "file_invalid_path",
  FILE_INVALID_NAME: "file_invalid_name",
  FILE_PARENT_MISSING: "file_parent_missing",
  FILE_PERMISSION_DENIED: "file_permission_denied",
  FILE_TOO_LARGE: "file_too_large",
  FILE_IO_ERROR: "file_io_error",
  FILE_SYMLINK_NOT_ALLOWED: "file_symlink_not_allowed",
};

/** No English-text heuristics and no untrusted/host-path-bearing server messages. */
export function fileCreationErrorKey(error: unknown): string {
  const errorCode = error && typeof error === "object" && "errorCode" in error
    ? error.errorCode : undefined;
  return typeof errorCode === "string" && Object.hasOwn(createErrorKeys, errorCode)
    ? createErrorKeys[errorCode]
    : "create_file_failed";
}

export function isUncertainFileCreationError(error: unknown): boolean {
  if (!error || typeof error !== "object") return false;
  // A gateway timeout/error can follow a successful backend write as well.
  if ("code" in error && [408, 502, 503, 504].includes(Number(error.code))) return true;
  return error instanceof TypeError || ("name" in error &&
    ["TimeoutError", "NetworkError", "AbortError"].includes(String(error.name)));
}

export type FileCreationOutcome =
  | { status: "failed"; errorKey: string }
  | { status: "uncertain"; present: boolean | null }
  | { status: "created" | "created_open_failed" | "created_not_opened"; refreshFailed: boolean };

/**
 * Exactly one creation attempt. A successful creation and a failed read are separate
 * outcomes; never roll back/delete a created file. Presence after a transport failure
 * is not proof that this request created it (another client may have won the race).
 */
export async function createFileAndOpen<T>({
  create, refresh, open,
}: {
  create: () => Promise<T>;
  refresh: () => Promise<boolean>;
  // false means the user left this view; don't open in a different workspace/directory.
  open?: (entry: T) => Promise<boolean | void>;
}): Promise<FileCreationOutcome> {
  let entry: T;
  try {
    entry = await create();
  } catch (error) {
    if (!isUncertainFileCreationError(error)) {
      return { status: "failed", errorKey: fileCreationErrorKey(error) };
    }
    let present: boolean | null = null;
    try { present = await refresh(); } catch { /* Can't safely establish the result. */ }
    return { status: "uncertain", present };
  }
  let refreshFailed = false;
  try { await refresh(); } catch { refreshFailed = true; }
  try {
    // Directory creation refreshes the list but has no editor/open phase.
    const opened = await open?.(entry);
    return { status: opened === false ? "created_not_opened" : "created", refreshFailed };
  } catch {
    return { status: "created_open_failed", refreshFailed };
  }
}

/** Synchronous duplicate guard, including two submit events before React rerenders. */
export function createFileSubmissionGate() {
  let pending = false;
  return async (submit: () => Promise<void>): Promise<void> => {
    if (pending) return;
    pending = true;
    try { await submit(); } finally { pending = false; }
  };
}
