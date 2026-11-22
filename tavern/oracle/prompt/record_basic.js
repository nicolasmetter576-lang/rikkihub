// 基础路径：默认预设 normal、不同布局、names 行为、send_if_empty、quiet_prompt 注入
await st.reset();
try {
  // 默认预设（ST Default.json 的形状）：11 顺序项
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
  const cases = [
    { name: 'default-squash-on', type: 'normal', order: DEF_ORDER, chat: DEF_CHAT,
      options: { squash_system_messages: true } },
    { name: 'default-squash-off', type: 'normal', order: DEF_ORDER, chat: DEF_CHAT,
      options: { squash_system_messages: false } },
    { name: 'empty-chat', type: 'normal', order: DEF_ORDER, chat: [],
      options: { squash_system_messages: true } },
    { name: 'empty-card-fields', type: 'normal', order: DEF_ORDER, chat: DEF_CHAT,
      card: { description: '', personality: '', scenario: '', mesExamples: '' },
      options: { squash_system_messages: true } },
    { name: 'names-completion', type: 'normal', order: DEF_ORDER, chat: DEF_CHAT,
      options: { names_behavior: 1, squash_system_messages: true } },
    { name: 'names-content', type: 'normal', order: DEF_ORDER, chat: DEF_CHAT,
      options: { names_behavior: 2, squash_system_messages: true } },
    { name: 'names-none', type: 'normal', order: DEF_ORDER, chat: DEF_CHAT,
      options: { names_behavior: -1, squash_system_messages: true } },
    { name: 'send-if-empty-after-assistant', type: 'normal', order: DEF_ORDER,
      chat: [...DEF_CHAT, { role: 'assistant', name: 'Seraphina', text: 'Trailing off…' }],
      options: { send_if_empty: '…and then?', squash_system_messages: true } },
    { name: 'send-if-empty-after-user', type: 'normal', order: DEF_ORDER, chat: DEF_CHAT,
      options: { send_if_empty: '…and then?', squash_system_messages: true } },
    // impersonate 不走 script.js:4455 的 sendMessageAsUser，才会碰到 openai.js:931（末条是 assistant 才补一条假 user）
    { name: 'send-if-empty-impersonate-after-assistant', type: 'impersonate', order: DEF_ORDER,
      chat: [...DEF_CHAT, { role: 'assistant', name: 'Seraphina', text: 'Trailing off…' }],
      options: { send_if_empty: '…and then?', squash_system_messages: true } },
    { name: 'send-if-empty-impersonate-after-user', type: 'impersonate', order: DEF_ORDER, chat: DEF_CHAT,
      options: { send_if_empty: '…and then?', squash_system_messages: true } },
    { name: 'custom-text-prompt', type: 'normal', order: DEF_ORDER,
      prompts: undefined, // 用已有预设 + 自定义条目
      chat: DEF_CHAT, options: { squash_system_messages: true },
      custom: true },
    { name: 'quiet-type', type: 'quiet', order: DEF_ORDER, chat: DEF_CHAT,
      quietPrompt: 'Write a private note for {{char}}.',
      options: { squash_system_messages: true } },
    { name: 'quiet-relative', type: 'quiet', order: DEF_ORDER, chat: DEF_CHAT,
      quietPrompt: 'Secret instruction {{user}}.',
      options: { squash_system_messages: true },
      quietPromptRelative: true },
    // 隐藏的系统消息（is_system）：Generate 过滤，提示词里没有它
    { name: 'hidden-system-message-dropped', type: 'normal', order: DEF_ORDER,
      chat: [...DEF_CHAT, { role: 'system', name: 'Narrator', text: 'A hidden system line.' }],
      options: { squash_system_messages: true } },
    // 旁白（extra.type=narrator，openai.js:590）：以 system 角色进提示词；与 squash 的交互以录到的结果为准
    { name: 'narrator-message-in-chat', type: 'normal', order: DEF_ORDER,
      chat: [...DEF_CHAT, { role: 'narrator', name: 'Narrator', text: 'A narrator line.' }],
      options: { squash_system_messages: true } },
    { name: 'narrator-message-mid-chat', type: 'normal', order: DEF_ORDER,
      chat: [DEF_CHAT[0], { role: 'narrator', name: 'Narrator', text: 'Meanwhile…' }, DEF_CHAT[1], DEF_CHAT[2]],
      options: { squash_system_messages: true } },
    { name: 'new-chat-prompt-custom', type: 'normal', order: DEF_ORDER, chat: DEF_CHAT,
      options: { new_chat_prompt: '=== NEW SESSION ===', squash_system_messages: true } },
    { name: 'user-prompt-bias', type: 'normal', order: DEF_ORDER, chat: DEF_CHAT,
      powerUser: { user_prompt_bias: 'Remember: speak politely.' },
      options: { squash_system_messages: true } },
  ];

  // custom-text-prompt：加一个非 marker 的 relative user prompt 到 prompts 和 order
  const customPrompt = {
    identifier: 'myprompt', name: 'My Prompt', system_prompt: false, role: 'user',
    content: 'Custom relative prompt {{char}}.', marker: false,
    injection_position: 0, injection_depth: 4, injection_order: 50,
  };
  for (const c of cases) {
    if (c.custom) {
      c.prompts = [customPrompt];
      c.order = [...DEF_ORDER.slice(0, 7), { identifier: 'myprompt', enabled: true }, ...DEF_ORDER.slice(7)];
      delete c.custom;
    }
    if (c.quietPromptRelative) {
      // system_prompt:false 的 quietPrompt → userRelative → 相对注入 main 末尾
      c.prompts = [{ identifier: 'quietPrompt', name: 'Quiet Prompt', system_prompt: false, role: 'system',
        content: '', marker: false, injection_position: 0 }];
      delete c.quietPromptRelative;
    }
    if (!c.prompts) c.prompts = null; // null = 保留 oracle 现有预设
    if (!c.order) c.order = DEF_ORDER;
  }
  return await recordPromptCases(cases);
} finally {}
