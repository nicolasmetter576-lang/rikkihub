// 主题：递归扫描、排除/阻止/延迟到递归、最大递归步数、最少激活数（扩大扫描深度）。
// 覆盖：递归关闭/开启、三层链、excludeRecursion(递归时不触发/聊天直接触发仍可)、preventRecursion、
//   delayUntilRecursion(1 级、true 旧写法、多级顺序、递归关闭时仍按级推进、聊天先命中的延迟条目)、
//   最大递归步数 1/2、最少激活数(满足/不满足/深度上限/聊天耗尽/递归缓冲不参与最少激活扫描/最少激活后再递归)、
//   次要关键词经由递归文本满足、条目内容含自身关键词、常驻条目参与递归文本
const E = (key, content, o = {}) => ({ key: Array.isArray(key) ? key : [key], content, ...o });
const R = { world_info_recursive: true };
const M = (name, entries, chat, settings = {}, extra = {}) => ({
  name, worlds: { 'wiq-r': book(entries) }, global: ['wiq-r'], chat, settings: { ...settings }, ...extra,
});
const chain = () => [
  E('alpha', 'Alpha mentions beta.', { order: 30 }),
  E('beta', 'Beta mentions gamma.', { order: 20 }),
  E('gamma', 'Gamma is the end.', { order: 10 }),
];
const cases = [];
const add = (...c) => cases.push(...c);

add(
  M('recursion-off-single-pass', chain(), turn('U:alpha'), {}),
  M('recursion-on-three-levels', chain(), turn('U:alpha'), R),
  M('recursion-on-no-seed', chain(), turn('U:nothing'), R),
  M('recursion-on-middle-seed', chain(), turn('U:beta'), R),
  M('recursion-self-key-no-loop', [E('alpha', 'Alpha says alpha again.')], turn('U:alpha'), R),
  M('recursion-constant-feeds-text', [E([], 'Constant mentions beta.', { constant: true }), E('beta', 'B')], turn('U:hi'), R),
  M('recursion-secondary-satisfied-by-recursion-text', [E('alpha', 'Alpha brings a crown.'), E('beta', 'B', { keysecondary: ['crown'], selectiveLogic: 3 }), E('beta', 'Chat-beta')], turn('U:alpha beta'), R),
  M('recursion-case-sensitivity-applies-to-recursion-text', [E('alpha', 'Mentions BETA.'), E('beta', 'B', { caseSensitive: true })], turn('U:alpha'), R),
);

// excludeRecursion / preventRecursion
add(
  M('exclude-recursion-blocks-recursive-hit', [E('alpha', 'Alpha mentions beta.'), E('beta', 'B', { excludeRecursion: true })], turn('U:alpha'), R),
  M('exclude-recursion-allows-chat-hit', [E('alpha', 'Alpha mentions beta.'), E('beta', 'B', { excludeRecursion: true })], turn('U:alpha beta'), R),
  M('exclude-recursion-irrelevant-when-recursion-off', [E('alpha', 'Alpha mentions beta.'), E('beta', 'B', { excludeRecursion: true })], turn('U:alpha beta'), {}),
  M('prevent-recursion-stops-chain', [E('alpha', 'Alpha mentions beta.', { preventRecursion: true }), E('beta', 'B')], turn('U:alpha'), R),
  M('prevent-recursion-only-affects-own-text', [E('alpha', 'Alpha mentions beta.', { preventRecursion: true }), E('gamma', 'Gamma mentions beta.'), E('beta', 'B')], turn('U:alpha gamma'), R),
);

// delayUntilRecursion
add(
  M('delay-until-recursion-activates-in-recursion', [E('alpha', 'Alpha.'), E('alpha', 'Delayed', { delayUntilRecursion: 1 })], turn('U:alpha'), R),
  M('delay-until-recursion-legacy-true', [E('alpha', 'Alpha.'), E('alpha', 'Delayed', { delayUntilRecursion: true })], turn('U:alpha'), R),
  M('delay-until-recursion-needs-recursion-pass', [E('alpha', 'Delayed', { delayUntilRecursion: 1 })], turn('U:alpha'), R),
  M('delay-until-recursion-recursion-off-never-runs', [E('alpha', 'Delayed', { delayUntilRecursion: 1 })], turn('U:alpha'), {}),
  M('delay-until-recursion-two-levels', [
    E('alpha', 'Immediate.', { order: 40 }),
    E('alpha', 'Level one.', { order: 30, delayUntilRecursion: 1 }),
    E('alpha', 'Level two.', { order: 20, delayUntilRecursion: 2 }),
    E('alpha', 'Level three.', { order: 10, delayUntilRecursion: 3 }),
  ], turn('U:alpha'), R),
  M('delay-until-recursion-levels-advance-even-if-recursion-off', [
    E('alpha', 'Immediate.', { order: 40 }),
    E('alpha', 'Level one.', { order: 30, delayUntilRecursion: 1 }),
    E('alpha', 'Level two.', { order: 20, delayUntilRecursion: 2 }),
  ], turn('U:alpha'), {}),
  M('delay-until-recursion-level-text-feeds-next', [
    E('alpha', 'Immediate mentions zeta.', { order: 40 }),
    E('zeta', 'Zeta level one.', { order: 30, delayUntilRecursion: 1 }),
    E('zeta', 'Zeta level two.', { order: 20, delayUntilRecursion: 2 }),
  ], turn('U:alpha'), R),
  M('delay-until-recursion-with-exclude', [E('alpha', 'Immediate.'), E('alpha', 'Both', { delayUntilRecursion: 1, excludeRecursion: true })], turn('U:alpha'), R),
);

// 最大递归步数
const deep = [
  E('a1', 'a1 mentions a2.', { order: 50 }), E('a2', 'a2 mentions a3.', { order: 40 }),
  E('a3', 'a3 mentions a4.', { order: 30 }), E('a4', 'a4 end.', { order: 20 }),
];
add(
  M('max-recursion-steps-unlimited', deep, turn('U:a1'), R),
  M('max-recursion-steps-1', deep, turn('U:a1'), { ...R, world_info_max_recursion_steps: 1 }),
  M('max-recursion-steps-2', deep, turn('U:a1'), { ...R, world_info_max_recursion_steps: 2 }),
  M('max-recursion-steps-3', deep, turn('U:a1'), { ...R, world_info_max_recursion_steps: 3 }),
);

// 最少激活数
const older = turn('A:ancient scroll', 'U:old beta', 'A:middle gamma', 'U:new delta');
const minE = () => [E('delta', 'D', { order: 40 }), E('gamma', 'G', { order: 30 }), E('beta', 'B', { order: 20 }), E('scroll', 'S', { order: 10 })];
add(
  M('min-activations-0-baseline', minE(), older, { world_info_depth: 1 }),
  M('min-activations-expands-depth', minE(), older, { world_info_depth: 1, world_info_min_activations: 2 }),
  M('min-activations-expands-until-satisfied-3', minE(), older, { world_info_depth: 1, world_info_min_activations: 3 }),
  M('min-activations-all-4', minE(), older, { world_info_depth: 1, world_info_min_activations: 4 }),
  M('min-activations-unsatisfiable-stops-at-chat-end', minE(), older, { world_info_depth: 1, world_info_min_activations: 9 }),
  M('min-activations-depth-max-caps', minE(), older, { world_info_depth: 1, world_info_min_activations: 4, world_info_min_activations_depth_max: 2 }),
  M('min-activations-already-satisfied-by-constant', [E([], 'C', { constant: true }), ...minE()], older, { world_info_depth: 1, world_info_min_activations: 1 }),
  M('min-activations-with-recursion', [E('delta', 'delta mentions epsilon.'), E('epsilon', 'E'), E('beta', 'B'), E('gamma', 'G')], older, { ...R, world_info_depth: 1, world_info_min_activations: 3 }),
  M('min-activations-recursion-text-not-in-min-scan', [E('delta', 'delta mentions beta.'), E('beta', 'B', { scanDepth: 1 })], older, { ...R, world_info_depth: 1, world_info_min_activations: 5 }),
  M('min-activations-ignores-entry-scandepth', [E('scroll', 'S', { scanDepth: 1 })], older, { world_info_depth: 1, world_info_min_activations: 1 }),
);

return await recordCases(cases);
