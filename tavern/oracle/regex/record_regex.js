// 录制正则引擎 golden：每个用例由本脚本定义输入，用真 ST 的 runRegexScript / getRegexedString 求期望值。
// 运行：python3 "$ST_ORACLE" eval tavern/oracle/regex/record_regex.js > tavern/src/test/resources/golden/regex/regex.json
await __st.reset();
const ctx = __st.ctx();
const rx = __st.rx;
const S = (o) => Object.assign({ id: 'x', scriptName: 's', findRegex: '', replaceString: '', trimStrings: [], placement: [2], disabled: false, markdownOnly: false, promptOnly: false, runOnEdit: false, substituteRegex: 0, minDepth: null, maxDepth: null }, o);
ctx.variables.local.set('x', 'a.b');
const out = { run: [], get: [] };

// ---- runRegexScript 用例 ----
const runCases = [
  ['group-number', S({ findRegex: '/(\\w+)@(\\w+)/g', replaceString: '$2 at $1' }), 'mail bob@host and amy@home'],
  ['group-named', S({ findRegex: '/(?<u>\\w+)@(?<h>\\w+)/g', replaceString: '$<h>:$<u>' }), 'bob@host'],
  ['match-macro', S({ findRegex: '/b+/g', replaceString: '[{{match}}|{{MATCH}}|$0]' }), 'abbbc'],
  ['trim-strings', S({ findRegex: '/\\[(.*?)\\]/g', replaceString: '<$1>', trimStrings: ['**', '{{user}}'] }), '[**hi** User there]'],
  ['user-macro-in-replace', S({ findRegex: '/hello/g', replaceString: 'hi {{user}} from {{char}}' }), 'hello world'],
  ['no-slash-pattern', S({ findRegex: 'a+', replaceString: 'X' }), 'caaat'],
  ['no-slash-global-needs-flag', S({ findRegex: 'a', replaceString: 'X' }), 'aaa'],
  ['invalid-pattern', S({ findRegex: '/(/g', replaceString: 'X' }), 'abc'],
  ['bad-flags-whole-input', S({ findRegex: '/a/gg', replaceString: 'X' }), 'a/a/gg'],
  ['pcre-flag-rejected', S({ findRegex: '/a/X', replaceString: 'Y' }), 'a'],
  ['unmatched-group-empty', S({ findRegex: '/(a)|(b)/g', replaceString: '[$1|$2]' }), 'ab'],
  ['disabled', S({ findRegex: '/a/g', replaceString: 'X', disabled: true }), 'aaa'],
  ['empty-find', S({ findRegex: '', replaceString: 'X' }), 'aaa'],
  ['empty-matches-global', S({ findRegex: '/x*/g', replaceString: '-' }), 'abc'],
  ['lone-brace-pattern', S({ findRegex: '/{{user}}/g', replaceString: 'U' }), 'hi {{user}} {{user}}'],
  ['lookbehind-unbounded', S({ findRegex: '/(?<=\\d+)x/g', replaceString: 'X' }), '12x ax'],
  ['cjk-space-trim', S({ findRegex: '/^\\s+|\\s+$/g', replaceString: '' }), '\u3000 你好 \u3000'],
  ['dollar-in-replacement-is-literal', S({ findRegex: '/a/g', replaceString: '$$ $& $`' }), 'a'],
  ['capture-with-macro-text', S({ findRegex: '/(\\w+)/', replaceString: '$1' }), '{{user}} x'],
  ['status-block', S({ findRegex: '/<status>([\\s\\S]*?)<\\/status>/gi', replaceString: '<div class="s">$1</div>' }), 'a<STATUS>hp: 5\nmp: 2</status>b'],
  ['substitute-raw', S({ findRegex: '/{{user}}/g', replaceString: 'U', substituteRegex: 1 }), 'hi User and {{user}}'],
  ['substitute-escaped', S({ findRegex: '/{{getvar::x}}/g', replaceString: 'V', substituteRegex: 2 }), 'a.b axb'],
  ['substitute-raw-unescaped', S({ findRegex: '/{{getvar::x}}/g', replaceString: 'V', substituteRegex: 1 }), 'a.b axb'],
  ['sticky-nonglobal', S({ findRegex: '/a/y', replaceString: 'X' }), 'ba'],
  ['emoji-unicode-flag', S({ findRegex: '/\\p{Extended_Pictographic}/gu', replaceString: '*' }), 'a😀b'],
];
for (const [name, script, raw] of runCases) {
  out.run.push({ name, script, raw, expected: rx.runRegexScript(script, raw) });
}

// ---- getRegexedString 用例（脚本放进全局正则列表）----
const P = rx.regex_placement;
const base = [
  S({ id: 'a', scriptName: 'plain', findRegex: '/a/g', replaceString: 'A', placement: [P.AI_OUTPUT] }),
  S({ id: 'b', scriptName: 'md', findRegex: '/b/g', replaceString: 'B', placement: [P.AI_OUTPUT], markdownOnly: true }),
  S({ id: 'c', scriptName: 'prompt', findRegex: '/c/g', replaceString: 'C', placement: [P.AI_OUTPUT], promptOnly: true }),
  S({ id: 'd', scriptName: 'user', findRegex: '/d/g', replaceString: 'D', placement: [P.USER_INPUT] }),
  S({ id: 'e', scriptName: 'edit', findRegex: '/e/g', replaceString: 'E', placement: [P.AI_OUTPUT], runOnEdit: true }),
  S({ id: 'f', scriptName: 'depth', findRegex: '/f/g', replaceString: 'F', placement: [P.AI_OUTPUT], minDepth: 1, maxDepth: 3 }),
  S({ id: 'g', scriptName: 'wi', findRegex: '/g/g', replaceString: 'G', placement: [P.WORLD_INFO] }),
  S({ id: 'h', scriptName: 'reasoning', findRegex: '/h/g', replaceString: 'H', placement: [P.REASONING] }),
  S({ id: 'i', scriptName: 'multi', findRegex: '/i/g', replaceString: 'I', placement: [P.USER_INPUT, P.AI_OUTPUT] }),
  S({ id: 'j', scriptName: 'off', findRegex: '/j/g', replaceString: 'J', placement: [P.AI_OUTPUT], disabled: true }),
  S({ id: 'k', scriptName: 'chain1', findRegex: '/k/g', replaceString: 'kk', placement: [P.AI_OUTPUT] }),
  S({ id: 'l', scriptName: 'chain2', findRegex: '/kk/g', replaceString: 'L', placement: [P.AI_OUTPUT] }),
  S({ id: 'm', scriptName: 'maxonly', findRegex: '/m/g', replaceString: 'M', placement: [P.AI_OUTPUT], maxDepth: 0 }),
  S({ id: 'n', scriptName: 'minneg', findRegex: '/n/g', replaceString: 'N', placement: [P.AI_OUTPUT], minDepth: -1 }),
];
const raw = 'abcdefghijklmn';
const getCases = [];
const add = (name, placement, params) => getCases.push({ name, placement, params });
add('ai-output-plain', P.AI_OUTPUT, {});
add('ai-output-markdown', P.AI_OUTPUT, { isMarkdown: true });
add('ai-output-prompt', P.AI_OUTPUT, { isPrompt: true });
add('ai-output-edit', P.AI_OUTPUT, { isEdit: true });
add('user-input', P.USER_INPUT, {});
add('world-info-prompt', P.WORLD_INFO, { isPrompt: true });
add('world-info-plain', P.WORLD_INFO, {});
add('reasoning', P.REASONING, {});
add('depth-0', P.AI_OUTPUT, { depth: 0 });
add('depth-1', P.AI_OUTPUT, { depth: 1 });
add('depth-3', P.AI_OUTPUT, { depth: 3 });
add('depth-4', P.AI_OUTPUT, { depth: 4 });
add('depth-prompt-2', P.AI_OUTPUT, { isPrompt: true, depth: 2 });
add('markdown-depth-2', P.AI_OUTPUT, { isMarkdown: true, depth: 2 });
const saved = ctx.extensionSettings.regex;
try {
  ctx.extensionSettings.regex = base;
  for (const c of getCases) {
    out.get.push({ name: c.name, raw, placement: c.placement, params: c.params, scripts: base,
      expected: rx.getRegexedString(raw, c.placement, c.params) });
  }
} finally { ctx.extensionSettings.regex = saved; }
return out;
