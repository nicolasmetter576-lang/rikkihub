// 作者注释与 persona 描述主题。
//   an-*       作者注释：position 0/1/2（IN_PROMPT/IN_CHAT/BEFORE_PROMPT）、depth、role、interval（含 0 与整除/不整除）、空内容、宏、
//              角色卡级笔记 chara（useChara 开/关、position）
//   persona-*  persona_description_position 0/1/2/3/4/9 与深度/角色；与作者注释同时存在时 TOP/BOTTOM_AN 的拼接
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
  const CHAT3 = [U('Hi {{char}}!'), A('Hello {{user}}!'), U('How are you?')];
  const CHAT4 = [...CHAT3, A('Well, and you?')];
  const CHAT6 = [...CHAT4, U('Fine.'), A('Good to hear.')];
  const OPT = { squash_system_messages: true };
  const mk = (name, extra = {}) => ({ name, type: 'normal', chat: CHAT3, options: OPT, order: DEF_ORDER, prompts: null, ...extra });
  const an = (o) => ({ authorsNote: { prompt: 'AN-TEXT', interval: 1, position: 1, depth: 1, role: 0, ...o } });
  const persona = (position, extra = {}) => ({
    powerUser: { persona_description: 'PERSONA: a wandering scholar.', persona_description_position: position,
      persona_description_depth: 2, persona_description_role: 0, ...extra },
  });

  const cases = [
    mk('an-in-chat-depth1-system', an({})),
    mk('an-in-chat-depth0-user', an({ depth: 0, role: 1 })),
    mk('an-in-chat-depth2-assistant', an({ depth: 2, role: 2 })),
    mk('an-in-chat-depth0-system', an({ depth: 0, role: 0 })),
    mk('an-in-chat-depth-beyond', an({ depth: 9 })),
    mk('an-in-prompt', an({ position: 0 })),
    mk('an-before-prompt', an({ position: 2 })),
    mk('an-interval-2-len3', { ...an({ interval: 2 }), chat: CHAT3 }),
    mk('an-interval-2-len4', { ...an({ interval: 2 }), chat: CHAT4 }),
    mk('an-interval-3-len6', { ...an({ interval: 3 }), chat: CHAT6 }),
    mk('an-interval-0', an({ interval: 0 })),
    mk('an-empty-prompt', an({ prompt: '' })),
    mk('an-macro', an({ prompt: 'Keep {{char}} in character for {{user}}.' })),
    mk('an-chara-use', an({ chara: { prompt: 'CHARA-NOTE', useChara: true, position: 0 } })),
    mk('an-chara-use-after', an({ chara: { prompt: 'CHARA-NOTE', useChara: true, position: 1 } })),
    mk('an-chara-disabled', an({ chara: { prompt: 'CHARA-NOTE', useChara: false, position: 0 } })),

    mk('persona-in-prompt', persona(0)),
    mk('persona-after-char', persona(1)),
    mk('persona-top-an-with-an', { ...persona(2), ...an({}) }),
    mk('persona-bottom-an-with-an', { ...persona(3), ...an({}) }),
    mk('persona-top-an-no-an', persona(2)),
    mk('persona-at-depth-system', persona(4)),
    mk('persona-at-depth-user', persona(4, { persona_description_role: 1, persona_description_depth: 1 })),
    mk('persona-none', persona(9)),
    mk('persona-empty', persona(0, { persona_description: '' })),
  ];
  return await recordPromptCases(cases);
} finally {}
