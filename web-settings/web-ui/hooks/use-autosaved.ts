import * as React from "react";

import { diffObjects } from "../lib/tavern";

/**
 * Edits an object that the server owns and saves every change at once, as a patch of just the changed fields
 * (the server merges it into the latest object, so another end's edits to other fields survive).
 *
 * - Saves go out one after another, so a slow reply cannot overwrite a newer edit.
 * - While saves are in flight the edited value is shown; when the last one has been answered the server's
 *   merged object replaces it. Updates from elsewhere ([remote] changing) are adopted while idle.
 * - A failed save reports the error and puts the last server value back.
 */
export function useAutosaved<T extends object>(
  remote: T,
  save: (patch: Record<string, unknown>) => Promise<T>,
  onError: (error: unknown) => void,
): { value: T; change: (transform: (value: T) => T) => void; saving: boolean } {
  const [value, setValue] = React.useState(remote);
  const [saving, setSaving] = React.useState(false);
  const latest = React.useRef(value);
  const remoteRef = React.useRef(remote);
  const inflight = React.useRef(0);
  const chain = React.useRef<Promise<unknown>>(Promise.resolve());
  const saveRef = React.useRef(save);
  saveRef.current = save;
  const errorRef = React.useRef(onError);
  errorRef.current = onError;

  React.useEffect(() => {
    remoteRef.current = remote;
    if (inflight.current === 0) {
      latest.current = remote;
      setValue(remote);
    }
  }, [remote]);

  const change = React.useCallback((transform: (current: T) => T) => {
    const before = latest.current;
    const after = transform(before);
    const patch = diffObjects(before, after);
    if (Object.keys(patch).length === 0) return;
    latest.current = after;
    setValue(after);
    inflight.current += 1;
    setSaving(true);
    chain.current = chain.current
      .then(() => saveRef.current(patch))
      .then(
        (saved) => {
          inflight.current -= 1;
          if (inflight.current === 0) {
            latest.current = saved;
            setValue(saved);
            setSaving(false);
          }
        },
        (error: unknown) => {
          inflight.current -= 1;
          errorRef.current(error);
          if (inflight.current === 0) {
            latest.current = remoteRef.current;
            setValue(remoteRef.current);
            setSaving(false);
          }
        },
      );
  }, []);

  return { value, change, saving };
}
