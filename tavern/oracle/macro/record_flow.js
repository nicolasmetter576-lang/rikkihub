// 控制流：{{if}} 各分支、else、嵌套、# flag、条件形态、作用域缩进、求值顺序
await st.reset();
st.freezeClock('2025-03-04T05:06:07Z');
try {
  const cases = [
    { name: 'if-true', input: '{{if true}}YES{{/if}}' },
    { name: 'if-false-empty', input: '[{{if false}}YES{{/if}}]' },
    { name: 'if-else-true', input: '{{if true}}yes{{else}}no{{/if}}' },
    { name: 'if-else-false', input: '{{if false}}yes{{else}}no{{/if}}' },
    { name: 'if-invert', input: '{{if !false}}yes{{else}}no{{/if}}' },
    { name: 'if-var-condition', input: '{{if .flag}}has{{else}}none{{/if}}', localVars: { flag: '1' } },
    { name: 'if-var-condition-falsy', input: '{{if .flag}}has{{else}}none{{/if}}', localVars: { flag: '0' } },
    { name: 'if-macro-condition', input: '{{if description}}HAS-DESC{{else}}NONE{{/if}}' },
    { name: 'if-literal-0', input: '{{if 0}}yes{{else}}no{{/if}}' },
    { name: 'if-literal-false-string', input: '{{if false}}yes{{else}}no{{/if}}' },
    { name: 'if-nested', input: '{{if true}}A{{if true}}inner{{else}}mid{{/if}}Z{{else}}outer{{/if}}' },
    { name: 'if-nested-else-path', input: '{{if false}}A{{if true}}inner{{/if}}{{else}}ELSE-BRANCH{{/if}}' },
    { name: 'if-scoped-indent-trim', input: 'start\n{{if true}}\n  line1\n  line2\n{{/if}}\nend' },
    { name: 'if-preserve-whitespace', input: 'start\n{{if true #}}\n  keep\n  indent\n{{/if}}\nend' },
    { name: 'if-inline-not-scope', input: '{{if true::inline-content}}|{{/if}}' },
    { name: 'if-inline-with-else', input: '{{if false::yes::no}}' },
    { name: 'else-standalone', input: 'a{{else}}b' },
    { name: 'unknown-scoped-pair', input: '{{weird}}content{{/weird}}' },
    { name: 'two-ifs-sequence', input: '{{if true}}1{{/if}}|{{if false}}2{{/if}}|{{if true}}3{{/if}}' },
    { name: 'if-content-side-effects', input: '{{if true}}{{setvar::done::yes}}branch{{/if}}{{getvar::done}}', varsAfter: true },
    { name: 'if-lost-branch-no-side-effects', input: '{{if false}}{{setvar::done::yes}}branch{{/if}}[{{getvar::done}}]', varsAfter: true },
    { name: 'nested-macro-in-args', input: '{{reverse::{{char}}}}|{{reverse::{{reverse::ab}}}}' },
    { name: 'deep-nested-scoped', input: '{{if true}}o{{if true}}m{{if true}}i{{/if}}{{/if}}{{/if}}' },
    { name: 'if-cond-evaluation-order', input: '{{if getvar::k}}yes{{else}}no{{/if}}', localVars: { k: 'yes' } },
    { name: 'empty-doc', input: 'no macros at all' },
    { name: 'only-braces-text', input: '{curly} and {{double_curly}}' },
    { name: 'unterminated-macro', input: 'a{{char b' },
    { name: 'empty-macro', input: 'a{{}}b' },
  ];
  return { cases: await runMacroCases(cases) };
} finally {
  st.thawClock();
}
