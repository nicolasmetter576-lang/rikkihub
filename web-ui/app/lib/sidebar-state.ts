// 聊天页侧边栏展开/收起状态，按浏览器记住；读不到（隐私模式、被禁用）一律按展开处理
export const SIDEBAR_OPEN_STORAGE_KEY = "rikkihub-sidebar-open";

type SidebarStateStorage = Pick<Storage, "getItem" | "setItem">;

function defaultStorage(): SidebarStateStorage | null {
  try {
    return typeof window === "undefined" ? null : window.localStorage;
  } catch {
    return null;
  }
}

export function readSidebarOpen(storage: SidebarStateStorage | null = defaultStorage()): boolean {
  try {
    return storage?.getItem(SIDEBAR_OPEN_STORAGE_KEY) !== "false";
  } catch {
    return true;
  }
}

export function writeSidebarOpen(
  open: boolean,
  storage: SidebarStateStorage | null = defaultStorage(),
): void {
  try {
    storage?.setItem(SIDEBAR_OPEN_STORAGE_KEY, String(open));
  } catch {
    // 写不进去只影响下次刷新，不影响当前开合
  }
}
