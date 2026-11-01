import * as React from "react";

import {
  applyWorkspaceCompletion,
  buildDeviceCompletionItems,
  buildWorkspaceCompletionItems,
  deviceCompletionIndexCache,
  deviceMountsFingerprint,
  findWorkspaceMention,
  KeyedInflightRequests,
  loadDeviceCompletionEntries,
  loadWorkspaceCompletionIndex,
  parseDeviceMentionQuery,
  toWorkspaceRelativePath,
  usableDeviceMounts,
  workspaceCompletionIndexCache,
  type DeviceCompletionEntries,
  type DeviceIndexSource,
  type DeviceMentionQuery,
  type WorkspaceCompletionEntry,
  type WorkspaceCompletionItem,
  type WorkspaceMention,
} from "@overlay/lib/workspace-completion";
import { workspaceApi, workspaceDeviceApi } from "@overlay/lib/workspace";

export type WorkspaceCompletionStatus =
  | "hidden"
  | "loading"
  | "ready"
  | "empty"
  | "error";

export interface WorkspaceCompletionViewState {
  status: WorkspaceCompletionStatus;
  items: WorkspaceCompletionItem[];
  selectedIndex: number;
  /** 这批候选所属 `${workspaceId} ${relativeCwd}`；非 hidden 态必为当前 key，用于拒收异 workspace 旧候选 */
  key: string;
}

const HIDDEN_STATE: WorkspaceCompletionViewState = {
  status: "hidden",
  items: [],
  selectedIndex: 0,
  key: "",
};

function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === "AbortError";
}

/**
 * Web 版 WorkspaceCompletionProvider（对齐 App）。
 *
 * - 仅当当前助手绑定了 workspaceId（且输入可用）时启用
 * - 索引走 workspaceApi.listFiles(FILES) BFS，500 项 / 80 目录有界；
 *   缓存按 (workspaceId, cwd) 隔离 TTL 5s
 * - 请求带 AbortController + generation，切助手/会话丢弃过期结果
 * - 不读文件内容（除 `.gitignore` 对齐 App 忽略机制）、不注入附件
 */
export function useWorkspaceFileCompletion(options: {
  workspaceId: string | null | undefined;
  /** 会话 cwd（绝对路径，/workspace 为根）；新会话为 null → 根目录 */
  cwd: string | null | undefined;
  /** 输入禁用 / IME composition 中置 false，弹层隐藏但不重置缓存 */
  enabled: boolean;
  textareaRef: React.RefObject<HTMLTextAreaElement | null>;
  onApplyValue: (nextText: string, nextCaret: number) => void;
}): {
  state: WorkspaceCompletionViewState;
  /** 候选就绪：Enter/Tab/↑↓ 应由补层消费 */
  candidatesActive: boolean;
  /** 弹层可见（含 loading/empty/error 态） */
  popupVisible: boolean;
  /** 重新评估光标位置（挂在 textarea onChange/onSelect/onClick/onKeyUp 上） */
  refresh: () => void;
  /** keydown 预处理；返回 true 表示已消费（调用方应 preventDefault 并返回） */
  handleKeyDown: (event: React.KeyboardEvent<HTMLTextAreaElement>) => boolean;
  /** 点击候选（弹层内部调用） */
  applyItem: (index: number) => void;
  /** hover 同步高亮（不改变选项，只移动选中行） */
  highlight: (index: number) => void;
  cancel: () => void;
} {
  const { workspaceId, cwd, enabled, textareaRef, onApplyValue } = options;
  const relativeCwd = React.useMemo(() => toWorkspaceRelativePath(cwd), [cwd]);

  const [state, setState] = React.useState<WorkspaceCompletionViewState>(HIDDEN_STATE);

  // 键控 in-flight：切 workspace/cwd 后旧请求必然 abort，绝不被新 refresh 复用
  const inflightRef = React.useRef<KeyedInflightRequests<WorkspaceCompletionEntry[] | null> | null>(
    null,
  );
  inflightRef.current ??= new KeyedInflightRequests();
  // 设备域独立 in-flight 槽（key = device scope 键），与 workspace 域互不阻塞
  const deviceInflightRef = React.useRef<KeyedInflightRequests<DeviceCompletionEntries> | null>(
    null,
  );
  deviceInflightRef.current ??= new KeyedInflightRequests();
  const generationRef = React.useRef(0);
  const mentionRef = React.useRef<WorkspaceMention | null>(null);
  const dismissedRef = React.useRef<{ start: number; end: number } | null>(null);
  const stateRef = React.useRef(state);
  stateRef.current = state;

  // 当前请求键：workspaceId + cwd 联合隔离（与索引缓存同粒度）
  const currentKey = workspaceId ? `${workspaceId} ${relativeCwd}` : "";

  const hide = React.useCallback(() => {
    mentionRef.current = null;
    setState((prev) => (prev.status === "hidden" ? prev : HIDDEN_STATE));
  }, []);

  const loadEntries = React.useCallback((): Promise<WorkspaceCompletionEntry[] | null> => {
    const wid = workspaceId;
    if (!wid) return Promise.resolve(null);

    const cached = workspaceCompletionIndexCache.get(wid, relativeCwd);
    if (cached) return Promise.resolve(cached);

    // 同 key 在飞 → 复用（缓存粒度就是 workspace+cwd）；
    // 异 key（切 workspace/cwd）→ manager 内部 abort 旧请求另起，旧 promise 不会被采纳
    const key = currentKey;
    const inflight = inflightRef.current;
    if (!inflight) return Promise.resolve(null);
    const reused = inflight.get(key);
    if (reused) return reused;

    const generation = generationRef.current;
    return inflight.start(key, async (signal) => {
      try {
        const entries = await loadWorkspaceCompletionIndex({
          cwd: relativeCwd,
          signal,
          source: {
            listFiles: async (path) => {
              const response = await workspaceApi.listFiles(wid, "FILES", path);
              return response.entries.map((entry) => ({
                path: entry.path,
                name: entry.name,
                isDirectory: entry.isDirectory,
              }));
            },
            readTextFile: async (path) => {
              try {
                const file = await workspaceApi.readFile(wid, "FILES", path);
                return file.content;
              } catch {
                return null;
              }
            },
          },
        });
        // 双保险：只有未过期（generation 未变）且未被 abort 的结果才写缓存
        if (generationRef.current === generation && !signal.aborted) {
          workspaceCompletionIndexCache.set(wid, relativeCwd, entries);
        }
        return entries;
      } catch (error) {
        if (isAbortError(error)) return null;
        throw error;
      }
    });
  }, [currentKey, workspaceId, relativeCwd]);

  // 设备数据源：每次调用都是新鲜 REST（不在 source 层做长缓存），
  // 可用性/挂载变化由调用方的指纹比较兜住。
  const deviceSource = React.useMemo<DeviceIndexSource>(
    () => ({
      isAvailable: async () => {
        if (!workspaceId) return false;
        const availability = await workspaceDeviceApi.availability(workspaceId);
        return availability.canBrowse === true;
      },
      listMounts: async () => {
        if (!workspaceId) return [];
        return workspaceDeviceApi.listMounts(workspaceId);
      },
      listFiles: async (mountId, path) => {
        if (!workspaceId) return [];
        const response = await workspaceDeviceApi.listFiles(workspaceId, mountId, path);
        return response.entries.map((entry) => ({
          path: entry.path,
          name: entry.name,
          isDirectory: entry.isDirectory,
        }));
      },
    }),
    [workspaceId],
  );

  const computeForCurrentCaret = React.useCallback((): {
    mention: WorkspaceMention;
    items: WorkspaceCompletionItem[];
  } | null => {
    const textarea = textareaRef.current;
    if (!textarea || !enabled) return null;
    // 有选区不触发（对齐 App hasSelection 检查）
    if (textarea.selectionStart !== textarea.selectionEnd) return null;
    const mention = findWorkspaceMention(textarea.value, textarea.selectionEnd);
    if (!mention) return null;
    const dismissed = dismissedRef.current;
    if (dismissed && dismissed.start === mention.start && dismissed.end === mention.end) {
      return null;
    }
    mentionRef.current = mention;
    return { mention, items: [] };
  }, [enabled, textareaRef]);

  // -----------------------------------------------------------------
  // 设备域（@/device/...）：每 query 重新验证 availability+mounts
  // -----------------------------------------------------------------

  const deviceRefresh = React.useCallback(
    (mention: WorkspaceMention, parsed: DeviceMentionQuery) => {
      const wid = workspaceId;
      if (!wid) {
        hide();
        return;
      }
      // dirKey：files 形态=alias+已键入父目录；mounts 形态=挂载列表桶
      const dirKey =
        parsed.kind === "files" ? `${parsed.alias}\u0000${parsed.dir}` : "\u0000mounts";
      const generation = generationRef.current;
      const inflight = deviceInflightRef.current;
      if (!inflight) {
        hide();
        return;
      }

      setState((prev) =>
        prev.status === "hidden" || prev.status === "error" || !prev.key.startsWith("device\u0000")
          ? { status: "loading", items: [], selectedIndex: 0, key: `device\u0000${wid}` }
          : prev,
      );

      const finishItems = (
        current: { mention: WorkspaceMention },
        loaded: DeviceCompletionEntries,
      ) => {
        const items = buildDeviceCompletionItems(loaded.entries, current.mention);
        setState(
          items.length === 0
            ? { status: "empty", items: [], selectedIndex: 0, key: `device\u0000${wid}` }
            : { status: "ready", items, selectedIndex: 0, key: `device\u0000${wid}` },
        );
      };

      const task = async (signal: AbortSignal): Promise<DeviceCompletionEntries> => {
        // 每次查询都重新验证：availability + mounts 拉最新的
        const mounts = await deviceSource.listMounts();
        const fingerprint = deviceMountsFingerprint(mounts);
        const usable = usableDeviceMounts(mounts);

        if (usable.length === 0) return { fingerprint, entries: [] };

        if (parsed.kind === "mounts") {
          const prefix = parsed.aliasPrefix.toLowerCase();
          return {
            fingerprint,
            entries: usable
              .filter((mount) => mount.alias.toLowerCase().startsWith(prefix))
              .map((mount) => ({ path: mount.alias, name: mount.alias, isDirectory: true })),
          };
        }

        if (signal.aborted) throw new DOMException("aborted", "AbortError");
        const mount = usable.find((candidate) => candidate.alias === parsed.alias);
        if (!mount) return { fingerprint, entries: [] }; // unknown alias：无 fallback

        // 命中缓存也要先把上面的 mounts 新鲜校验跑完（指纹变 → key 变 → 必 miss）
        const cached = deviceCompletionIndexCache.get(wid, fingerprint, dirKey);
        if (cached) return { fingerprint, entries: cached };

        // 可用性在列文件前再确认一次（授权撤回 → 403 也算不可用，不缓存）
        if (!(await deviceSource.isAvailable())) return { fingerprint, entries: [] };

        const loaded = await loadDeviceCompletionEntries({
          query: parsed,
          source: deviceSource,
          signal,
        });
        if (
          generationRef.current === generation &&
          !signal.aborted &&
          loaded.fingerprint === fingerprint
        ) {
          deviceCompletionIndexCache.set(wid, fingerprint, dirKey, loaded.entries);
        }
        return loaded.fingerprint === fingerprint
          ? loaded
          : { fingerprint, entries: [] }; // 列目录期间挂载变了 → 不信任结果
      };

      // in-flight 复用粒度：同 workspace+alias+dir 的同一次 typing burst
      const inflightKey = `device\u0000${wid}\u0000${dirKey}`;
      const promise = inflight.get(inflightKey) ?? inflight.start(inflightKey, task);
      void promise
        .then((loaded) => {
          if (generationRef.current !== generation) return; // workspace 已切换
          const current = computeForCurrentCaret();
          if (!current) {
            setState((prev) => (prev.status === "hidden" ? prev : HIDDEN_STATE));
            return;
          }
          const live = parseDeviceMentionQuery(current.mention.query);
          if (!live || live.rest !== parsed.rest) {
            // 光标/输入已推进：重新评估一次（同 key in-flight 已 settle，会走缓存或新拉）
            refreshRef.current();
            return;
          }
          finishItems(current, loaded);
        })
        .catch((error) => {
          if (error instanceof Error && error.name === "AbortError") return;
          if (generationRef.current !== generation) return;
          setState({ status: "error", items: [], selectedIndex: 0, key: `device\u0000${wid}` });
        });
    },
    [computeForCurrentCaret, deviceSource, hide, workspaceId],
  );

  // refresh 自引用（device 结果落地后 caret 变了需要再评估一次）
  const refreshRef = React.useRef<() => void>(() => {});

  const refresh = React.useCallback(() => {
    const found = computeForCurrentCaret();
    if (!found) {
      hide();
      return;
    }
    const deviceParsed = parseDeviceMentionQuery(found.mention.query);
    if (deviceParsed) {
      deviceRefresh(found.mention, deviceParsed);
      return;
    }
    const key = currentKey;
    const generation = generationRef.current;

    const finish = (entries: WorkspaceCompletionEntry[] | null, failed: boolean) => {
      if (generationRef.current !== generation) return;
      // 期间光标/文本可能又变了：用当前最新的 mention 重新过滤（不是丢弃）
      const current = computeForCurrentCaret();
      if (!current) {
        setState((prev) => (prev.status === "hidden" ? prev : HIDDEN_STATE));
        return;
      }
      if (failed) {
        setState({ status: "error", items: [], selectedIndex: 0, key });
        return;
      }
      const items = buildWorkspaceCompletionItems(
        entries ?? [],
        current.mention,
        relativeCwd,
      );
      setState(
        items.length === 0
          ? { status: "empty", items: [], selectedIndex: 0, key }
          : { status: "ready", items, selectedIndex: 0, key },
      );
    };

    const cached = workspaceCompletionIndexCache.get(workspaceId ?? "", relativeCwd);
    if (cached || !workspaceId) {
      finish(cached, false);
      return;
    }

    setState((prev) =>
      prev.status === "hidden" || prev.status === "error" || prev.key !== key
        ? { status: "loading", items: [], selectedIndex: 0, key }
        : prev,
    );
    void loadEntries()
      .then((entries) => finish(entries, entries === null))
      .catch(() => finish(null, true));
  }, [
    computeForCurrentCaret,
    currentKey,
    deviceRefresh,
    hide,
    loadEntries,
    relativeCwd,
    workspaceId,
  ]);

  React.useEffect(() => {
    refreshRef.current = refresh;
  }, [refresh]);

  React.useEffect(() => {
    // workspace / cwd 变化 → bump generation（finish 旧结果作废）+ abort 在飞请求
    generationRef.current += 1;
    inflightRef.current?.abort();
    deviceInflightRef.current?.abort();
    deviceCompletionIndexCache.invalidate(); // 跨 workspace 切换：设备桶全部作废
    dismissedRef.current = null;
    hide();
    return () => {
      inflightRef.current?.abort();
      deviceInflightRef.current?.abort();
    };
  }, [workspaceId, relativeCwd, hide]);

  React.useEffect(() => {
    if (!enabled) hide();
  }, [enabled, hide]);

  const applyItem = React.useCallback(
    (index: number) => {
      // 异 workspace/cwd 的旧候选不采纳（effect 前的渲染竞态窗口防护）
      if (stateRef.current.key !== currentKey) return;
      const textarea = textareaRef.current;
      const mention = mentionRef.current;
      const item = stateRef.current.items[index];
      if (!textarea || !mention || !item) return;
      // 点击弹层期间光标可能已移动（onPointerDown preventDefault 理论上不会，
      // 但 IME / 程序性 focus 变化仍可能）——校验 live mention 仍是同一个再替换
      const live = findWorkspaceMention(textarea.value, textarea.selectionEnd);
      if (!live || live.start !== mention.start || live.end < mention.start) return;
      const next = applyWorkspaceCompletion(
        textarea.value,
        live.start,
        live.end,
        item.insertText,
      );
      dismissedRef.current = null;
      onApplyValue(next.text, next.cursor);
      // 目录会以新 query 立刻再补全（drill-in）；文件末尾空格则自然关闭
      mentionRef.current = null;
      setState(HIDDEN_STATE);
      requestAnimationFrame(() => refresh());
    },
    [currentKey, onApplyValue, refresh, textareaRef],
  );

  const cancel = React.useCallback(() => {
    const mention = mentionRef.current;
    if (mention) {
      dismissedRef.current = { start: mention.start, end: mention.end };
    }
    hide();
  }, [hide]);

  const handleKeyDown = React.useCallback(
    (event: React.KeyboardEvent<HTMLTextAreaElement>): boolean => {
      const current = stateRef.current;
      if (current.status === "hidden") {
        // 弹层关闭时按键照常触发重估（如键入 @ 立即出现候选）
        return false;
      }

      // 残留的异 workspace 弹层：不消费按键，直接收起
      if (current.key !== currentKey) {
        hide();
        return false;
      }

      if (event.key === "Escape") {
        cancel();
        return true;
      }

      // loading / empty / error 态没有可选候选 → 不劫持任何编辑键，Escape 除外
      if (current.status !== "ready" || current.items.length === 0) {
        return false;
      }

      switch (event.key) {
        case "ArrowDown":
          setState((prev) => ({
            ...prev,
            selectedIndex: (prev.selectedIndex + 1) % prev.items.length,
          }));
          return true;
        case "ArrowUp":
          setState((prev) => ({
            ...prev,
            selectedIndex: (prev.selectedIndex - 1 + prev.items.length) % prev.items.length,
          }));
          return true;
        case "Tab":
        case "Enter":
          applyItem(stateRef.current.selectedIndex);
          return true;
        default:
          return false;
      }
    },
    [applyItem, cancel, currentKey, hide],
  );

  const highlight = React.useCallback((index: number) => {
    setState((prev) =>
      prev.status === "ready" && index >= 0 && index < prev.items.length
        ? { ...prev, selectedIndex: index }
        : prev,
    );
  }, []);

  const candidatesActive = state.status === "ready" && state.items.length > 0;
  const popupVisible = state.status !== "hidden";

  return {
    state,
    candidatesActive,
    popupVisible,
    refresh,
    handleKeyDown,
    applyItem,
    highlight,
    cancel,
  };
}
