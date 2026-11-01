/**
 * 按当前实际挂载的面板拼一个 setLayout 用的布局（百分比，合计 100）。
 * 工作台面板只在打开时挂载，所以记下来的布局里可能多出或缺少面板：多的丢掉，
 * 缺的用 fallback 里的值（没有则 0），再按比例缩放到 100。全为 0 时返回 null（不改布局）。
 */
export function fitPanelLayout(
  wanted: Record<string, number>,
  panelIds: readonly string[],
  fallback: Record<string, number> = {},
): Record<string, number> | null {
  const raw = panelIds.map((id) => Math.max(0, wanted[id] ?? fallback[id] ?? 0));
  const total = raw.reduce((sum, value) => sum + value, 0);
  if (total <= 0) return null;
  return Object.fromEntries(panelIds.map((id, index) => [id, (raw[index] * 100) / total]));
}

/**
 * 把 [id] 面板设成 [size]%，其余面板按原比例分掉剩下的部分。[id] 不在布局里时原样返回。
 */
export function widenPanelLayout(layout: Record<string, number>, id: string, size: number): Record<string, number> {
  if (!(id in layout)) return layout;
  const others = Object.entries(layout).filter(([key]) => key !== id);
  const rest = others.reduce((sum, [, value]) => sum + Math.max(0, value), 0);
  const left = 100 - size;
  return Object.fromEntries(
    Object.keys(layout).map((key) => [
      key,
      key === id ? size : rest > 0 ? (Math.max(0, layout[key]) * left) / rest : left / others.length,
    ]),
  );
}
