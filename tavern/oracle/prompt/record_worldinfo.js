// 世界书结果在提示词里的落点（输入是 getWorldInfoPrompt 的返回值，不依赖世界书引擎的实现）。
//   wi-before/after        position 0/1，多条按 order，wi_format 模板
//   wi-an-top/bottom       position 2/3 与作者注释的拼接（有/无作者注释）
//   wi-at-depth-*          position 4：深度、三种 role、同深度多 role
//   wi-em-top/bottom       position 5/6：示例对话之前/之后（有/无示例）
//   wi-outlet              position 7：经 {{outlet::名}} 宏落到 main 提示词里
//   wi-constant / wi-none  常驻条目、无匹配
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
  const U = (t) => ({ role: 'user', name: 'User', text: t });
  const A = (t) => ({ role: 'assistant', name: 'Seraphina', text: t });
  const CHAT = [U('Tell me about the dragon.'), A('The dragon sleeps in the castle.'), U('Where exactly?')];
  const OPT = { squash_system_messages: true };
  const BASE = structuredClone(oa.oai_settings.prompts);

  const entry = (position, content, extra = {}) => ({
    key: ['dragon'], keysecondary: [], content, comment: 'e', position, role: 0, depth: 4, order: 100,
    constant: false, disable: false, selective: false, ...extra,
  });
  const world = (...entries) => ({ worlds: { 'wip-book': { entries: Object.fromEntries(entries.map((e, i) => [i, e])) } }, global: ['wip-book'] });
  const mk = (name, w, extra = {}) => ({ name, type: 'normal', chat: CHAT, options: OPT, order: DEF_ORDER, prompts: null, ...w, ...extra });
  const AN = { authorsNote: { prompt: 'AN-TEXT', interval: 1, position: 1, depth: 1, role: 0 } };

  const cases = [
    mk('wi-before', world(entry(0, 'LORE-BEFORE'))),
    mk('wi-after', world(entry(1, 'LORE-AFTER'))),
    mk('wi-before-and-after', world(entry(0, 'LORE-BEFORE'), entry(1, 'LORE-AFTER'))),
    mk('wi-before-order', world(entry(0, 'ORDER-10', { order: 10 }), entry(0, 'ORDER-50', { order: 50 }), entry(0, 'ORDER-30', { order: 30 }))),
    mk('wi-format-custom', world(entry(0, 'LORE-BEFORE'), entry(1, 'LORE-AFTER')), { options: { ...OPT, wi_format: '<lore>\n{0}\n</lore>' } }),
    mk('wi-an-top-with-an', { ...world(entry(2, 'LORE-AN-TOP')), ...AN }),
    mk('wi-an-bottom-with-an', { ...world(entry(3, 'LORE-AN-BOTTOM')), ...AN }),
    mk('wi-an-top-no-an', world(entry(2, 'LORE-AN-TOP'))),
    mk('wi-an-both-with-an', { ...world(entry(2, 'LORE-AN-TOP'), entry(3, 'LORE-AN-BOTTOM')), ...AN }),
    mk('wi-at-depth-system', world(entry(4, 'LORE-D1-SYS', { depth: 1, role: 0 }))),
    mk('wi-at-depth-user', world(entry(4, 'LORE-D0-USER', { depth: 0, role: 1 }))),
    mk('wi-at-depth-assistant', world(entry(4, 'LORE-D2-ASSISTANT', { depth: 2, role: 2 }))),
    mk('wi-at-depth-multi-role', world(
      entry(4, 'LORE-SYS', { depth: 1, role: 0 }), entry(4, 'LORE-USER', { depth: 1, role: 1 }), entry(4, 'LORE-ASSISTANT', { depth: 1, role: 2 }),
    )),
    mk('wi-at-depth-same-role-order', world(
      entry(4, 'LORE-A-10', { depth: 1, role: 1, order: 10 }), entry(4, 'LORE-B-90', { depth: 1, role: 1, order: 90 }),
    )),
    mk('wi-at-depth-with-an-same-depth', { ...world(entry(4, 'LORE-D1', { depth: 1, role: 0 })), ...AN }),
    mk('wi-em-top', world(entry(5, 'LORE-EM-TOP'))),
    mk('wi-em-bottom', world(entry(6, 'LORE-EM-BOTTOM'))),
    mk('wi-em-top-and-bottom', world(entry(5, 'LORE-EM-TOP'), entry(6, 'LORE-EM-BOTTOM'))),
    mk('wi-em-no-examples', world(entry(5, 'LORE-EM-TOP'), entry(6, 'LORE-EM-BOTTOM')), { card: { mesExamples: '' } }),
    mk('wi-outlet', world(entry(7, 'LORE-OUTLET', { outletName: 'lore' })), {
      prompts: BASE.map(p => (p.identifier === 'main' ? { ...p, content: p.content + '\nOutlet says: {{outlet::lore}}' } : p)),
    }),
    mk('wi-constant', world(entry(0, 'LORE-ALWAYS', { key: [], constant: true }))),
    mk('wi-none', world(entry(0, 'LORE-NEVER', { key: ['unicorn'] }))),
    mk('wi-all-positions', { ...world(
      entry(0, 'P0'), entry(1, 'P1'), entry(2, 'P2'), entry(3, 'P3'), entry(4, 'P4', { depth: 1, role: 0 }), entry(5, 'P5'), entry(6, 'P6'),
    ), ...AN }),
  ];
  return await recordPromptCases(cases);
} finally {}
