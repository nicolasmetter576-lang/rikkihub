import * as React from "react";

import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { Button } from "~/components/ui/button";
import { Input } from "~/components/ui/input";
import api from "~/services/api";
import { reportSettingsError } from "@overlay/components/settings/settings-ui";

interface LimitState {
  limit: number | null;
  blocked: boolean;
  spent: number;
}

interface LimitResponse {
  limit?: { limit: number; since: number; blocked?: boolean } | null;
  spent?: number;
}

function toState(raw: LimitResponse): LimitState {
  return { limit: raw.limit?.limit ?? null, blocked: raw.limit?.blocked ?? false, spent: raw.spent ?? 0 };
}

function usd(value: number): string {
  return `$${Number(value.toFixed(2))}`;
}

/** Per-provider spending limit: reaching it stops the provider's models until the user lifts it here. */
export function ProviderSpendLimit({ providerId }: { providerId: string }) {
  const { t } = useTranslation("settings");
  const path = `stats/provider-limits/${providerId}`;
  const [state, setState] = React.useState<LimitState | null>(null);
  const [input, setInput] = React.useState("");
  const [busy, setBusy] = React.useState(false);

  React.useEffect(() => {
    const controller = new AbortController();
    const load = () =>
      api
        .get<LimitResponse>(path, { signal: controller.signal })
        .then((raw) => {
          const next = toState(raw);
          setState(next);
          setInput((current) => (current === "" && next.limit !== null ? String(next.limit) : current));
        })
        .catch(() => undefined);
    void load();
    const timer = window.setInterval(() => {
      if (document.visibilityState === "visible") void load();
    }, 10_000);
    return () => {
      window.clearInterval(timer);
      controller.abort();
    };
  }, [path]);

  const run = async (request: () => Promise<LimitResponse>) => {
    setBusy(true);
    try {
      setState(toState(await request()));
      toast.success(t("common.saved"));
    } catch (error) {
      reportSettingsError(error, t("providers.spend_limit.save_failed"));
    } finally {
      setBusy(false);
    }
  };

  const value = Number(input);
  const valid = input.trim() !== "" && Number.isFinite(value) && value > 0 && value <= 1_000_000;

  return (
    <section className="space-y-2 rounded-xl border bg-card p-3" data-testid="provider-spend-limit">
      <div className="text-sm font-medium">{t("providers.spend_limit.title")}</div>
      <p className="text-xs text-muted-foreground">{t("providers.spend_limit.desc")}</p>
      <div className={state?.blocked ? "text-sm text-destructive" : "text-sm"}>
        {state === null || state.limit === null
          ? t("providers.spend_limit.none")
          : state.blocked
            ? t("providers.spend_limit.blocked", { limit: usd(state.limit) })
            : t("providers.spend_limit.status", { spent: usd(state.spent), limit: usd(state.limit) })}
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <Input
          className="w-40"
          inputMode="decimal"
          placeholder={t("providers.spend_limit.input")}
          value={input}
          onChange={(event) => setInput(event.target.value)}
          aria-invalid={input.trim() !== "" && !valid}
        />
        <Button
          type="button"
          size="sm"
          disabled={!valid || busy}
          onClick={() => void run(() => api.put<LimitResponse>(path, { limit: value }))}
        >
          {t("common.save")}
        </Button>
        {state?.blocked ? (
          <Button
            type="button"
            size="sm"
            variant="secondary"
            disabled={busy}
            onClick={() => void run(() => api.put<LimitResponse>(path, { lift: true }))}
          >
            {t("providers.spend_limit.lift")}
          </Button>
        ) : null}
        {state?.limit != null ? (
          <Button
            type="button"
            size="sm"
            variant="ghost"
            disabled={busy}
            onClick={() =>
              void run(async () => {
                const result = await api.delete<LimitResponse>(path);
                setInput("");
                return result;
              })
            }
          >
            {t("providers.spend_limit.remove")}
          </Button>
        ) : null}
      </div>
    </section>
  );
}
