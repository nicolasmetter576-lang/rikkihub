// 标记/排序/绝对注入/injection_trigger 主题。
//   disable-<id>      逐个关闭 marker（main/nsfw/jailbreak/charDescription/charPersonality/scenario/dialogueExamples/chatHistory）
//   reorder-*         chatHistory 提前、main 放到最后
//   abs-*             绝对位置条目（injection_position=1）：depth 0/1/2/越界/空聊天，同深度不同 role、不同 order、同 order 同 role
//   trigger-*         injection_trigger 与生成类型的交互（normal/continue/impersonate、多类型命中与未命中、空数组、绝对条目）
await st.reset();
try {
  const DEF_ORDER = [
    { identifier: 'main', enabled: true }, { identifier: 'worldInfoBefore', enabled: true },
    { identifier: 'charDescription', enabled: true }, { identifier: 'charPersonality', enabled: true },
    { identifier: 'scenario', enabled: true }, { identifier: 'enhanceDefinitions', enabled: false },
    { identifier: 'nsfw', enabled: true }, { identifier: 'worldInfoAfter', enabled: true },
    { identifier: 'dialogueExamples', enabled: true }, { identifier: 'chatHistory', enabled: true },
    { identifier: 'jailbreak', enabled: true },
  ];
  const DEF_CHAT = [
    { role: 'user', name: 'User', text: 'Hi {{char}}!' },
    { role: 'assistant', name: 'Seraphina', text: 'Hello {{user}}!' },
    { role: 'user', name: 'User', text: 'How are you?' },
  ];
  const ENDS_ASSISTANT = [...DEF_CHAT, { role: 'assistant', name: 'Seraphina', text: 'I am well, and' }];
  const OPT = { squash_system_messages: true };
  const BASE = structuredClone(oa.oai_settings.prompts);

  const off = (id) => DEF_ORDER.map(o => (o.identifier === id ? { ...o, enabled: false } : o));
  const abs = (identifier, role, depth, order, content) => ({
    identifier, name: identifier, system_prompt: false, role, content, marker: false,
    injection_position: 1, injection_depth: depth, injection_order: order,
  });
  const rel = (identifier, role, content, trigger) => ({
    identifier, name: identifier, system_prompt: false, role, content, marker: false,
    injection_position: 0, injection_depth: 4, injection_order: 100, injection_trigger: trigger,
  });
  // 在现有预设上追加条目，并放进 order（放在 chatHistory 之前，jailbreak 之后不影响绝对条目）
  const plus = (extra, order = DEF_ORDER) => ({
    prompts: [...BASE, ...extra],
    order: [...order, ...extra.map(p => ({ identifier: p.identifier, enabled: true }))],
  });
  const mk = (name, type, chat, preset = {}, more = {}) => ({
    name, type, chat, options: OPT, order: DEF_ORDER, prompts: null, ...preset, ...more,
  });

  const cases = [];
  for (const id of ['main', 'nsfw', 'jailbreak', 'charDescription', 'charPersonality', 'scenario', 'dialogueExamples', 'chatHistory']) {
    cases.push(mk('disable-' + id, 'normal', DEF_CHAT, { order: off(id) }));
  }
  cases.push(mk('reorder-history-first', 'normal', DEF_CHAT, {
    order: [DEF_ORDER[9], ...DEF_ORDER.slice(0, 9), DEF_ORDER[10]],
  }));
  cases.push(mk('reorder-main-last', 'normal', DEF_CHAT, {
    order: [...DEF_ORDER.slice(1), DEF_ORDER[0]],
  }));

  cases.push(mk('abs-depth0-user', 'normal', DEF_CHAT, plus([abs('a0', 'user', 0, 100, 'ABS depth0 user')])));
  cases.push(mk('abs-depth1-assistant', 'normal', DEF_CHAT, plus([abs('a1', 'assistant', 1, 100, 'ABS depth1 assistant')])));
  cases.push(mk('abs-depth2-system', 'normal', DEF_CHAT, plus([abs('a2', 'system', 2, 100, 'ABS depth2 system')])));
  cases.push(mk('abs-same-depth-diff-role', 'normal', DEF_CHAT, plus([
    abs('r1', 'assistant', 1, 100, 'ABS-ASSISTANT'), abs('r2', 'user', 1, 100, 'ABS-USER'), abs('r3', 'system', 1, 100, 'ABS-SYSTEM'),
  ])));
  cases.push(mk('abs-same-depth-diff-order', 'normal', DEF_CHAT, plus([
    abs('o1', 'user', 1, 10, 'ORDER-10'), abs('o2', 'user', 1, 90, 'ORDER-90'), abs('o3', 'user', 1, 50, 'ORDER-50'),
  ])));
  cases.push(mk('abs-same-order-same-role', 'normal', DEF_CHAT, plus([
    abs('s1', 'system', 1, 100, 'SAME-A'), abs('s2', 'system', 1, 100, 'SAME-B'),
  ])));
  cases.push(mk('abs-depth-beyond-chat', 'normal', DEF_CHAT, plus([abs('b1', 'user', 10, 100, 'ABS depth10')])));
  cases.push(mk('abs-empty-chat-depth0', 'normal', [], plus([abs('e1', 'user', 0, 100, 'ABS on empty chat')])));
  cases.push(mk('abs-depth0-and-1', 'normal', DEF_CHAT, plus([
    abs('d1', 'user', 0, 100, 'D0-USER'), abs('d2', 'assistant', 1, 100, 'D1-ASSISTANT'),
  ])));

  cases.push(mk('trigger-normal-on-normal', 'normal', DEF_CHAT, plus([rel('tn', 'system', 'ONLY-NORMAL', ['normal'])])));
  cases.push(mk('trigger-continue-on-normal', 'normal', DEF_CHAT, plus([rel('tc', 'system', 'ONLY-CONTINUE', ['continue'])])));
  cases.push(mk('trigger-continue-on-continue', 'continue', ENDS_ASSISTANT, plus([rel('tc', 'system', 'ONLY-CONTINUE', ['continue'])])));
  cases.push(mk('trigger-impersonate-on-impersonate', 'impersonate', DEF_CHAT, plus([rel('ti', 'system', 'ONLY-IMPERSONATE', ['impersonate'])])));
  // 数组里有多个类型：命中其中之一即可（regenerate/swipe 的真发送路径另有门道，放在 gentype 主题里查）
  cases.push(mk('trigger-multi-on-impersonate', 'impersonate', DEF_CHAT, plus([rel('tm', 'system', 'CONTINUE-OR-IMPERSONATE', ['continue', 'impersonate'])])));
  cases.push(mk('trigger-multi-miss', 'normal', DEF_CHAT, plus([rel('tx', 'system', 'CONTINUE-OR-IMPERSONATE', ['continue', 'impersonate'])])));
  // swipe 在组装前去掉最后一条（chat 末条是 assistant）；regenerate 的 chat 末条必须是 user（见 lib.js 的说明）
  cases.push(mk('trigger-swipe-on-swipe', 'swipe', ENDS_ASSISTANT, plus([rel('ts', 'system', 'ONLY-SWIPE', ['swipe'])])));
  cases.push(mk('trigger-swipe-on-normal', 'normal', DEF_CHAT, plus([rel('tt', 'system', 'ONLY-SWIPE', ['swipe'])])));
  cases.push(mk('trigger-regenerate-on-regenerate', 'regenerate', DEF_CHAT, plus([rel('tr', 'system', 'ONLY-REGENERATE', ['regenerate'])])));
  cases.push(mk('trigger-multi-on-swipe', 'swipe', ENDS_ASSISTANT, plus([rel('tu', 'system', 'SWIPE-OR-REGEN', ['swipe', 'regenerate'])])));
  cases.push(mk('trigger-empty-always', 'normal', DEF_CHAT, plus([rel('te', 'system', 'ALWAYS', [])])));
  cases.push(mk('trigger-on-absolute', 'normal', DEF_CHAT, plus([
    { ...abs('ta', 'user', 1, 100, 'ABS-ONLY-CONTINUE'), injection_trigger: ['continue'] },
  ])));

  return await recordPromptCases(cases);
} finally {}
