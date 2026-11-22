// 用真 SillyTavern 1.19 的服务端代码做卡格式的对照（不需要起服务）：
//   node st_node.mjs png-write <image.png> <card.json> <out.png>   用 ST 的 write() 把卡写进 PNG
//   node st_node.mjs png-read  <card.png>                          用 ST 的 read() 读出卡文本（ccv3 优先）
//   node st_node.mjs book-back <entries.json> <name>               用 ST 的 convertWorldInfoToCharacterBook 把世界书条目转回卡内世界书
//   node st_node.mjs tiny-png  <out.png>                           生成一张 4x4 的 PNG（做 png-write 的底图）
// 环境变量 ST_DIR（必填）：SillyTavern 1.19.0 源码根，装好 node_modules。
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { pathToFileURL } from 'node:url';

const ST = process.env.ST_DIR;
if (!ST) throw new Error('set ST_DIR to the SillyTavern 1.19.0 source root');
const [mode, ...args] = process.argv.slice(2);

if (mode === 'png-write' || mode === 'png-read') {
    const parser = await import(pathToFileURL(path.join(ST, 'src/character-card-parser.js')).href);
    if (mode === 'png-write') {
        const [image, card, out] = args;
        fs.writeFileSync(out, parser.write(fs.readFileSync(image), fs.readFileSync(card, 'utf8')));
    } else {
        process.stdout.write(parser.read(fs.readFileSync(args[0])));
    }
} else if (mode === 'book-back') {
    // convertWorldInfoToCharacterBook 没有导出，也不依赖别的东西：从源文件里把函数文本取出来执行
    const src = fs.readFileSync(path.join(ST, 'src/endpoints/characters.js'), 'utf8');
    const m = src.match(/function convertWorldInfoToCharacterBook\(name, entries\) \{[\s\S]*?\n\}\n/);
    if (!m) throw new Error('convertWorldInfoToCharacterBook not found');
    const fn = new Function(`${m[0]}; return convertWorldInfoToCharacterBook;`)();
    const entries = JSON.parse(fs.readFileSync(args[0], 'utf8'));
    process.stdout.write(JSON.stringify(fn(args[1], entries)));
} else if (mode === 'tiny-png') {
    const crcTable = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
    const crc = (buf) => { let c = 0xffffffff; for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
    const chunk = (type, data) => { const len = Buffer.alloc(4); len.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([len, td, c]); };
    const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(4, 0); ihdr.writeUInt32BE(4, 4); ihdr[8] = 8; ihdr[9] = 2;
    const raw = Buffer.alloc((4 * 3 + 1) * 4);
    for (let y = 0; y < 4; y++) { raw[y * 13] = 0; for (let x = 0; x < 4; x++) { raw[y * 13 + 1 + x * 3] = 40 + x * 40; raw[y * 13 + 2 + x * 3] = 90 + y * 30; raw[y * 13 + 3 + x * 3] = 160; } }
    fs.writeFileSync(args[0], Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]));
} else {
    console.error('usage: st_node.mjs png-write|png-read|book-back|tiny-png ...');
    process.exit(2);
}
