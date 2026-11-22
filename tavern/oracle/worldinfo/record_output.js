// 主题：各 position 的产出落点与顺序（上游 world-info.js:5190-5281 BUILDING PROMPT）。
// 覆盖（正例/反例）：
//   before/after：order 大者先 unshift 到前面 → 拼接顺序为 order 升序；跨书同 order 按解析逆序
//   ANTop/ANBottom → anBefore/anAfter 数组
//   EMTop/EMBottom → worldInfoExamples（anchor before/after）
//   atDepth：默认 depth=4/role=0、role 1/2、depth 覆盖、同 depth+role 合组（组内 order 升序）、
//     异 role/depth 分组、组间按条目解析顺序
//   outlet：具名收集、同名多条（push 顺序 = order 降序）、多 outlet、无名跳过、未知 position 跳过
//   getRegexedString(WORLD_INFO, isPrompt) 把内容清空 → 激活但不进 prompt；minDepth 只挡 atDepth
await st.reset();
const E = (key, content, o = {}) => ({ key: Array.isArray(key) ? key : [key], content, ...o });
const W = (...es) => ({ 'wiq-o': book(es) });
const M = (name, entries, chat, extra = {}) => ({ name, worlds: W(...entries), global: ['wiq-o'], chat, ...extra });
const hit = turn('U:dragon');

const cases = [];
const add = (...c) => cases.push(...c);

// --- 七个位置各一个基本落点（position: before0 after1 ANTop2 ANBottom3 atDepth4 EMTop5 EMBottom6 outlet7）
add(
  M('pos-before-default', [E('dragon', 'BEFORE')], hit),
  M('pos-after', [E('dragon', 'AFTER', { position: 1 })], hit),
  M('pos-an-top', [E('dragon', 'ANTOP', { position: 2 })], hit),
  M('pos-an-bottom', [E('dragon', 'ANBOT', { position: 3 })], hit),
  M('pos-em-top', [E('dragon', 'EMT', { position: 5 })], hit),
  M('pos-em-bottom', [E('dragon', 'EMB', { position: 6 })], hit),
);

// --- atDepth：depth/role 组合
add(
  M('pos-atdepth-defaults', [E('dragon', 'D', { position: 4 })], hit),
  M('pos-atdepth-role-user', [E('dragon', 'D', { position: 4, role: 1 })], hit),
  M('pos-atdepth-role-assistant', [E('dragon', 'D', { position: 4, role: 2 })], hit),
  M('pos-atdepth-depth-7', [E('dragon', 'D', { position: 4, depth: 7 })], hit),
  // 同 depth+role 合组：组内 unshift → 升序
  M('pos-atdepth-merge-same', [
    E('dragon', 'D30', { position: 4, depth: 2, order: 30 }),
    E('dragon', 'D10', { position: 4, depth: 2, order: 10 }),
  ], hit),
  // 同 depth 异 role → 两个组
  M('pos-atdepth-split-role', [
    E('dragon', 'SYS', { position: 4, depth: 2, order: 30 }),
    E('dragon', 'USR', { position: 4, depth: 2, role: 1, order: 10 }),
  ], hit),
  // 异 depth → 两个组，组顺序 = 解析（order 降序）遭遇顺序
  M('pos-atdepth-split-depth', [
    E('dragon', 'D2', { position: 4, depth: 2, order: 30 }),
    E('dragon', 'D7', { position: 4, depth: 7, order: 10 }),
  ], hit),
);

// --- outlet
add(
  M('pos-outlet-basic', [E('dragon', 'O', { position: 7, outletName: 'sys' })], hit),
  M('pos-outlet-no-name-skipped', [
    E('dragon', 'O', { position: 7 }),
    E('dragon', 'B', { order: 50 }),
  ], hit),
  // 同名多条 push → order 降序
  M('pos-outlet-two-same-name', [
    E('dragon', 'O30', { position: 7, outletName: 'sys', order: 30 }),
    E('dragon', 'O10', { position: 7, outletName: 'sys', order: 10 }),
  ], hit),
  M('pos-outlet-two-names', [
    E('dragon', 'OS', { position: 7, outletName: 'sys', order: 30 }),
    E('dragon', 'OL', { position: 7, outletName: 'log', order: 10 }),
  ], hit),
);

// --- 非法 position / 排序
add(
  M('pos-unknown-skipped', [
    E('dragon', 'X', { position: 99 }),
    E('dragon', 'B', { order: 50 }),
  ], hit),
  // before 拼接 = order 升序（unshift 在降序遍历上）
  M('output-order-ascending', [
    E('dragon', 'C30', { order: 30 }),
    E('dragon', 'C10', { order: 10 }),
    E('dragon', 'C20', { order: 20 }),
  ], hit),
  // 跨书同 order：解析顺序 chat > 全局，unshift → 全局排前
  {
    name: 'output-same-order-two-books',
    worlds: { 'wiq-o': book([E('dragon', 'GLOBAL', { order: 100 })]), 'wiq-c': book([E('dragon', 'CHAT', { order: 100 })]) },
    global: ['wiq-o'], chatBook: 'wiq-c', chat: hit,
  },
  // 同书同 order：考察同序条目的最终拼接顺序（稳定排序 + unshift）
  M('output-same-order-same-book', [
    E('dragon', 'U0', { order: 100 }),
    E('dragon', 'U1', { order: 100 }),
  ], hit),
);

// --- 内容被提示词正则清空 → 激活但不进 prompt；minDepth 只挡 atDepth
const RX_WIPE = [{
  scriptName: 'wipe', findRegex: '/SECRET/g', replaceString: '',
  placement: [5], promptOnly: true, disabled: false, markdownOnly: false, runOnEdit: true,
  substituteRegex: 0, minDepth: null, maxDepth: null, trimStrings: [],
}];
add(
  // 内容被正则清空：activated 里仍有，prompt 字段为空
  { ...M('output-emptied-by-regex-still-activated',
      [E('dragon', 'SECRET'), E('dragon', 'B', { order: 50 })], hit),
    regexScripts: RX_WIPE },
  // minDepth 只作用于 atDepth（上游 typeof depth === 'number' 守卫）：
  // 深 4 的 atDepth 条目内容原样留下；普通位置条目 depth 为 null → 不被 minDepth 挡、正则照跑
  { ...M('output-regex-mindepth-only-blocks-atdepth',
      [E('dragon', 'SECRET', { position: 4, depth: 4, order: 100 }), E('dragon', 'SECRET', { order: 50 })], hit),
    regexScripts: [{ ...RX_WIPE[0], minDepth: 5 }] },
);

return await recordCases(cases);
