// 随机宏：固定 Math.random 序列下的 roll/random；pick 的确定性（聊天+内容+位置+reroll）
await st.reset();
st.freezeClock('2025-03-04T05:06:07Z');
try {
  const cases = [
    { name: 'roll-simple', input: '{{roll::1d6}}', random: [0.5] },
    { name: 'roll-sequence', input: '{{roll::1d6}}|{{roll::1d6}}|{{roll::1d20}}', random: [0.0, 0.999, 0.5] },
    { name: 'roll-multi-dice', input: '{{roll::3d6+4}}', random: [0.0, 0.5, 0.999] },
    { name: 'roll-numeric-shorthand', input: '{{roll::6}}', random: [0.5] },
    { name: 'roll-minus', input: '{{roll::2d4-1}}', random: [0.0, 0.999] },
    { name: 'roll-invalid', input: '[{{roll::not-a-die}}]' },
    { name: 'roll-percentile', input: '{{roll::1d100}}', random: [0.42] },
    { name: 'random-multi', input: '{{random::blonde::brown::red::black::blue}}', random: [0.5], allow: ['blonde','brown','red','black','blue'] },
    { name: 'random-comma-list', input: '{{random::a,b,c}}', random: [0.9], allow: ['a','b','c'] },
    { name: 'random-empty', input: '[{{random}}]' },
    { name: 'pick-basic', input: '{{pick::blonde::brown::red::black::blue}}' },
    { name: 'pick-same-position', input: '{{pick::x::y::z}} {{pick::x::y::z}}' },
    { name: 'pick-different-position', input: 'aaa{{pick::x::y::z}}|bbb{{pick::x::y::z}}|ccc{{pick::x::y::z}}' },
    { name: 'pick-comma-list', input: '{{pick::a,b,c}}' },
    { name: 'pick-reroll-seed', input: '{{pick::x::y::z}}', pickRerollSeed: 'seed-42' },
    { name: 'pick-reroll-null', input: '{{pick::x::y::z}}', pickRerollSeed: null },
    { name: 'pick-empty', input: '[{{pick}}]' },
    { name: 'pick-content-hash', input: 'v1 {{pick::x::y::z}}' },
  ];
  return { cases: await runMacroCases(cases) };
} finally {
  st.thawClock();
}
