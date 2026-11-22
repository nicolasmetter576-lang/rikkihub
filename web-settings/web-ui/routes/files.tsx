import * as React from "react";

import { ArrowLeft } from "lucide-react";
import { Link } from "react-router";
import { useTranslation } from "react-i18next";

import { Button } from "~/components/ui/button";
import { FileManagerPanes } from "@overlay/components/file-manager/file-manager-panes";

export default function FileManagerPage() {
  const { t } = useTranslation("fileManager");
  return (
    <div className="flex h-svh flex-col bg-background">
      <header className="border-b bg-background/90 backdrop-blur">
        <div className="flex w-full items-center gap-3 px-4 py-3">
          <Button variant="ghost" size="sm" asChild>
            <Link to="/">
              <ArrowLeft className="size-4" />
              {t("back_to_chat")}
            </Link>
          </Button>
          <h1 className="text-sm font-semibold">{t("title")}</h1>
        </div>
      </header>
      <FileManagerPanes className="flex-1" />
    </div>
  );
}
