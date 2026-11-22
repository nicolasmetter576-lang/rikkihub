// 录 convertCharacterBook 的 golden：用真 ST 页面里的函数转 books.json 里的每本卡内世界书。
// 运行：tavern/oracle/card/record.sh book   （BOOKS 由 record.sh 注入）
await __st.reset();
const ctx = __st.ctx();
const out = {};
for (const [name, book] of Object.entries(BOOKS)) {
    const input = JSON.parse(JSON.stringify(book));
    const converted = ctx.convertCharacterBook(JSON.parse(JSON.stringify(book)));
    out[name] = { input, entries: converted.entries, originalData: converted.originalData };
}
return { cases: out };
