import * as React from "react";
import { useTranslation } from "react-i18next";

import { X } from "lucide-react";

import Markdown from "~/components/markdown/markdown";
import { buildCodePreviewSrcDoc, PREVIEW_IFRAME_SANDBOX } from "~/lib/preview-sandbox";
import { buildMermaidPreviewDoc } from "~/lib/mermaid-renderer";
import { Button } from "~/components/ui/button";
import { cn } from "~/lib/utils";

import { getCodePreviewLanguage } from "./code-preview-language";
import type { WorkbenchPanel } from "./workbench-context";

interface WorkbenchHostProps {
  panel: WorkbenchPanel;
  onClose: () => void;
  className?: string;
}

interface WorkbenchPanelRenderer {
  render: (panel: WorkbenchPanel) => React.ReactNode;
}

function readStringField(payload: Record<string, unknown>, key: string): string {
  const value = payload[key];
  return typeof value === "string" ? value : "";
}

/** Rendered by the bundled mermaid runtime inside the preview sandbox; errors show in place. */
function MermaidPreview({ code, title }: { code: string; title: string }) {
  const { t } = useTranslation();
  const srcDoc = React.useMemo(
    () => buildMermaidPreviewDoc(code, t("workbench.mermaid_render_failed")),
    [code, t],
  );
  return (
    <iframe
      title={title}
      sandbox={PREVIEW_IFRAME_SANDBOX}
      srcDoc={srcDoc}
      className="h-full w-full border-0"
    />
  );
}

function CodePreviewPanel({ panel }: { panel: WorkbenchPanel }) {
  const { t } = useTranslation();
  const [mode, setMode] = React.useState<"preview" | "source">("preview");

  const language = readStringField(panel.payload, "language");
  const normalizedLanguage = getCodePreviewLanguage(language);
  const code = readStringField(panel.payload, "code");

  const canRenderPreview =
    normalizedLanguage === "html" ||
    normalizedLanguage === "svg" ||
    normalizedLanguage === "markdown" ||
    normalizedLanguage === "mermaid";

  React.useEffect(() => {
    setMode(canRenderPreview ? "preview" : "source");
  }, [canRenderPreview, panel.payload]);

  const preview = React.useMemo(() => {
    if (normalizedLanguage !== "html" && normalizedLanguage !== "svg") {
      return null;
    }
    return buildCodePreviewSrcDoc(normalizedLanguage, code);
  }, [code, normalizedLanguage]);

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex items-center gap-2 border-b px-3 py-2">
        <Button
          type="button"
          size="sm"
          variant={mode === "preview" ? "secondary" : "ghost"}
          disabled={!canRenderPreview}
          onClick={() => {
            setMode("preview");
          }}
        >
          {t("workbench.preview")}
        </Button>
        <Button
          type="button"
          size="sm"
          variant={mode === "source" ? "secondary" : "ghost"}
          onClick={() => {
            setMode("source");
          }}
        >
          {t("workbench.source_code")}
        </Button>
      </div>

      <div className="flex-1 min-h-0">
        {mode === "preview" && canRenderPreview ? (
          normalizedLanguage === "markdown" ? (
            <div className="h-full overflow-auto p-4">
              <Markdown content={code} allowCodePreview={false} />
            </div>
          ) : normalizedLanguage === "mermaid" ? (
            <MermaidPreview code={code} title={panel.title} />
          ) : preview ? (
            <iframe
              title={panel.title}
              sandbox={PREVIEW_IFRAME_SANDBOX}
              srcDoc={preview}
              className="h-full w-full border-0"
            />
          ) : null
        ) : (
          <pre className="h-full overflow-auto bg-muted/30 p-4 text-xs">
            {code || t("workbench.empty_content")}
          </pre>
        )}
      </div>
    </div>
  );
}

function UnknownPanel({ panel }: { panel: WorkbenchPanel }) {
  return (
    <div className="h-full overflow-auto p-4">
      <div className="rounded-lg border bg-muted/30 p-3 text-xs">
        <pre>{JSON.stringify(panel.payload, null, 2)}</pre>
      </div>
    </div>
  );
}

const PANEL_RENDERERS: Record<string, WorkbenchPanelRenderer> = {
  "code-preview": {
    render: (panel) => <CodePreviewPanel panel={panel} />,
  },
};

export function WorkbenchHost({ panel, onClose, className }: WorkbenchHostProps) {
  const { t } = useTranslation();
  const renderer = PANEL_RENDERERS[panel.type];

  return (
    <section className={cn("flex h-full min-h-0 flex-col border-l", className)}>
      <div className="flex items-center justify-between gap-2 border-b px-3 py-2">
        <div className="min-w-0">
          <div className="truncate font-medium text-sm">{panel.title}</div>
          <div className="truncate text-muted-foreground text-xs">
            {t("workbench.type_label", { type: panel.type })}
          </div>
        </div>
        <Button
          aria-label={t("workbench.close_panel")}
          type="button"
          size="icon-sm"
          variant="ghost"
          onClick={onClose}
        >
          <X className="size-4" />
        </Button>
      </div>

      <div className="flex-1 min-h-0">
        {renderer ? renderer.render(panel) : <UnknownPanel panel={panel} />}
      </div>
    </section>
  );
}
