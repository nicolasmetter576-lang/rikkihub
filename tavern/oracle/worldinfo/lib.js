// 世界书差分录制的公共夹具（record.sh 会把它拼在每个 record_*.js 前面，整体作为一个函数体在 oracle 页面里执行）。
//
// 一个用例（case）的 JSON 形状，同时也是 golden 里的 `input`：
//   name, worlds{书名:{entries:{uid:条目覆盖字段}}}, global[书名], chatBook, characterBooks[主书,附加书...], personaBook,
//   settings{world_info_*}, maxContext, globalScan{personaDescription...}, trigger, avatar, tags(null=没有标签表项),
//   injected[文本], regexScripts[脚本], random[序列], steps[{chat:[{name,text}], dryRun, random}]  (没有 steps 时用 chat)
// 条目覆盖字段会按上游 `{uid, ...newWorldInfoEntryTemplate, ...覆盖}` 补全后才交给真酒馆。
//
// 每一步录下：prompt（getWorldInfoPrompt 的返回形状）、activated（allActivatedEntries，含 hash 与求值后的内容）、
// timed（chat_metadata.timedWorldInfo）、trace（宏副作用 {{addvar::wtrace::..}} 记录的 substituteParams 调用顺序）、
// randomUsed（Math.random 被取了几次）。
const st = window.__st;
const ctx = st.ctx();
const wi = st.wi;
const oa = st.oa;

// Math.random 必须在页面上下文里替换（oracle 的脚本沙箱有自己的 Math），所以注入一小段页面脚本。
if (!window.__wiRand) {
  const s = document.createElement('script');
  s.textContent = `window.__wiRand = (() => {
    const real = Math.random; let seq = []; let used = 0;
    return {
      install(json) { seq = JSON.parse(json); used = 0; Math.random = () => { if (used >= seq.length) throw new Error('random sequence exhausted'); return seq[used++]; }; },
      used() { return used; },
      reset() { used = 0; },
      restore() { Math.random = real; },
    };
  })();`;
  document.head.appendChild(s);
}
const rand = window.__wiRand;

const DEFAULT_SETTINGS = {
  world_info_depth: 2,
  world_info_min_activations: 0,
  world_info_min_activations_depth_max: 0,
  world_info_budget: 25,
  world_info_include_names: true,
  world_info_recursive: false,
  world_info_overflow_alert: false,
  world_info_case_sensitive: false,
  world_info_match_whole_words: false,
  world_info_character_strategy: 1,
  world_info_budget_cap: 0,
  world_info_use_group_scoring: false,
  world_info_max_recursion_steps: 0,
};
const DEFAULT_SCAN = {
  personaDescription: '', characterDescription: '', characterPersonality: '',
  characterDepthPrompt: '', scenario: '', creatorNotes: '',
};

// 夹具构造辅助：entries 用数组，uid = 下标（可被覆盖字段里的 uid 改写）。
function book(entries) {
  const o = {};
  entries.forEach((e, i) => { const uid = e.uid ?? i; o[uid] = { uid, ...e }; });
  return { entries: o };
}
function turn(...lines) {
  // 'U:文本' / 'A:文本' → 用户/助手消息；最旧的在前
  return lines.map(l => {
    const m = /^([UA]):([\s\S]*)$/.exec(l);
    return { name: m[1] === 'U' ? ctx.name1 : ctx.name2, text: m[2] };
  });
}

function buildWorld(def) {
  const entries = {};
  for (const [uid, partial] of Object.entries(def.entries)) {
    entries[uid] = { uid: Number(uid), ...structuredClone(wi.newWorldInfoEntryTemplate), ...partial };
  }
  return { entries };
}

async function runCase(c) {
  const settings = { ...DEFAULT_SETTINGS, ...(c.settings ?? {}) };
  const ch = ctx.characters[ctx.characterId];
  const avatar = c.avatar ?? ch.avatar;
  const originalAvatar = ch.avatar;
  const fileName = avatar.replace(/\.[^/.]+$/, '');
  const worldNames = Object.keys(c.worlds ?? {});
  const saved = {
    settings: structuredClone(wi.getWorldInfoSettings()),
    selected: [...wi.selected_world_info],
    model: oa.oai_settings.openai_model,
    chatWorld: ctx.chatMetadata[wi.METADATA_KEY],
    timed: ctx.chatMetadata.timedWorldInfo,
    variables: ctx.chatMetadata.variables,
    charWorld: ch.data.extensions.world,
    charLore: wi.world_info.charLore,
    persona: ctx.powerUserSettings.persona_description_lorebook,
    tagHad: avatar in ctx.tagMap, tagVal: ctx.tagMap[avatar],
    regex: ctx.extensionSettings.regex,
    scanFlags: Object.entries(ctx.extensionPrompts).filter(([, p]) => p.scan).map(([k]) => k),
  };
  const out = { name: c.name, input: c, steps: [] };
  try {
    // 分词：openai 来源按 oai_settings.openai_model 选分词器，gpt-3.5-turbo = cl100k_base
    oa.oai_settings.openai_model = 'gpt-3.5-turbo';
    for (const k of saved.scanFlags) ctx.extensionPrompts[k].scan = false;
    for (const n of worldNames) wi.worldInfoCache.set(n, buildWorld(c.worlds[n]));

    wi.updateWorldInfoSettings({ ...settings }, [...(c.global ?? [])]);
    ctx.chatMetadata[wi.METADATA_KEY] = c.chatBook ?? '';
    ch.avatar = avatar;
    ch.data.extensions.world = (c.characterBooks ?? [])[0] ?? '';
    const extra = (c.characterBooks ?? []).slice(1);
    if (extra.length) wi.world_info.charLore = [{ name: fileName, extraBooks: extra }];
    else delete wi.world_info.charLore;
    ctx.powerUserSettings.persona_description_lorebook = c.personaBook ?? '';
    if (c.tags === null) delete ctx.tagMap[avatar]; else ctx.tagMap[avatar] = [...(c.tags ?? [])];
    ctx.extensionSettings.regex = structuredClone(c.regexScripts ?? []);
    if (c.injected) c.injected.forEach((t, i) => ctx.setExtensionPrompt('wiq_scan_' + i, t, 0, 0, true));
    ctx.chatMetadata.timedWorldInfo = structuredClone(c.timedInit ?? { sticky: {}, cooldown: {} });

    const steps = c.steps ?? [{ chat: c.chat }];
    for (const step of steps) {
      const dryRun = step.dryRun ?? c.dryRun ?? false;
      const seq = step.random ?? c.random ?? [];
      rand.install(JSON.stringify(seq));
      ctx.chatMetadata.variables = {};
      // 与 ST:public/script.js:4624 一致：名字: 正文，最新在前
      const chatForWI = step.chat.map(x => settings.world_info_include_names ? `${x.name}: ${x.text}` : x.text).reverse();
      const scan = { ...DEFAULT_SCAN, ...(c.globalScan ?? {}), trigger: c.trigger ?? 'normal' };
      const res = await wi.checkWorldInfo(chatForWI, c.maxContext ?? 4096, dryRun, scan);
      // 与 getWorldInfoPrompt（world-info.js:892）相同的映射
      const prompt = {
        worldInfoString: res.worldInfoBefore + res.worldInfoAfter,
        worldInfoBefore: res.worldInfoBefore,
        worldInfoAfter: res.worldInfoAfter,
        worldInfoExamples: res.EMEntries,
        worldInfoDepth: res.WIDepthEntries,
        anBefore: res.ANBeforeEntries,
        anAfter: res.ANAfterEntries,
        outletEntries: res.outletEntries,
      };
      const rec = {
        prompt: JSON.parse(JSON.stringify(prompt)),
        activated: Array.from(res.allActivatedEntries).map(e => ({ world: e.world, uid: e.uid, hash: e.hash, position: e.position, content: e.content })),
        timed: JSON.parse(JSON.stringify(ctx.chatMetadata.timedWorldInfo)),
        trace: ctx.chatMetadata.variables.wtrace ?? '',
        randomUsed: rand.used(),
      };
      // dryRun 不改任何状态，可以再用官方入口 getWorldInfoPrompt 跑一遍，确认上面的映射与它一致
      if (dryRun && !c.noSelfCheck) {
        rand.reset(); ctx.chatMetadata.variables = {};
        const again = await wi.getWorldInfoPrompt(chatForWI, c.maxContext ?? 4096, true, scan);
        if (JSON.stringify(again) !== JSON.stringify(prompt)) throw new Error('getWorldInfoPrompt differs from checkWorldInfo mapping in ' + c.name);
      }
      out.steps.push(rec);
    }
  } finally {
    rand.restore();
    oa.oai_settings.openai_model = saved.model;
    for (const n of worldNames) wi.worldInfoCache.delete(n);
    if (c.injected) c.injected.forEach((t, i) => delete ctx.extensionPrompts['wiq_scan_' + i]);
    for (const k of saved.scanFlags) if (ctx.extensionPrompts[k]) ctx.extensionPrompts[k].scan = true;
    const s = { ...saved.settings }; delete s.world_info;
    wi.updateWorldInfoSettings(s, saved.selected);
    ctx.chatMetadata[wi.METADATA_KEY] = saved.chatWorld;
    if (saved.chatWorld === undefined) delete ctx.chatMetadata[wi.METADATA_KEY];
    ctx.chatMetadata.timedWorldInfo = saved.timed;
    ctx.chatMetadata.variables = saved.variables;
    if (saved.variables === undefined) delete ctx.chatMetadata.variables;
    ch.data.extensions.world = saved.charWorld;
    if (saved.charLore === undefined) delete wi.world_info.charLore; else wi.world_info.charLore = saved.charLore;
    ctx.powerUserSettings.persona_description_lorebook = saved.persona;
    if (saved.tagHad) ctx.tagMap[avatar] = saved.tagVal; else delete ctx.tagMap[avatar];
    ch.avatar = originalAvatar;
    ctx.extensionSettings.regex = saved.regex;
    if (saved.regex === undefined) delete ctx.extensionSettings.regex;
  }
  return out;
}

async function recordCases(cases) {
  const results = [];
  for (const c of cases) results.push(await runCase(c));
  return { upstream: 'SillyTavern 1.19.0', user: ctx.name1, char: ctx.name2, cases: results };
}
