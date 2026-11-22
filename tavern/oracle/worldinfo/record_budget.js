// 主题：token 预算（百分比、上限、四舍五入、"刚好超出/刚好装下"边界、ignoreBudget、递归文本占用预算、溢出后的行为）。
// 预算 = round(budget% × maxContext / 100)（至少 1），超出判定 `已激活文本 + 新增内容 的 token >= 预算`。
// token 计数走 openai 路径：cl100k 文本数 + 6（见 WorldInfoEngine.ST_TEXT_COUNT_OVERHEAD）。边界值用真酒馆的计数接口现算，
// 再在边界两侧各取一个用例（刚好装下 / 刚好超出 / 差一个）。
// 覆盖：单条边界 T-1/T/T+1、多条累计溢出、溢出后 ignoreBudget 条目仍可加入而普通条目被截断、ignoreBudget 内容占用后续预算、
//   上限(cap 小于/大于百分比预算)、百分比 0 → 预算 1、百分比 100、四舍五入(.5 进位)、递归文本占用预算、溢出阻止递归、
//   概率失败的条目不占用预算、中文/空内容的计数、预算对不同 maxContext 的缩放
const E = (key, content, o = {}) => ({ key: Array.isArray(key) ? key : [key], content, ...o });
async function stTokens(text) {
  const r = await fetch('/api/tokenizers/openai/count?model=gpt-3.5-turbo', {
    method: 'POST', headers: ctx.getRequestHeaders(), body: JSON.stringify([{ content: text }]),
  });
  return (await r.json()).token_count;
}
const T = async (...contents) => stTokens(contents.map(c => c + '\n').join(''));   // 与 newContent 的拼法一致
const mc = b => 4 * b;                                                                 // 预算百分比 25：maxContext = 4 × 预算
const M = (name, entries, chat, maxContext, settings = {}, extra = {}) => ({
  name, worlds: { 'wiq-b': book(entries) }, global: ['wiq-b'], chat, maxContext, settings: { ...settings }, ...extra,
});
const cases = [];
const add = (...c) => cases.push(...c);

const c1 = 'The old dragon sleeps beneath the mountain and dreams of gold.';
const c2 = 'A second entry with a few more words in it for counting.';
const c3 = 'Third entry text.';
const t1 = await T(c1), t12 = await T(c1, c2), t2 = await T(c2), t3 = await T(c3);
const chat = turn('U:dragon wizard castle');

// 单条边界
add(
  M('single-budget-T-plus-1-fits', [E('dragon', c1)], chat, mc(t1 + 1)),
  M('single-budget-T-overflows', [E('dragon', c1)], chat, mc(t1)),
  M('single-budget-T-minus-1-overflows', [E('dragon', c1)], chat, mc(t1 - 1)),
);

// 多条累计
add(
  M('two-entries-both-fit', [E('dragon', c1, { order: 20 }), E('wizard', c2, { order: 10 })], chat, mc(t12 + 1)),
  M('two-entries-second-overflows', [E('dragon', c1, { order: 20 }), E('wizard', c2, { order: 10 })], chat, mc(t12)),
  M('two-entries-second-overflows-first-fits-barely', [E('dragon', c1, { order: 20 }), E('wizard', c2, { order: 10 })], chat, mc(t1 + 1)),
  M('three-entries-stop-at-first-overflow', [E('dragon', c1, { order: 30 }), E('wizard', c2, { order: 20 }), E('castle', c3, { order: 10 })], chat, mc(t12)),
  M('overflow-uses-sorted-order-high-order-first', [E('dragon', c1, { order: 10 }), E('wizard', c2, { order: 20 })], chat, mc(t2 + 1)),
);

// ignoreBudget
add(
  M('ignorebudget-added-after-overflow', [
    E('dragon', c1, { order: 30 }), E('wizard', c2, { order: 20, ignoreBudget: true }), E('castle', c3, { order: 10 }),
  ], chat, mc(t1)),
  M('ignorebudget-skips-over-normal-entries-after-overflow', [
    E('dragon', c1, { order: 40 }), E('wizard', c2, { order: 30 }), E('castle', c3, { order: 20, ignoreBudget: true }), E('dragon', 'Tail normal.', { order: 10 }),
  ], chat, mc(t1)),
  M('ignorebudget-first-then-normal-sees-its-tokens', [
    E('dragon', c1, { order: 30, ignoreBudget: true }), E('castle', c3, { order: 20 }),
  ], chat, mc(t3 + 1)),
  M('ignorebudget-never-overflows-alone', [E('dragon', c1, { ignoreBudget: true })], chat, mc(2)),
);

// 上限/百分比/取整
add(
  M('cap-smaller-than-budget', [E('dragon', c1)], chat, mc(t1 + 100), { world_info_budget_cap: t1 }),
  M('cap-allows-fit', [E('dragon', c1)], chat, mc(t1 + 100), { world_info_budget_cap: t1 + 1 }),
  M('cap-larger-than-budget-no-effect', [E('dragon', c1)], chat, mc(t1), { world_info_budget_cap: 100000 }),
  M('budget-percent-zero-means-one', [E('dragon', c1)], chat, 100000, { world_info_budget: 0 }),
  M('budget-percent-100', [E('dragon', c1)], chat, t1 + 1, { world_info_budget: 100 }),
  M('budget-percent-100-boundary', [E('dragon', c1)], chat, t1, { world_info_budget: 100 }),
  M('budget-round-half-up-fits', [E('dragon', c1)], chat, 2 * t1 + 1, { world_info_budget: 50 }),     // (2T+1)/2 = T+0.5 → T+1
  M('budget-round-half-up-overflows', [E('dragon', c1)], chat, 2 * t1 - 1, { world_info_budget: 50 }), // (2T-1)/2 = T-0.5 → T
  M('budget-max-context-zero', [E('dragon', c1)], chat, 0),
);

// 递归与预算
const ra = 'Alpha text that mentions beta in the middle of it.';
const rb = 'Beta text.';
const tra = await T(ra), trb = await T(rb);
const tRecursion = await stTokens(ra + '\n' + '\n' ) ; // 仅用于对比：递归文本 = 内容 + "\n"
add(
  M('recursion-text-counts-toward-budget-second-fits', [E('alpha', ra), E('beta', rb)], turn('U:alpha'), mc(tra + trb + 20), { world_info_recursive: true }),
  M('recursion-text-counts-toward-budget-second-overflows', [E('alpha', ra), E('beta', rb)], turn('U:alpha'), mc(trb + 2), { world_info_recursive: true }),
  M('overflow-in-first-pass-blocks-recursion', [E('alpha', ra, { order: 20 }), E('alpha', rb, { order: 10 }), E('beta', 'Never.', { order: 5 })], turn('U:alpha'), mc(tra), { world_info_recursive: true }),
  M('overflowed-entry-text-not-fed-into-recursion', [E('alpha', ra, { order: 20 }), E('beta', rb, { order: 10 })], turn('U:alpha'), mc(tra), { world_info_recursive: true }),
);

// 其它
add(
  M('failed-probability-does-not-use-budget', [E('dragon', c1, { order: 30, probability: 50 }), E('wizard', c2, { order: 20 })], chat, mc(t2 + 1), {}, { random: [0.99] }),
  M('passed-probability-uses-budget', [E('dragon', c1, { order: 30, probability: 50 }), E('wizard', c2, { order: 20 })], chat, mc(t2 + 1), {}, { random: [0.1] }),
  M('cjk-content-counts', [E('dragon', '古老的巨龙沉睡在山脉之下，梦见了黄金。')], chat, mc((await T('古老的巨龙沉睡在山脉之下，梦见了黄金。')) + 1)),
  M('cjk-content-overflow', [E('dragon', '古老的巨龙沉睡在山脉之下，梦见了黄金。')], chat, mc(await T('古老的巨龙沉睡在山脉之下，梦见了黄金。'))),
  M('empty-content-counts-newline', [E('dragon', '')], chat, mc(8)),
  M('empty-content-newline-boundary', [E('dragon', '')], chat, mc(await T('') )),
  M('macro-content-counted-after-substitution', [E('dragon', 'Hi {{user}} and {{char}} and {{user}} again {{user}} {{user}}')], chat, mc((await T('Hi User and Seraphina and User again User User')) + 1)),
  M('macro-content-counted-after-substitution-overflow', [E('dragon', 'Hi {{user}} and {{char}} and {{user}} again {{user}} {{user}}')], chat, mc(await T('Hi User and Seraphina and User again User User'))),
  M('budget-scales-with-max-context', [E('dragon', c1), E('wizard', c2)], chat, 100000),
  M('depth-entries-and-budget-same', [E('dragon', c1, { position: 4, depth: 1, role: 1 })], chat, mc(t1 + 1)),
);

return await recordCases(cases);
