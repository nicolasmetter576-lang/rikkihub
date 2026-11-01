import { Link, Outlet } from "react-router";
import { ArrowLeft } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "~/components/ui/button";
import { useWorkspaceUpdates } from "@overlay/hooks/use-workspace-updates";

export default function WorkspacesLayout() {
  const { t } = useTranslation("workspace");
  // 订阅 workspace_update：App/Web 任一侧修改后失效 detail/list 缓存
  useWorkspaceUpdates();

  return (
    <div className="flex min-h-svh flex-col bg-background">
      <header className="sticky top-0 z-20 border-b bg-background/90 backdrop-blur">
        <div className="mx-auto flex w-full max-w-6xl items-center gap-3 px-4 py-3">
          <Button variant="ghost" size="sm" asChild>
            <Link to="/">
              <ArrowLeft className="size-4" />
              {t("nav.back_to_chat")}
            </Link>
          </Button>
          <h1 className="text-sm font-semibold">{t("nav.title")}</h1>
        </div>
      </header>
      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-6">
        <Outlet />
      </main>
    </div>
  );
}
