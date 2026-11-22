// 主题：计时效果 sticky/cooldown/delay 的多步序列与失效规则。
// 覆盖（正例/反例）：
//   sticky：无关键词也激活、到期后不再激活；sticky 结束自动挂 cooldown（protected）
//   cooldown：期间压掉命中、到期恢复；聊天没前进（chat.length <= start 且非 protected）整条移除
//   delay：chat.length < delay 压制、到达后放行（无状态，不写 timedWorldInfo）
//   dryRun：不查 sticky/cooldown（不应用也不推进），setTimedEffects 也不写
//   timedInit 播种：未知哈希未到期保留/到期清理、聊天未前进清理、protected 跳过前进检查、
//     条目存在但类型不符（!entry.sticky）清理 —— 哈希从同脚本先录的探针用例取
//   sticky 在包含组内碾压非粘滞成员；sticky 不再掷概率骰（randomUsed=0）
await st.reset();
const E = (key, content, o = {}) => ({ key: Array.isArray(key) ? key : [key], content, ...o });
const W = (...es) => ({ 'wiq-t': book(es) });
const M = (name, entries, steps, extra = {}) => ({ name, worlds: W(...entries), global: ['wiq-t'], steps, ...extra });
// 一步 = {chat: [...]}；聊天条数即上游计时的 chat.length
const S = (...msgs) => ({ chat: turn(...msgs) });
const BOGUS = 424242; // 不可能等于任何真实哈希

const cases = [];
const add = (...c) => cases.push(...c);

// --- sticky 生命周期
add(
  M('sticky-holds-then-expires', [E('dragon', 'S', { sticky: 2 })], [
    S('A:hi', 'U:dragon here'),                                    // len2：命中 → sticky{2,4}
    S('A:one', 'U:two', 'A:three'),                                // len3：无关键词，sticky 续命
    S('A:1', 'U:2', 'A:3', 'U:4', 'A:5'),                          // len5 ≥ end4：到期不再激活
  ]),
  M('sticky-then-cooldown-protected', [E('dragon', 'S', { sticky: 1, cooldown: 2 })], [
    S('A:hi', 'U:dragon'),                                         // len2：命中 → sticky{2,3}
    S('A:1', 'U:2', 'A:3'),                                        // len3：sticky 到期 → 自动挂 cooldown{3,5,protected}，被压
    S('A:1', 'U:2', 'A:3', 'U:4'),                                 // len4：cooldown 仍压
    S('A:1', 'U:2', 'A:3', 'U:4', 'U:dragon back'),                // len5：cooldown 到期 → 再命中激活
  ]),
  M('sticky-skips-probability-reroll', [E('dragon', 'S', { sticky: 5, probability: 50 })], [
    { ...S('A:hi', 'U:dragon'), random: [0.3] },                   // len2：roll 30 ≤ 50 → 激活 + sticky{2,7}
    { ...S('A:1', 'U:2', 'A:3'), random: [] },                     // len3：sticky 不掷骰（randomUsed 应 0）
  ]),
);

// --- cooldown 生命周期
add(
  M('cooldown-basic', [E('dragon', 'C', { cooldown: 2 })], [
    S('A:hi', 'U:dragon'),                                         // len2：命中 → cooldown{2,4}
    S('A:1', 'U:dragon again', 'A:3'),                             // len3：冷却中 → 压制
    S('A:1', 'U:2', 'A:3', 'U:dragon'),                            // len4 ≥ end：解除 → 再激活
  ]),
  M('cooldown-chat-not-advanced-drops', [E('dragon', 'C', { cooldown: 9 })], [
    S('A:hi', 'U:dragon'),                                         // len2：cooldown{2,11}
    S('A:other', 'U:dragon'),                                      // len2 ≤ start 且非 protected → 整条移除 → 再激活
  ]),
);

// --- delay（无状态）
add(
  M('delay-gates-early', [E('dragon', 'D', { delay: 3 })], [
    S('A:hi', 'U:dragon'),                                         // len2 < 3 → 压制
    S('A:1', 'U:2', 'U:dragon'),                                   // len3 = delay → 放行激活
  ]),
  M('delay-gates-multiple', [E('dragon', 'D', { delay: 5 })], [
    S('A:hi', 'U:dragon'),
    S('A:1', 'U:2', 'A:3', 'U:dragon'),                            // len4 < 5 仍压
    S('A:1', 'U:2', 'A:3', 'U:4', 'U:dragon'),                     // len5 放行
  ]),
);

// --- dryRun：不应用效果、不写状态
add(
  M('dryrun-freezes-effects', [E('dragon', 'S', { sticky: 5 })], [
    S('A:hi', 'U:dragon'),                                         // len2：激活 + sticky{2,7}
    { ...S('A:1', 'U:2', 'A:3'), dryRun: true },                   // dryRun：sticky 不生效（无关键词不激活），timed 不变
    S('A:1', 'U:2', 'A:3'),                                        // 正常步：sticky 还在 → 激活
    { ...S('A:1', 'U:2', 'A:3', 'U:4', 'U:5', 'A:6', 'U:7', 'U:dragon'), dryRun: true }, // len8 dryRun 命中 → 激活但 sticky 不刷新
  ]),
);

// --- timedInit 播种的清理分支
add(
  // 未知哈希（条目表里没有）且聊天未到期 → 保留原样
  M('seeded-missing-entry-kept', [E('dragon', 'A')], [S('A:hi', 'U:dragon')],
    { timedInit: { sticky: { 'wiq-t.0': { hash: BOGUS, start: 0, end: 9, protected: false } } } }),
  // 未知哈希且聊天已到期 → 清理
  M('seeded-missing-entry-expired', [E('dragon', 'A')], [S('A:hi', 'U:dragon')],
    { timedInit: { sticky: { 'wiq-t.0': { hash: BOGUS, start: 0, end: 2, protected: false } } } }),
  // 聊天未前进（chat.length <= start）且非 protected → 清理
  M('seeded-not-advanced-removed', [E('dragon', 'A')], [S('A:hi', 'U:dragon')],
    { timedInit: { sticky: { 'wiq-t.0': { hash: BOGUS, start: 2, end: 9, protected: false } } } }),
  // protected 跳过未前进检查（即便 start 在未来也保留）
  M('seeded-protected-skips-advance-check', [E('dragon', 'A')], [S('A:hi', 'U:dragon')],
    { timedInit: { sticky: { 'wiq-t.0': { hash: BOGUS, start: 5, end: 9, protected: true } } } }),
);

// --- 条目存在但类型不符 → 清理（哈希取自探针用例的真实激活记录，探针本身也录成一个用例）
const probe = await runCase(M('probe-hash', [E('dragon', 'P')], [S('A:hi', 'U:dragon')]));
const probeHash = probe.steps[0].activated[0].hash;
add(
  M('seeded-invalid-type-removed', [E('dragon', 'P')], [S('A:hi', 'U:dragon')],
    { timedInit: { sticky: { 'wiq-t.0': { hash: probeHash, start: 0, end: 9, protected: false } } } }),
);

// --- sticky × 包含组
add(
  M('sticky-wins-group', [
    E('alpha', 'A', { group: 'g', sticky: 4, order: 100 }),
    E('beta', 'B', { group: 'g', order: 50 }),
  ], [
    S('A:hi', 'U:alpha'),                                          // len2：只 A 命中 → 激活 + sticky{2,6}
    S('A:1', 'U:beta now', 'A:3'),                                 // len3：A 靠 sticky 复活、B 命中 → 组内非粘滞者 B 被除名
  ]),
);

const results = [probe];
for (const c of cases) results.push(await runCase(c));
return { upstream: 'SillyTavern 1.19.0', user: ctx.name1, char: ctx.name2, cases: results };
