import { NavLink, Outlet, Link } from "react-router";
import { ArrowLeft } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "~/components/ui/button";
import { cn } from "~/lib/utils";

const NAV_ITEMS = [
  { to: "/settings/assistants", key: "nav.assistants" },
  { to: "/settings/providers", key: "nav.providers" },
  { to: "/settings/extensions", key: "nav.extensions" },
  { to: "/settings/skills", key: "nav.skills" },
  { to: "/settings/subagent-presets", key: "nav.subagent_presets" },
  { to: "/settings/cache-keep-alive", key: "nav.cache_keep_alive" },
  { to: "/settings/tool-execution", key: "nav.tool_execution" },
  { to: "/settings/tuiqiao", key: "nav.tuiqiao" },
  { to: "/settings/zaizao", key: "nav.zaizao" },
  { to: "/settings/prompt-polish", key: "nav.prompt_polish" },
] as const;

export default function SettingsLayout() {
  const { t } = useTranslation("settings");

  return (
    <div className="flex min-h-svh flex-col bg-background">
      <header className="sticky top-0 z-20 border-b bg-background/90 backdrop-blur">
        <div className="mx-auto flex w-full max-w-5xl items-center gap-3 px-4 py-3">
          <Button variant="ghost" size="sm" asChild>
            <Link to="/">
              <ArrowLeft className="size-4" />
              {t("nav.back_to_chat")}
            </Link>
          </Button>
          <h1 className="text-sm font-semibold">{t("nav.title")}</h1>
        </div>
        <nav className="mx-auto flex w-full max-w-5xl gap-1 overflow-x-auto px-4 pb-3">
          {NAV_ITEMS.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                cn(
                  "rounded-full px-3 py-1 text-sm whitespace-nowrap transition",
                  isActive
                    ? "bg-primary text-primary-foreground"
                    : "text-muted-foreground hover:bg-accent hover:text-foreground",
                )
              }
            >
              {t(item.key)}
            </NavLink>
          ))}
        </nav>
      </header>
      <main className="mx-auto w-full max-w-5xl flex-1 px-4 py-6">
        <Outlet />
      </main>
    </div>
  );
}
