import * as React from "react";

import { useTranslation } from "react-i18next";

import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "~/components/ui/dialog";
import type { UploadedFileDto } from "~/types/dto";
import { FileManagerPanes } from "@overlay/components/file-manager/file-manager-panes";

/** The file manager as a picker for the chat input: selected files come back as uploaded attachments. */
export function FileManagerPickDialog({
  open,
  onOpenChange,
  onPicked,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onPicked: (files: UploadedFileDto[]) => void;
}) {
  const { t } = useTranslation("fileManager");
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="flex h-[85svh] flex-col gap-0 p-0 sm:max-w-6xl">
        <DialogHeader className="border-b px-4 py-3">
          <DialogTitle>{t("title")}</DialogTitle>
          <DialogDescription>{t("pick_hint")}</DialogDescription>
        </DialogHeader>
        {open ? (
          <FileManagerPanes
            className="min-h-0 flex-1"
            onSend={(files) => {
              onPicked(files);
              onOpenChange(false);
            }}
          />
        ) : null}
      </DialogContent>
    </Dialog>
  );
}
