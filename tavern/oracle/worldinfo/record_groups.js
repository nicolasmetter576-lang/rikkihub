// 主题：包含组（inclusion group）。上游 world-info.js:5388 filterByInclusionGroups。
// 覆盖（正例/反例）：
//   组内只留一条：加权随机（固定 Math.random 序列，含边界 roll==累计权重）、默认权重 100
//   groupOverride：覆盖者直接胜出（不掷骰）、多个覆盖者按 order 取第一个
//   useGroupScoring：全局开关、条目级 true 触发整组评分、条目级 false 在全局开时不被淘汰、
//     评分平局回到加权随机、评分键命中数（getScore）高低淘汰
//   单成员组直接放行（不掷骰）、空组名不组组、禁用成员进不了组
//   上一递归层已激活的组 → 新成员整组压掉（对照组：无组则正常递归激活）
//   多组成员在一个组被淘汰即从 newEntries 移除（另一个组的成员资格救不回来）
//   粘滞成员碾压非粘滞在 timed.json（sticky-wins-group）；冷却/延迟成员在扫描阶段已被压掉，
//   永远到不了分组（上游 5359 行注释同款），这里不再覆盖。
await st.reset();
const E = (key, content, o = {}) => ({ key: Array.isArray(key) ? key : [key], content, ...o });
const W = (...es) => ({ 'wiq-g': book(es) });
const M = (name, entries, chat, extra = {}) => ({ name, worlds: W(...entries), global: ['wiq-g'], chat, ...extra });
const REC = { settings: { world_info_recursive: true } };

const cases = [];
const add = (...c) => cases.push(...c);

// --- 加权随机：组内顺序 = 条目解析顺序（order 降序），roll = random() * 总权重
const W30_70 = () => [
  E('dragon', 'A', { group: 'g', groupWeight: 30, order: 100 }),
  E('dragon', 'B', { group: 'g', groupWeight: 70, order: 50 }),
];
add(
  // roll = 0.2*100 = 20 ≤ 30 → 第一条
  M('group-weighted-first', W30_70(), turn('U:dragon'), { random: [0.2] }),
  // roll = 0.5*100 = 50 > 30 → 第二条
  M('group-weighted-second', W30_70(), turn('U:dragon'), { random: [0.5] }),
  // 边界：roll = 0.3*100 = 30 ≤ 累计 30 → 仍归第一条
  M('group-weighted-boundary', W30_70(), turn('U:dragon'), { random: [0.3] }),
  // 不带 groupWeight → DEFAULT_WEIGHT 100 各一：roll = 0.6*200 = 120 > 100 → 第二条
  M('group-weight-default', [
    E('dragon', 'A', { group: 'g', order: 100 }),
    E('dragon', 'B', { group: 'g', order: 50 }),
  ], turn('U:dragon'), { random: [0.6] }),
);

// --- groupOverride
add(
  // 覆盖者即使 order 低也赢；不掷骰（randomUsed 应为 0）
  M('group-override-wins', [
    E('dragon', 'A', { group: 'g', groupOverride: true, order: 50 }),
    E('dragon', 'B', { group: 'g', order: 100 }),
  ], turn('U:dragon')),
  // 多个覆盖者按 sortFn 排序取第一个（order 最高）
  M('group-override-highest-order', [
    E('dragon', 'A', { group: 'g', groupOverride: true, order: 50 }),
    E('dragon', 'B', { group: 'g', groupOverride: true, order: 100 }),
    E('dragon', 'C', { group: 'g', order: 200 }),
  ], turn('U:dragon')),
);

// --- 单成员 / 空组名 / 禁用
add(
  // 单成员组跳过整段逻辑，不掷骰
  M('group-single-entry', [E('dragon', 'A', { group: 'g' })], turn('U:dragon')),
  // group 为空串不算组，两条都激活
  M('group-empty-name-ignored', [
    E('dragon', 'A', { group: '', order: 100 }),
    E('dragon', 'B', { group: '', order: 50 }),
  ], turn('U:dragon')),
  // 禁用成员在扫描阶段就被滤掉，组里只剩一条
  M('group-disabled-member', [
    E('dragon', 'A', { group: 'g', disable: true, order: 100 }),
    E('dragon', 'B', { group: 'g', order: 50 }),
  ], turn('U:dragon')),
);

// --- 组评分
const SCORE_ON = { settings: { world_info_use_group_scoring: true } };
add(
  // 全局开：score 2 淘汰 score 1，直接胜出（不掷骰）
  M('group-scoring-global', [
    E(['dragon', 'castle'], 'A', { group: 'g', order: 50 }),
    E('dragon', 'B', { group: 'g', order: 100 }),
  ], turn('U:dragon castle'), SCORE_ON),
  // 平分无人被淘汰 → 回到加权随机（roll=50>30 → B）
  M('group-scoring-tie-random', [
    E('dragon', 'A', { group: 'g', order: 100, groupWeight: 30 }),
    E('dragon', 'B', { group: 'g', order: 50, groupWeight: 70 }),
  ], turn('U:dragon'), { ...SCORE_ON, random: [0.5] }),
  // 全局关但有条目标记 → 整组评分；被标记者分低被淘汰
  M('group-scoring-entry-flag-triggers', [
    E('dragon', 'A', { group: 'g', useGroupScoring: true, order: 100 }),
    E(['dragon', 'castle'], 'B', { group: 'g', order: 50 }),
  ], turn('U:dragon castle')),
  // 全局开但条目标记 false → 该条不参与评分不被淘汰 → 两条留到加权随机
  M('group-scoring-entry-false-kept', [
    E('dragon', 'A', { group: 'g', useGroupScoring: false, order: 100, groupWeight: 30 }),
    E(['dragon', 'castle'], 'B', { group: 'g', order: 50, groupWeight: 70 }),
  ], turn('U:dragon castle'), { ...SCORE_ON, random: [0.5] }),
  // 全局关且无标记 → 不评分直接加权随机
  M('group-no-scoring', [
    E(['dragon', 'castle'], 'A', { group: 'g', order: 100, groupWeight: 30 }),
    E('dragon', 'B', { group: 'g', order: 50, groupWeight: 70 }),
  ], turn('U:dragon castle'), { random: [0.5] }),
);

// --- 已激活组（递归上一层赢过的组不再出新成员）
add(
  M('group-already-activated-recursion', [
    E('dragon', 'A runecode lore', { group: 'g', order: 100 }),
    E('runecode', 'B', { group: 'g', order: 50 }),
  ], turn('U:dragon'), REC),
  // 对照：无组则递归命中的第二条正常激活
  M('no-group-recursion-both-activate', [
    E('dragon', 'A runecode lore', { order: 100 }),
    E('runecode', 'B', { order: 50 }),
  ], turn('U:dragon'), REC),
);

// --- 多组成员
add(
  // X 同时在组 a、b：a 里只有它自己放行；b 里加权输了 → 从 newEntries 移除，整体不激活
  M('group-multi-membership-loser-drops', [
    E('dragon', 'X', { group: 'a,b', order: 100, groupWeight: 10 }),
    E('dragon', 'Y', { group: 'b', order: 50, groupWeight: 80 }),
    E('dragon', 'Z', { group: 'b', order: 30, groupWeight: 10 }),
  ], turn('U:dragon'), { random: [0.5] }),
);

return await recordCases(cases);
