const cases = [
  { name: 'smoke-basic', worlds: { 'wiq-a': book([
      { key: ['dragon'], content: 'Dragons breathe fire.', order: 10 },
      { key: [], constant: true, content: 'ALWAYS-ON lore.', order: 5, position: 1 },
      { key: ['castle'], keysecondary: ['king'], content: 'The castle has a king {{addvar::wtrace::c;}}', order: 20 },
    ]) }, global: ['wiq-a'],
    chat: turn('A:Hello', 'U:The dragon sleeps in the castle with the king.'),
  },
  { name: 'smoke-dry', worlds: { 'wiq-a': book([{ key: ['dragon'], content: 'D', probability: 50 }]) }, global: ['wiq-a'], dryRun: true, random: [0.1],
    chat: turn('U:dragon') },
];
return await recordCases(cases);
