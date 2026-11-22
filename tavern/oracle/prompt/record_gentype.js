// 生成类型、示例对话、格式模板、名字清洗、token 预算。
//   continue-*        续写：nudge（默认/自定义）、continue_prefill（含 continue_postfix）、末条为 user 的情形
//   impersonate-*     代写：默认/自定义 impersonation_prompt、历史末条为 assistant
//   examples-*        示例对话：pin_examples / strip_examples、多段 <START>、自定义 new_example_chat_prompt、无示例
//   format-*          scenario_format / personality_format 模板
//   names-*           names_behavior=completion 时名字清洗（空格、符号）
//   budget-*          同一份长聊天、同一份示例，逐步缩小 openai_max_context，覆盖“刚好装下/装不下/一条都装不下”的各档
//   swipe-* / regenerate-*  swipe 组装前去掉末条；regenerate 只录末条是 user 的形态（见 lib.js 的说明）
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
  const U = (t, n = 'User') => ({ role: 'user', name: n, text: t });
  const A = (t, n = 'Seraphina') => ({ role: 'assistant', name: n, text: t });
  const CHAT3 = [U('Hi {{char}}!'), A('Hello {{user}}!'), U('How are you?')];
  const ENDS_A = [...CHAT3, A('I am well, and')];
  const OPT = { squash_system_messages: true };
  const mk = (name, type, chat, extra = {}) => ({ name, type, chat, options: OPT, order: DEF_ORDER, prompts: null, ...extra });
  const o = (more) => ({ options: { ...OPT, ...more } });

  const EX2 = '<START>\n{{user}}: Hello\n{{char}}: Hi there.\n<START>\n{{user}}: Bye\n{{char}}: Farewell.';
  const LONG = [];
  for (let i = 0; i < 24; i++) {
    LONG.push(U(`Message number ${i} from the user, asking about the forest and its many hidden paths.`));
    LONG.push(A(`Reply number ${i} from the character, describing the forest and its many hidden paths in detail.`));
  }
  LONG.push(U('And the last question?'));

  const cases = [
    mk('continue-default', 'continue', ENDS_A),
    mk('continue-custom-nudge', 'continue', ENDS_A, o({ continue_nudge_prompt: '[Go on from: {{lastChatMessage}}]' })),
    mk('continue-prefill', 'continue', ENDS_A, o({ continue_prefill: true })),
    mk('continue-prefill-postfix', 'continue', ENDS_A, o({ continue_prefill: true, continue_postfix: '\n\n' })),
    mk('continue-after-user', 'continue', CHAT3),
    mk('swipe-basic', 'swipe', ENDS_A),
    mk('swipe-pops-only-last', 'swipe', [...ENDS_A, U('And then?'), A('Then it ended.')]),
    mk('regenerate-after-user', 'regenerate', CHAT3),
    mk('impersonate-default', 'impersonate', CHAT3),
    mk('impersonate-custom-prompt', 'impersonate', CHAT3, o({ impersonation_prompt: '[Speak as {{user}} now, in first person.]' })),
    mk('impersonate-after-assistant', 'impersonate', ENDS_A),
    mk('impersonate-empty-chat', 'impersonate', []),

    mk('examples-multi-start', 'normal', CHAT3, { card: { mesExamples: EX2 } }),
    mk('examples-none', 'normal', CHAT3, { card: { mesExamples: '' } }),
    mk('examples-pin', 'normal', CHAT3, { powerUser: { pin_examples: true }, card: { mesExamples: EX2 } }),
    mk('examples-strip', 'normal', CHAT3, { powerUser: { strip_examples: true }, card: { mesExamples: EX2 } }),
    mk('examples-custom-heading', 'normal', CHAT3, { ...o({ new_example_chat_prompt: '=== EXAMPLES ===' }), card: { mesExamples: EX2 } }),
    mk('examples-no-start-marker', 'normal', CHAT3, { card: { mesExamples: '{{user}}: Plain line\n{{char}}: Plain reply' } }),

    mk('format-scenario', 'normal', CHAT3, o({ scenario_format: 'Scenario => {{scenario}}' })),
    mk('format-personality', 'normal', CHAT3, o({ personality_format: 'Persona => {{personality}}' })),
    mk('format-both', 'normal', CHAT3, o({ scenario_format: 'S: {{scenario}}', personality_format: 'P: {{personality}}' })),

    mk('names-completion-spaces', 'normal', [U('Hi', 'Dr. Who'), A('Hello', 'Seraphina the Wise'), U('Again', 'Dr. Who')], o({ names_behavior: 1 })),
    mk('names-completion-symbols', 'normal', [U('Hi', 'a<b>c'), A('Hello', '名字'), U('Again', 'ok_name-1')], o({ names_behavior: 1 })),
    mk('names-content-multi', 'normal', [U('Hi', 'Dr. Who'), A('Hello', 'Seraphina')], o({ names_behavior: 2 })),
  ];

  // 预算扫描：LONG 聊天 + 示例，最大回复 100，逐步缩小上下文
  for (const ctxSize of [4095, 2000, 1500, 1200, 1000, 900, 800, 700, 600, 500, 450, 400]) {
    cases.push(mk('budget-' + ctxSize, 'normal', LONG, { ...o({ openai_max_context: ctxSize, openai_max_tokens: 100 }), card: { mesExamples: EX2 } }));
  }
  cases.push(mk('budget-pin-900', 'normal', LONG, { ...o({ openai_max_context: 900, openai_max_tokens: 100 }), powerUser: { pin_examples: true }, card: { mesExamples: EX2 } }));
  // continue 要读最后一条消息对应的页面元素（script.js），直接塞进 ctx.chat 的长聊天没有渲染，所以这里用 4 条 + 小上下文
  for (const ctxSize of [900, 700, 550]) {
    cases.push(mk('budget-continue-' + ctxSize, 'continue', ENDS_A, { ...o({ openai_max_context: ctxSize, openai_max_tokens: 100 }), card: { mesExamples: EX2 } }));
  }

  return await recordPromptCases(cases);
} finally {}
