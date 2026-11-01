import * as React from "react";

import { FitAddon } from "@xterm/addon-fit";
import { Terminal } from "@xterm/xterm";
import "@xterm/xterm/css/xterm.css";
import { useTranslation } from "react-i18next";

import { Button } from "~/components/ui/button";
import { reportSettingsError } from "@overlay/components/settings/settings-ui";
import { openWorkspaceTerminalSocket, workspaceApi } from "@overlay/lib/workspace";

const encoder = new TextEncoder();

export function WorkspaceTerminal({
  workspaceId,
  ready,
  notReadyAction,
}: {
  workspaceId: string;
  ready: boolean;
  /** Shown under the "no rootfs yet" notice (the Web install button). */
  notReadyAction?: React.ReactNode;
}) {
  const { t } = useTranslation("workspace");
  const hostRef = React.useRef<HTMLDivElement>(null);
  const tRef = React.useRef(t);
  const [status, setStatus] = React.useState<"idle" | "connecting" | "open" | "closed">("idle");
  const [nonce, setNonce] = React.useState(0);

  React.useLayoutEffect(() => {
    tRef.current = t;
  }, [t]);

  React.useLayoutEffect(() => {
    if (!ready) return;
    const host = hostRef.current;
    if (!host) return;

    const terminal = new Terminal({
      cursorBlink: true,
      convertEol: true,
      fontFamily: "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace",
      fontSize: 13,
      theme: {
        background: "#09090b",
        foreground: "#fafafa",
      },
    });
    const fit = new FitAddon();
    terminal.loadAddon(fit);
    terminal.open(host);
    fit.fit();

    let cancelled = false;
    let socket: WebSocket | null = null;

    const connect = async () => {
      setStatus("connecting");
      try {
        const dims = fit.proposeDimensions() ?? { cols: 80, rows: 24 };
        const session = await workspaceApi.createTerminal(
          workspaceId,
          Math.max(dims.cols, 40),
          Math.max(dims.rows, 12),
        );
        if (cancelled) return;
        // Single-use ?ticket= minted just-in-time — the only URL credential left.
        socket = await openWorkspaceTerminalSocket(workspaceId, session.id);
        socket.binaryType = "arraybuffer";
        socket.onopen = () => {
          setStatus("open");
          socket?.send(JSON.stringify({ type: "resize", cols: terminal.cols, rows: terminal.rows }));
        };
        socket.onmessage = (event) => {
          if (typeof event.data === "string") {
            terminal.write(event.data);
            return;
          }
          if (event.data instanceof ArrayBuffer) {
            terminal.write(new Uint8Array(event.data));
          }
        };
        socket.onclose = () => setStatus("closed");
        socket.onerror = () => setStatus("closed");
        terminal.onData((data) => {
          if (socket?.readyState === WebSocket.OPEN) {
            socket.send(encoder.encode(data));
          }
        });
        terminal.onResize(({ cols, rows }) => {
          if (socket?.readyState === WebSocket.OPEN) {
            socket.send(JSON.stringify({ type: "resize", cols, rows }));
          }
        });
      } catch (error) {
        if (!cancelled) {
          setStatus("closed");
          reportSettingsError(error, tRef.current("terminal.connect_failed"));
        }
      }
    };

    void connect();
    const onResize = () => fit.fit();
    window.addEventListener("resize", onResize);
    const observer = new ResizeObserver(onResize);
    observer.observe(host);

    return () => {
      cancelled = true;
      window.removeEventListener("resize", onResize);
      observer.disconnect();
      socket?.close();
      terminal.dispose();
    };
  }, [nonce, ready, workspaceId]);

  if (!ready) {
    return (
      <div className="space-y-3 rounded-xl border border-dashed px-4 py-16 text-center text-sm text-muted-foreground">
        <p>{t("terminal.not_ready")}</p>
        {notReadyAction ? <div className="flex justify-center">{notReadyAction}</div> : null}
      </div>
    );
  }

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between gap-3">
        <p className="text-sm text-muted-foreground">
          {status === "connecting"
            ? t("terminal.connecting")
            : status === "closed"
              ? t("terminal.disconnected")
              : t("terminal.title")}
        </p>
        {status === "closed" ? (
          <Button type="button" size="sm" variant="outline" onClick={() => setNonce((value) => value + 1)}>
            {t("terminal.reconnect")}
          </Button>
        ) : null}
      </div>
      <div
        ref={hostRef}
        className="h-[min(70svh,640px)] overflow-hidden rounded-xl bg-zinc-950 p-2"
      />
    </div>
  );
}
