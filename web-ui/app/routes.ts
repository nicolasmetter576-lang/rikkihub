import { type RouteConfig, index, layout, route } from "@react-router/dev/routes";

export default [
  index("routes/home.tsx"),
  route("c/:id", "routes/c.$id.tsx"),
  layout("routes/settings.tsx", [
    route("settings", "routes/settings.index.tsx"),
    route("settings/assistants", "routes/settings.assistants.tsx"),
    route("settings/assistants/:id", "routes/settings.assistants.$id.tsx"),
    route("settings/providers", "routes/settings.providers.tsx"),
    route("settings/providers/:id", "routes/settings.providers.$id.tsx"),
    route("settings/extensions", "routes/settings.extensions.tsx"),
    route("settings/skills", "routes/settings.skills.tsx"),
    route("settings/skills/:name", "routes/settings.skills.$name.tsx"),
    route("settings/subagent-presets", "routes/settings.subagent-presets.tsx"),
    route("settings/cache-keep-alive", "routes/settings.cache-keep-alive.tsx"),
    route("settings/tool-execution", "routes/settings.tool-execution.tsx"),
    route("settings/tuiqiao", "routes/settings.tuiqiao.tsx"),
    route("settings/zaizao", "routes/settings.zaizao.tsx"),
    route("settings/prompt-polish", "routes/settings.prompt-polish.tsx"),
  ]),
  layout("routes/workspaces.tsx", [
    route("workspaces", "routes/workspaces.index.tsx"),
    route("workspaces/:id", "routes/workspaces.$id.tsx"),
  ]),
  route("translator", "routes/translator.tsx"),
  route("storage", "routes/storage.tsx"),
] satisfies RouteConfig;
