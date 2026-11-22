// 生成随机宏语法语料（固定种子，可重复）。用法：node gen_corpus.mjs <count> [seed] > corpus.json
const ALPHABET = [
    '{{', '{{', '{{', '}}', '}}', '}}', '{', '}', '::', '::', ':', '$', '.', '!', '?', '~', '#', '/', '//', '>', '|', '||', '??', '=', '==', '!=',
    '+=', '-=', '++', '--', '<=', '>=', '??=', '||=', '"', ' ', ' ', '\n', 'a', 'user', 'char', 'setvar', 'getvar', 'if', 'else', 'x1', 'foo-bar',
    '-', '_', '1', "'", ',', '\\', '<', ')', 'é', ' ', '😀',
];
const count = Number(process.argv[2] || 1000);
let seed = Number(process.argv[3] || 12345) >>> 0;
function rnd() { // mulberry32
    seed = (seed + 0x6D2B79F5) >>> 0;
    let t = seed;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
}
const out = [];
for (let i = 0; i < count; i++) {
    const maxLen = Number(process.argv[4] || 14);
    const len = 1 + Math.floor(rnd() * maxLen);
    let s = '';
    for (let j = 0; j < len; j++) s += ALPHABET[Math.floor(rnd() * ALPHABET.length)];
    out.push(s);
}
process.stdout.write(JSON.stringify(out));
