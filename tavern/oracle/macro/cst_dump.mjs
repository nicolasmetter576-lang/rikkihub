// 用上游 chevrotain 11 + 上游 MacroLexer/MacroParser 源码（原样复制，只改 lib.js 的导入）导出词法与 CST，
// 供 Kotlin 的 MacroParser 做逐输入差分（见 MacroParserFuzzTest 与 gen_corpus.mjs）。
//
// 用法：node cst_dump.mjs <corpus.json> > expected.json
//   corpus.json 是字符串数组；输出是 [{tokens, lexErrors, cst, parseErrors}] 数组（紧凑文本，便于逐项比对）。
// 环境变量 ST_DIR（必填）：SillyTavern 1.19.0 源码根，装好 node_modules。
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const ST = process.env.ST_DIR;
if (!ST) throw new Error('set ST_DIR to the SillyTavern 1.19.0 source root');
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'macro-cst-'));
fs.symlinkSync(path.join(ST, 'node_modules'), path.join(work, 'node_modules'));
fs.writeFileSync(path.join(work, 'package.json'), '{"type":"module"}');
fs.writeFileSync(path.join(work, 'lib.js'), "import * as chevrotain from 'chevrotain';\nexport { chevrotain };\n");
fs.mkdirSync(path.join(work, 'macros/engine'), { recursive: true });
for (const f of ['MacroLexer.js', 'MacroParser.js']) {
    const src = fs.readFileSync(path.join(ST, 'public/scripts/macros/engine', f), 'utf8').replace("'../../../lib.js'", "'../../lib.js'");
    fs.writeFileSync(path.join(work, 'macros/engine', f), src);
}
const { MacroLexer } = await import(path.join(work, 'macros/engine/MacroLexer.js'));
const { MacroParser } = await import(path.join(work, 'macros/engine/MacroParser.js'));

const n = (x) => (Number.isNaN(x) ? 'NaN' : String(x));
function dumpTok(t) {
    return `T(${t.tokenType.name}|${t.image}|${n(t.startOffset)}|${n(t.endOffset)}${t.isInsertedInRecovery ? '|ins' : ''})`;
}
function dumpNode(node) {
    const loc = node.location;
    let s = `<${node.name} ${n(loc?.startOffset)}:${n(loc?.endOffset)}${node.recoveredNode ? '!' : ''}`;
    for (const key of Object.keys(node.children)) {
        s += ` ${key}[` + node.children[key].map((c) => ('children' in c ? dumpNode(c) : dumpTok(c))).join(',') + ']';
    }
    return s + '>';
}

const corpus = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const out = corpus.map((input) => {
    const lr = MacroLexer.tokenize(input);
    const { cst, parserErrors } = MacroParser.parseDocument(input);
    return {
        tokens: lr.tokens.map(dumpTok).join(''),
        lexErrors: lr.errors.map((e) => `${e.offset}+${e.length}`).join(','),
        cst: cst ? dumpNode(cst) : 'null',
        parseErrors: parserErrors.length,
    };
});
process.stdout.write(JSON.stringify(out));
fs.rmSync(work, { recursive: true, force: true });
