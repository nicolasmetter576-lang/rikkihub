const editorErrors: Record<string, string> = {
  FILE_REVISION_CONFLICT: "editor_state.conflict",
  FILE_NOT_UTF8: "editor_state.not_utf8",
  FILE_NOT_TEXT: "editor_state.not_text",
  FILE_ATOMIC_SAVE_UNSUPPORTED: "editor_state.atomic_unsupported",
  FILE_TOO_LARGE: "editor_state.too_large",
  FILE_PERMISSION_DENIED: "editor_state.permission_denied",
};
/** Error-code-only localization; never show payload strings or infer from English text. */
export function workspaceEditorErrorKey(error: unknown, fallback: string): string {
  const code = error && typeof error === "object" && "errorCode" in error ? error.errorCode : undefined;
  return typeof code === "string" && Object.hasOwn(editorErrors, code) ? editorErrors[code] : fallback;
}
