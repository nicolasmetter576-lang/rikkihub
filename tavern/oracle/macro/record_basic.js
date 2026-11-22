// 基础宏：名字/角色卡/系统字段/utility/注释/未知宏/转义/legacy 前缀
await st.reset();
st.freezeClock('2025-03-04T05:06:07Z');
try {
  const cases = [
    { name: 'names-user', input: 'Hello {{user}}!' },
    { name: 'names-char', input: 'Hello {{char}}!' },
    { name: 'names-group-solo', input: '{{group}}|{{groupNotMuted}}|{{notChar}}' },
    { name: 'names-charIfNotGroup', input: '{{charIfNotGroup}}' },
    { name: 'case-insensitive', input: '{{USER}}|{{Char}}|{{DESCRIption}}' },
    { name: 'char-desc-personality-scenario', input: '{{description}}|{{personality}}|{{scenario}}' },
    { name: 'char-prompt-instruction-depth', input: '{{charPrompt}}|{{charInstruction}}|{{charDepthPrompt}}' },
    { name: 'char-creator-version-persona', input: '{{charCreatorNotes}}|{{charVersion}}|{{persona}}' },
    { name: 'char-first-message', input: '{{greeting}}|{{greeting::1}}|{{greeting::9}}' },
    { name: 'model', input: '{{model}}' },
    { name: 'maxPrompt-maxContext-maxResponse', input: '{{maxPrompt}}|{{maxContext}}|{{maxResponse}}' },
    { name: 'isMobile', input: '{{isMobile}}' },
    { name: 'hasExtension', input: '{{hasExtension::anything}}' },
    { name: 'lastGenerationType', input: '[{{lastGenerationType}}]' },
    { name: 'space-newline-noop', input: 'a{{space}}b{{newline}}c{{noop}}d' },
    { name: 'space-newline-count', input: 'a{{space::3}}b{{newline::2}}c' },
    { name: 'reverse', input: '{{reverse::hello}}' },
    { name: 'input', input: '{{input}}', input_text: 'typed into the box' },
    { name: 'comment-inline', input: 'a{{// a comment }}b' },
    { name: 'comment-scoped', input: 'a{{//}}this is\nmultiline{{///}}b' },
    { name: 'comment-alias', input: 'a{{comment::x}}b' },
    { name: 'trim-standalone', input: 'a\n{{trim}}\nb' },
    { name: 'trim-scoped', input: '{{trim}}  x\n  y  {{/trim}}' },
    { name: 'trim-scoped-nopreserve', input: '{{trim#}}  padded  {{/trim}}' },
    { name: 'unknown-macro', input: 'a{{mystery::1::2}}b' },
    { name: 'unknown-macro-nested', input: '{{mystery::{{char}}}}' },
    { name: 'unmatched-closing', input: 'a{{/if}}b' },
    { name: 'unmatched-open', input: 'a{{if true}}b' },
    { name: 'escape-braces', input: 'a\\{{char}}b' },
    { name: 'legacy-user', input: '<USER> met <BOT> aka <CHAR> in <GROUP>' },
    { name: 'legacy-time-utc', input: '{{time_UTC+2}}' },
    { name: 'multi-macro-one-string', input: '{{char}} says "hi" to {{user}} at {{isodate}}.' },
    { name: 'empty-string', input: '' },
    { name: 'original-first-second', input: '{{original}}-{{original}}', original: 'RAW_ORIGINAL' },
    { name: 'outlet', input: '{{outlet::achievements}}|{{outlet::missing}}', outlet: { achievements: 'ACH-CONTENT' } },
    { name: 'lastMessage-chain', input: '{{lastMessage}}|{{lastMessageId}}|{{lastUserMessage}}|{{lastCharMessage}}|{{allChatRange}}',
      chat: [
        { name: 'Seraphina', text: 'greeting', sendDateMillis: 1741000000000 },
        { name: 'User', text: 'hi there', isUser: true, sendDateMillis: 1741000100000 },
        { name: 'Seraphina', text: 'hello', sendDateMillis: 1741000200000 },
      ] },
    { name: 'lastSwipe-current', input: '{{lastSwipeId}}|{{currentSwipeId}}',
      chat: [{ name: 'Seraphina', text: 'g', swipeId: 2, swipeCount: 3 }] },
    { name: 'firstIncluded-firstDisplayed', input: '[{{firstIncludedMessageId}}]|[{{firstDisplayedMessageId}}]',
      chatMeta: { lastInContextMessageId: 5 } },
    { name: 'mesExamples', input: '[{{mesExamples}}]' },
    { name: 'mesExamplesRaw', input: '{{mesExamplesRaw}}' },
  ];
  return { cases: await runMacroCases(cases) };
} finally {
  st.thawClock();
}
