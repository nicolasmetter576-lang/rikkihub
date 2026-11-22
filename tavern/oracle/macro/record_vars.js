// 变量宏全家：18 个注册宏 + 别名 + 简写操作符 + 对象/键路径 + 终态断言
await st.reset();
try {
  const cases = [
    { name: 'setvar-getvar', input: '{{setvar::a::foo}}{{getvar::a}}', varsAfter: true },
    { name: 'getvar-missing', input: '[{{getvar::nope}}]' },
    { name: 'addvar-numbers', input: '{{setvar::n::1}}{{addvar::n::2}}{{getvar::n}}', varsAfter: true },
    { name: 'addvar-append', input: '{{setvar::s::ab}}{{addvar::s::cd}}{{getvar::s}}', varsAfter: true },
    { name: 'incdecvar', input: '{{setvar::i::5}}{{incvar::i}}|{{decvar::i}}', varsAfter: true },
    { name: 'hasvar', input: '{{setvar::x::1}}{{hasvar::x}}|{{hasvar::y}}', varsAfter: true },
    { name: 'deletevar', input: '{{setvar::d::1}}{{deletevar::d}}{{hasvar::d}}', varsAfter: true },
    { name: 'setgetvarkey', input: '{{setvarkey::o::k::v}}{{getvarkey::o::k}}', varsAfter: true },
    { name: 'setvarkey-index', input: '{{setvarkey::arr::0::a}}{{setvarkey::arr::2::c}}{{getvar::arr}}', varsAfter: true },
    { name: 'getvar-object', input: '{{getvar::obj}}', localVars: { obj: { a: 1, b: 'x' } } },
    { name: 'getvarkey-nested', input: '{{getvarkey::obj::a}}', localVars: { obj: { a: 42 } } },
    { name: 'global-basic', input: '{{setglobalvar::g::G}}{{getglobalvar::g}}|{{hasglobalvar::g}}', varsAfter: true },
    { name: 'global-add-inc-dec', input: '{{setglobalvar::gi::10}}{{addglobalvar::gi::5}}{{incglobalvar::gi}}|{{decglobalvar::gi}}', varsAfter: true },
    { name: 'deleteglobalvar', input: '{{setglobalvar::gd::1}}{{deleteglobalvar::gd}}{{hasglobalvar::gd}}', varsAfter: true },
    { name: 'setgetglobalvarkey', input: '{{setglobalvarkey::go::k::v}}{{getglobalvarkey::go::k}}', varsAfter: true },
    { name: 'aliases', input: '{{setvar::al::1}}{{varexists::al}}|{{setvarkey::al2::k::1}}{{getvarindex::al2::k}}|{{globalvarexists::al}}|{{flushvar::al}}{{varexists::al}}|{{flushglobalvar::al2}}', varsAfter: true },
    { name: 'shorthand-get-set', input: '{{.x=hello}}{{ .x }}', varsAfter: true },
    { name: 'shorthand-global', input: '{{$gx=world}}{{$gx}}', varsAfter: true },
    { name: 'shorthand-inc-dec', input: '{{.c=10}}{{.c++}}|{{.c--}}', varsAfter: true },
    { name: 'shorthand-add-sub', input: '{{.v=7}}{{.v+=3}}{{.v}}|{{.v-=2}}{{.v}}', varsAfter: true },
    { name: 'shorthand-or', input: '{{.empty || "dflt"}}|{{.setval=ok}}{{.setval || "dflt"}}', varsAfter: true },
    { name: 'shorthand-nullish', input: '{{.missing ?? "fb"}}|{{.zero=0}}{{.zero ?? "fb"}}', varsAfter: true },
    { name: 'shorthand-or-assign', input: '{{.oa ||= "new"}}|{{.oa}}', varsAfter: true },
    { name: 'shorthand-nullish-assign', input: '{{.na ??= "new"}}{{.na}}', varsAfter: true },
    { name: 'shorthand-compare', input: '{{.n=5}}{{.n==5}}|{{.n!=3}}|{{.n>4}}|{{.n>=5}}|{{.n<6}}|{{.n<=4}}', varsAfter: true },
    { name: 'shorthand-in-macro-arg', input: '{{reverse::{{.x}}}}', localVars: { x: 'abc' } },
    { name: 'var-value-with-macro', input: '{{setvar::m::{{user}}X}}{{getvar::m}}', varsAfter: true },
    { name: 'local-global-isolated', input: '{{setvar::k::local}}{{setglobalvar::k::global}}{{getvar::k}}|{{getglobalvar::k}}', varsAfter: true },
    { name: 'side-effect-order', input: '{{setvar::seq::1}}[{{getvar::seq}}]{{setvar::seq::2}}[{{getvar::seq}}]', varsAfter: true },
  ];
  return { cases: await runMacroCases(cases) };
} finally {}
