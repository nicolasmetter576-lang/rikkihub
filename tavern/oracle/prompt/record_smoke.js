// 冒烟：默认预设 + 两条历史
await st.reset();
try {
  const cases = [
    {
      name: 'smoke-default',
      type: 'normal',
      chat: [
        { role: 'user', name: 'User', text: 'Hi {{char}}!' },
        { role: 'assistant', name: 'Seraphina', text: 'Hello {{user}}!' },
      ],
    },
  ];
  return await recordPromptCases(cases);
} finally {
  // 状态由 runPromptCase 内部恢复
}
