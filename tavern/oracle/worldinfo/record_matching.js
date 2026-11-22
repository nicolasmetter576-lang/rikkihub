// 主题：关键词匹配、扫描缓冲、选择逻辑、装饰器、触发器、角色过滤、全局扫描数据。
// 覆盖（正例/反例）：
//   明文：命中/未命中、多关键词任一、默认忽略大小写、全局区分大小写、条目覆盖(true/false)、
//   整词：全局开/条目覆盖、标点边界、下划线不是边界、多词关键词退化为子串、非 ASCII 邻字符(é/汉字)、关键词含正则元字符
//   正则：/模式/标志 命中/未命中、忽略全局大小写、覆盖整词、转义斜杠、未转义斜杠/语法错误/未知标志当明文、g 标志
//   关键词预处理：trim、空串、宏替换
//   缓冲：scanDepth 设置/条目覆盖(含 0)、包含名字开/关、空消息、扩展注入文本
//   逻辑：AND_ANY / NOT_ALL / NOT_ANY / AND_ALL 各自命中与未命中、selective=false、次要关键词为空
//   常驻、禁用、无关键词
//   全局扫描数据六种 match* 各自开/关
//   装饰器：@@activate、@@dont_activate、内容剥离、@@@ 转义、未知装饰器回退、只有装饰器、带尾随文本
//   生成类型触发器；角色过滤(名字/标签、包含/排除、无标签表项)
const E = (key, content, o = {}) => ({ key: Array.isArray(key) ? key : [key], content, ...o });
const W = (...es) => ({ 'wiq-m': book(es) });
const M = (name, entries, chat, extra = {}) => ({ name, worlds: W(...entries), global: ['wiq-m'], chat, ...extra });

const cases = [];
const add = (...c) => cases.push(...c);

// --- 明文
add(
  M('plain-hit', [E('dragon', 'D')], turn('A:Hello', 'U:A dragon appears.')),
  M('plain-miss', [E('dragon', 'D')], turn('A:Hello', 'U:Nothing here.')),
  M('plain-any-of-keys', [E(['wyvern', 'dragon'], 'D')], turn('U:the dragon')),
  M('case-insensitive-default', [E('Dragon', 'D')], turn('U:the DRAGON')),
  M('case-global-sensitive-miss', [E('Dragon', 'D')], turn('U:the dragon'), { settings: { world_info_case_sensitive: true } }),
  M('case-global-sensitive-hit', [E('Dragon', 'D')], turn('U:the Dragon'), { settings: { world_info_case_sensitive: true } }),
  M('case-entry-sensitive-over-global-off', [E('Dragon', 'D', { caseSensitive: true }), E('wyrm', 'W', { caseSensitive: true })], turn('U:the dragon, a WYRM')),
  M('case-entry-insensitive-over-global-on', [E('Dragon', 'D', { caseSensitive: false })], turn('U:the dragon'), { settings: { world_info_case_sensitive: true } }),
);

// --- 整词
const WW = { settings: { world_info_match_whole_words: true } };
add(
  M('wholeword-miss-substring', [E('cat', 'C')], turn('U:concatenate'), WW),
  M('wholeword-hit', [E('cat', 'C')], turn('U:the cat sat'), WW),
  M('wholeword-punctuation-boundary', [E('cat', 'C')], turn('U:"cat," he said'), WW),
  M('wholeword-underscore-is-word-char', [E('cat', 'C')], turn('U:cat_dog'), WW),
  M('wholeword-digit-is-word-char', [E('cat', 'C')], turn('U:cat2'), WW),
  M('wholeword-entry-off-over-global', [E('cat', 'C', { matchWholeWords: false })], turn('U:concatenate'), WW),
  M('wholeword-entry-on-over-global-off', [E('cat', 'C', { matchWholeWords: true })], turn('U:concatenate')),
  M('wholeword-multiword-key-is-substring', [E('big cat', 'C')], turn('U:a abig cat'), WW),
  M('wholeword-nonascii-neighbour', [E('cat', 'C')], turn('U:écat'), WW),
  M('wholeword-cjk', [E('龙', 'L')], turn('U:巨龙出现'), WW),
  M('wholeword-special-chars', [E('c++', 'P')], turn('U:I like c++ a lot'), WW),
  M('wholeword-key-at-buffer-edges', [E('hello', 'H')], turn('U:hello'), { ...WW, settings: { world_info_match_whole_words: true, world_info_include_names: false } }),
);

// --- 正则关键词
add(
  M('regex-hit-with-flag-i', [E('/dra(g|k)on/i', 'R')], turn('U:DRAKON')),
  M('regex-miss', [E('/dra(g|k)on/i', 'R')], turn('U:dracon')),
  M('regex-ignores-global-case-off', [E('/dragon/', 'R')], turn('U:DRAGON')),
  M('regex-overrides-wholeword', [E('/cat/', 'R')], turn('U:concatenate'), WW),
  M('regex-escaped-slash', [E('/a\\/b/', 'R')], turn('U:a/b')),
  M('regex-unescaped-slash-is-plain', [E('/a/b/i', 'R')], turn('U:see /a/b/i here')),
  M('regex-unescaped-slash-no-match-literal', [E('/a/b/i', 'R')], turn('U:a/b')),
  M('regex-syntax-error-is-plain-hit', [E('/(/', 'R')], turn('U:foo /(/ bar')),
  M('regex-syntax-error-is-plain-miss', [E('/(/', 'R')], turn('U:foo bar')),
  M('regex-unknown-flag-is-plain', [E('/abc/x', 'R')], turn('U:abc')),
  M('regex-g-flag', [E('/dragon/g', 'R')], turn('U:dragon dragon')),
  M('regex-needs-nonempty-pattern', [E('//', 'R')], turn('U:// here')),
  M('regex-multiline-anchor', [E('/^User: hi$/m', 'R')], turn('U:hi')),
  M('regex-lookbehind-and-unicode', [E('/(?<=\\s)\\p{Lu}rag/u', 'R')], turn('U:big Drag')),
  M('regex-dot-matches-newline-s', [E('/a.b/s', 'R')], turn('U:a\nb')),
  M('regex-and-plain-keys-mixed', [E(['/zzz/', 'dragon'], 'R')], turn('U:dragon')),
);

// --- 关键词预处理
add(
  M('key-trimmed', [E('  dragon  ', 'D')], turn('U:dragon')),
  M('key-empty-string-never-matches', [E(['', 'dragon'], 'D'), E([''], 'E')], turn('U:dragon')),
  M('key-whitespace-only-matches-everything', [E('   ', 'D')], turn('U:x')),
  M('key-macro-char', [E('{{char}}', 'C')], turn('A:hi')),
  M('key-macro-user-not-found', [E('{{user}}zzz', 'C')], turn('A:hi')),
);

// --- 缓冲：深度、名字、空消息、注入
const long = turn('A:alpha one', 'U:beta two', 'A:gamma three', 'U:delta four');
add(
  M('depth-1-only-newest', [E('delta', 'D'), E('gamma', 'G')], long, { settings: { world_info_depth: 1 } }),
  M('depth-3', [E('delta', 'D'), E('gamma', 'G'), E('beta', 'B'), E('alpha', 'A')], long, { settings: { world_info_depth: 3 } }),
  M('depth-larger-than-chat', [E('alpha', 'A')], long, { settings: { world_info_depth: 50 } }),
  M('depth-0-scans-nothing', [E('delta', 'D'), E('x', 'C', { constant: true })], long, { settings: { world_info_depth: 0 } }),
  M('entry-scandepth-overrides', [E('alpha', 'A', { scanDepth: 4 }), E('beta', 'B', { scanDepth: 1 }), E('delta', 'D', { scanDepth: 1 })], long),
  M('entry-scandepth-zero', [E('delta', 'D', { scanDepth: 0 })], long),
  M('entry-scandepth-negative', [E('delta', 'D', { scanDepth: -1 })], long),
  M('entry-scandepth-over-max', [E('alpha', 'A', { scanDepth: 5000 })], long),
  M('include-names-on-matches-name', [E('User:', 'N')], turn('U:hi')),
  M('include-names-off-no-name', [E('User:', 'N'), E('hi', 'H')], turn('U:hi'), { settings: { world_info_include_names: false } }),
  M('empty-messages-names-off', [E('dragon', 'D', { scanDepth: 3 })], turn('A:', 'U:dragon here', 'A:'), { settings: { world_info_include_names: false } }),
  M('message-whitespace-trim', [E('/^\\u0001spaced$/', 'D')], [{ name: 'User', text: '   spaced   ' }], { settings: { world_info_include_names: false } }),
  M('injected-scan-text', [E('secret-injection', 'I')], turn('U:hi'), { injected: ['a secret-injection here'] }),
  M('injected-scan-text-not-matching', [E('secret-injection', 'I')], turn('U:hi'), { injected: ['other'] }),
);

// --- 选择逻辑
const SEC = (logic, secondary, chatText, extra = {}) => M(
  `selective-${['and-any', 'not-all', 'not-any', 'and-all'][logic]}-${extra.tag}`,
  [E('dragon', 'D', { keysecondary: secondary, selectiveLogic: logic, selective: extra.selective ?? true })],
  turn('U:' + chatText),
);
add(
  SEC(0, ['king', 'queen'], 'dragon and king', { tag: 'hit-one' }),
  SEC(0, ['king', 'queen'], 'dragon alone', { tag: 'miss' }),
  SEC(1, ['king', 'queen'], 'dragon and king', { tag: 'hit-one-missing' }),
  SEC(1, ['king', 'queen'], 'dragon king queen', { tag: 'miss-all-present' }),
  SEC(2, ['king', 'queen'], 'dragon alone', { tag: 'hit-none-present' }),
  SEC(2, ['king', 'queen'], 'dragon and queen', { tag: 'miss-one-present' }),
  SEC(3, ['king', 'queen'], 'dragon king queen', { tag: 'hit-all-present' }),
  SEC(3, ['king', 'queen'], 'dragon and king', { tag: 'miss-one-missing' }),
  SEC(3, ['king'], 'dragon alone', { tag: 'miss-single' }),
  SEC(0, ['king'], 'dragon alone', { tag: 'selective-false-ignores-secondary', selective: false }),
  M('selective-empty-secondary-ignored', [E('dragon', 'D', { keysecondary: [], selectiveLogic: 3 })], turn('U:dragon')),
  M('selective-primary-miss-secondary-irrelevant', [E('dragon', 'D', { keysecondary: ['king'], selectiveLogic: 2 })], turn('U:nothing')),
  M('selective-secondary-regex', [E('dragon', 'D', { keysecondary: ['/k(i|o)ng/'], selectiveLogic: 0 })], turn('U:dragon kong')),
  M('selective-secondary-empty-key-string', [E('dragon', 'D', { keysecondary: ['', 'king'], selectiveLogic: 3 })], turn('U:dragon king')),
);

// --- 常驻/禁用/无关键词
add(
  M('constant-no-keys', [E([], 'C', { constant: true })], turn('U:hi')),
  M('constant-disabled', [E([], 'C', { constant: true, disable: true })], turn('U:hi')),
  M('keyed-disabled', [E('hi', 'C', { disable: true })], turn('U:hi')),
  M('no-keys-not-constant', [E([], 'C')], turn('U:hi')),
);

// --- 全局扫描数据
const GS = (field, flag) => M(
  `global-scan-${flag}`,
  [E('zorblax', 'ON', { [flag]: true }), E('zorblax', 'OFF', { [flag]: false })],
  turn('U:hi'),
  { globalScan: { [field]: 'the zorblax secret' } },
);
add(
  GS('personaDescription', 'matchPersonaDescription'),
  GS('characterDescription', 'matchCharacterDescription'),
  GS('characterPersonality', 'matchCharacterPersonality'),
  GS('characterDepthPrompt', 'matchCharacterDepthPrompt'),
  GS('scenario', 'matchScenario'),
  GS('creatorNotes', 'matchCreatorNotes'),
  M('global-scan-empty-field-not-added', [E('zorblax', 'ON', { matchScenario: true })], turn('U:hi'), { globalScan: { scenario: '' } }),
  M('global-scan-with-depth-zero', [E('zorblax', 'ON', { matchScenario: true, scanDepth: 0 })], turn('U:hi'), { globalScan: { scenario: 'zorblax' } }),
);

// --- 装饰器
add(
  M('decorator-activate-without-key-match', [E('nothing-matches', '@@activate\nBody text')], turn('U:hi')),
  M('decorator-activate-strips-line', [E('hi', '@@activate\nBody text')], turn('U:hi')),
  M('decorator-dont-activate-beats-key', [E('hi', '@@dont_activate\nBody text')], turn('U:hi')),
  M('decorator-dont-activate-beats-constant', [E([], '@@dont_activate\nBody', { constant: true })], turn('U:hi')),
  M('decorator-activate-before-constant-and-disabled-check-order', [E([], '@@activate\nA', { disable: true })], turn('U:hi')),
  M('decorator-both-activate-first', [E('x', '@@activate\n@@dont_activate\nBody')], turn('U:hi')),
  M('decorator-triple-at-escape-dropped', [E('hi', '@@@activate\nBody')], turn('U:hi')),
  M('decorator-unknown-then-triple-at', [E('hi', '@@foo\n@@@activate\nBody')], turn('U:hi')),
  M('decorator-only-keeps-content', [E('nothing', '@@activate')], turn('U:hi')),
  M('decorator-with-trailing-text-not-exact', [E('nothing', '@@activate now\nBody')], turn('U:hi')),
  M('decorator-not-at-start-is-content', [E('hi', 'Body\n@@activate')], turn('U:hi')),
  M('decorator-triple-at-then-known', [E('hi', '@@@activate\n@@activate\nBody')], turn('U:hi')),
);

// --- 生成类型触发器
add(
  M('trigger-no-filter', [E('hi', 'T')], turn('U:hi'), { trigger: 'swipe' }),
  M('trigger-matching', [E('hi', 'T', { triggers: ['continue', 'swipe'] })], turn('U:hi'), { trigger: 'swipe' }),
  M('trigger-not-matching', [E('hi', 'T', { triggers: ['continue'] })], turn('U:hi'), { trigger: 'normal' }),
  M('trigger-quiet-and-impersonate', [E('hi', 'Q', { triggers: ['quiet'] }), E('hi', 'I', { triggers: ['impersonate'] })], turn('U:hi'), { trigger: 'impersonate' }),
  M('trigger-constant-also-filtered', [E([], 'C', { constant: true, triggers: ['regenerate'] })], turn('U:hi'), { trigger: 'normal' }),
);

// --- 角色过滤
const CF = (name, filter, extra = {}) => M(name, [E('hi', 'F', { characterFilter: filter })], turn('U:hi'), extra);
add(
  CF('charfilter-names-include-hit', { isExclude: false, names: ['default_Seraphina'], tags: [] }),
  CF('charfilter-names-include-miss', { isExclude: false, names: ['someone_else'], tags: [] }),
  CF('charfilter-names-exclude-hit', { isExclude: true, names: ['default_Seraphina'], tags: [] }),
  CF('charfilter-names-exclude-pass', { isExclude: true, names: ['someone_else'], tags: [] }),
  CF('charfilter-names-with-other-avatar', { isExclude: false, names: ['Eldoria_Guide'], tags: [] }, { avatar: 'Eldoria_Guide.png' }),
  CF('charfilter-tags-include-hit', { isExclude: false, names: [], tags: ['t1'] }, { tags: ['t1', 't2'] }),
  CF('charfilter-tags-include-miss', { isExclude: false, names: [], tags: ['t9'] }, { tags: ['t1'] }),
  CF('charfilter-tags-exclude-hit', { isExclude: true, names: [], tags: ['t1'] }, { tags: ['t1'] }),
  CF('charfilter-tags-exclude-pass', { isExclude: true, names: [], tags: ['t9'] }, { tags: ['t1'] }),
  CF('charfilter-tags-missing-tag-map-entry-acts-as-empty', { isExclude: false, names: [], tags: ['t9'] }, { tags: null }),
  CF('charfilter-names-and-tags-both-must-pass', { isExclude: false, names: ['default_Seraphina'], tags: ['t9'] }, { tags: ['t1'] }),
  CF('charfilter-empty-lists-no-filter', { isExclude: true, names: [], tags: [] }),
);

return await recordCases(cases);
