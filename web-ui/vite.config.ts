import path from "node:path";
import { fileURLToPath } from "node:url";

import { reactRouter } from "@react-router/dev/vite";
import tailwindcss from "@tailwindcss/vite";
import { defineConfig, type Plugin } from "vite";
import tsconfigPaths from "vite-tsconfig-paths";
import svgr from "vite-plugin-svgr";

const projectRoot = path.dirname(fileURLToPath(import.meta.url));
const overlayDir = path.resolve(projectRoot, "../web-settings/web-ui");
const overlayAliasDir = path.resolve(projectRoot, "app/overlay");

function overlayBareModuleResolve(): Plugin {
  return {
    name: "overlay-bare-module-resolve",
    enforce: "pre",
    async resolveId(id, importer, options) {
      if (!importer) return null;
      const normalizedImporter = importer.replaceAll("\\", "/");
      if (!normalizedImporter.includes("/web-settings/web-ui/")) {
        return null;
      }
      if (id.startsWith(".") || id.startsWith("/") || id.startsWith("@overlay")) {
        return null;
      }
      return this.resolve(id, path.join(projectRoot, "app/root.tsx"), {
        ...options,
        skipSelf: true,
      });
    },
  };
}

export default defineConfig({
  plugins: [overlayBareModuleResolve(), tailwindcss(), reactRouter(), tsconfigPaths(), svgr()],
  resolve: {
    alias: {
      "@overlay": overlayAliasDir,
    },
  },
  server: {
    fs: {
      allow: [projectRoot, overlayDir],
    },
    proxy: {
      // Dev-only /api proxy (pnpm dev → local Ktor). The app web server
      // enforces Origin==Host for browser calls; changeOrigin only rewrites
      // Host, so without an explicit Origin rewrite every dev request with an
      // Origin header would hit the production cross-origin deny. This is a
      // DEV-ONLY allowance — production is same-origin deployed and untouched.
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
        ws: true,
        configure: (proxy) => {
          proxy.on("proxyReq", (proxyReq) => {
            if (proxyReq.getHeader("origin")) {
              proxyReq.setHeader("origin", "http://localhost:8080");
            }
          });
          proxy.on("proxyReqWs", (proxyReq) => {
            if (proxyReq.getHeader("origin")) {
              proxyReq.setHeader("origin", "http://localhost:8080");
            }
          });
        },
      },
    },
  },
});
