// 宏引擎差分录制公共夹具（record.sh 把它拼在每个 record_*.js 前面，整体作为函数体在 oracle 页面执行）。
//
// golden case 形状：
//   name, input, expected（或 allow[]：结果只需属于列表，{{random}} 用）,
//   env{names,character,model,isMobile,lastGenerationType,firstIncludedMessageId,firstDisplayedMessageId,
//        input,maxPromptTokens,maxContextTokens,maxResponseTokens,outlet,chat,variables,pickRerollSeed,
//        chatId,original,content,timezone},
//   varsAfter{local,global}（可选：终态变量断言）
const st = window.__st;
const ctx = st.ctx();
const sc = st.sc;
const oa = st.oa;

// ST:utils.js getStringHash 内联拷贝（import 会触发页面模块链报错）
function getStringHash(str, seed = 0) {
  if (typeof str !== 'string') return 0;
  let h1 = 0xdeadbeef ^ seed, h2 = 0x41c6ce57 ^ seed;
  for (let i = 0, ch; i < str.length; i++) {
    ch = str.charCodeAt(i);
    h1 = Math.imul(h1 ^ ch, 2654435761);
    h2 = Math.imul(h2 ^ ch, 1597334677);
  }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
  return 4294967296 * (2097151 & h2) + (h1 >>> 0);
}

if (!window.__macroRand) {
  const s = document.createElement('script');
  s.textContent = `window.__macroRand = (() => {
    const real = Math.random; let seq = []; let used = 0;
    return {
      install(json) { seq = JSON.parse(json); used = 0; Math.random = () => { if (used >= seq.length) throw new Error('random sequence exhausted'); return seq[used++]; }; },
      used() { return used; },
      restore() { Math.random = real; },
    };
  })();`;
  document.head.appendChild(s);
}
const rand = window.__macroRand;

function setChat(lines) {
  ctx.chat.length = 0;
  for (const m of lines) {
    ctx.chat.push({
      mes: m.text,
      name: m.name,
      is_user: !!m.isUser,
      is_system: !!m.isSystem,
      swipe_id: m.swipeId ?? 0,
      swipes: new Array(m.swipeCount ?? 1).fill(m.text),
      send_date: m.sendDateMillis ?? undefined,
      extra: {},
    });
  }
}

function setVars(local = {}, global = {}) {
  ctx.chatMetadata.variables = structuredClone(local);
  if (!ctx.extensionSettings.variables) ctx.extensionSettings.variables = {};
  ctx.extensionSettings.variables.global = structuredClone(global);
}

function snapshotEnv(input) {
  const ch = ctx.characters[ctx.characterId];
  return {
    names: {
      user: ctx.name1 ?? '',
      char: ctx.name2 ?? '',
      group: ctx.name2 ?? '',          // 单人聊天 getGroupValue 语义：currentChar
      groupNotMuted: ctx.name2 ?? '',
      notChar: ctx.name1 ?? '',        // 单人 notChar = includeUser
    },
    // env.character 装的是 getCharacterCardFields() 的解析结果（字段内 {{user}}/{{char}} 已被 baseChatReplace 代掉）
    character: (() => {
      const f = sc.getCharacterCardFields();
      return {
        charPrompt: f.system ?? '',
        charInstruction: f.jailbreak ?? '',
        description: f.description ?? '',
        personality: f.personality ?? '',
        scenario: f.scenario ?? '',
        persona: f.persona ?? '',
        mesExamplesRaw: f.mesExamples ?? '',
        charDepthPrompt: f.charDepthPrompt ?? '',
        creatorNotes: f.creatorNotes ?? '',
        version: String(f.version ?? ''),
        firstMessage: f.firstMessage ?? '',
        alternateGreetings: f.alternateGreetings ?? [],
      };
    })(),
    model: sc.getGeneratingModel(),
    isMobile: window.innerWidth <= 1000 || 'ontouchstart' in window,
    lastGenerationType: '',
    firstIncludedMessageId: typeof ctx.chatMetadata?.lastInContextMessageId === 'number' ? ctx.chatMetadata.lastInContextMessageId : null,
    firstDisplayedMessageId: (() => { const el = document.querySelector('#chat .mes'); const id = Number(el?.getAttribute('mesid')); return Number.isNaN(id) || id < 0 ? null : id; })(),
    input: document.querySelector('#send_textarea')?.value ?? '',
    maxPromptTokens: sc.getMaxPromptTokens(),
    maxContextTokens: sc.getMaxContextTokens(),
    maxResponseTokens: sc.getMaxResponseTokens(),
    outlet: Object.fromEntries(
      Object.entries(sc.extension_prompts)
        .filter(([k]) => k.startsWith('customWIOutlet_'))
        .map(([k, v]) => [k.slice('customWIOutlet_'.length), [v.value]]),
    ),
    chat: ctx.chat.map(m => ({
      name: m.name, text: m.mes, isUser: !!m.is_user, isSystem: !!m.is_system,
      swipeId: m.swipe_id ?? 0, swipeCount: m.swipes?.length ?? 1,
      sendDateMillis: typeof m.send_date === 'number' ? m.send_date : null,
    })),
    variables: {
      local: structuredClone(ctx.chatMetadata?.variables ?? {}),
      global: structuredClone(ctx.extensionSettings?.variables?.global ?? {}),
    },
    pickRerollSeed: ctx.chatMetadata?.pick_reroll_seed ?? null,
    chatId: ctx.chatMetadata?.main_chat ?? sc.getCurrentChatId(),
    // {{pick}} 种子里 upstream 用 chat_metadata.chat_id_hash 缓存值；录生效值保证可回放
    chatIdHash: typeof ctx.chatMetadata?.chat_id_hash === 'number'
      ? ctx.chatMetadata.chat_id_hash
      : getStringHash(ctx.chatMetadata?.main_chat ?? sc.getCurrentChatId()),
    contentHash: getStringHash(input),
    original: null,
    content: input,
    timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
  };
}

// 跑一个用例：夹具 → substituteParams → 录 expected + env 快照 + 终态变量
// c: {name,input,chat,vars,localVars,globalVars,input_text,pickRerollSeed,original,chatIdOverride,
//     outlet,options,random[],chatMeta}
async function runMacroCase(c) {
  const chatBackup = structuredClone(ctx.chat);
  const varsBackup = structuredClone(ctx.chatMetadata?.variables ?? null);
  const gvarsBackup = structuredClone(ctx.extensionSettings?.variables?.global ?? null);
  const chatMetaBackup = structuredClone(ctx.chatMetadata ?? {});
  const textarea = document.querySelector('#send_textarea');
  const textareaBackup = textarea?.value ?? '';
  const outletKeys = Object.keys(c.outlet ?? {});
  const outletBackup = {};
  for (const k of outletKeys) outletBackup[k] = sc.extension_prompts[`customWIOutlet_${k}`];
  try {
    if (c.chatMeta) Object.assign(ctx.chatMetadata, structuredClone(c.chatMeta));
    if (c.chat) setChat(c.chat);
    setVars(c.vars?.local ?? c.localVars ?? {}, c.vars?.global ?? c.globalVars ?? {});
    if (c.pickRerollSeed !== undefined) {
      if (c.pickRerollSeed === null) delete ctx.chatMetadata.pick_reroll_seed;
      else ctx.chatMetadata.pick_reroll_seed = c.pickRerollSeed;
    }
    if (c.input_text !== undefined && textarea) textarea.value = c.input_text;
    for (const k of outletKeys) {
      sc.extension_prompts[`customWIOutlet_${k}`] = { value: c.outlet[k], position: 4 };
    }
    if (c.random) rand.install(JSON.stringify(c.random));

    const options = {};
    if (c.original !== undefined) options.original = c.original;
    if (c.options) Object.assign(options, c.options);

    const env = snapshotEnv(c.input);
    env.lastGenerationType = null; // 实际值在 expected 输出里，回放时由 case.env.lastGenerationType 覆盖
    let expected;
    try {
      expected = ctx.substituteParams(c.input, options);
    } catch (e) {
      expected = '__ERROR__:' + e.message;
    }

    const out = { name: c.name, input: c.input, env };
    if (c.random) out.random = c.random;
    if (expected !== undefined) {
      if (c.allow) { out.allow = c.allow; }
      out.expected = expected;
    }
    if (c.varsAfter) {
      out.varsAfter = {
        local: structuredClone(ctx.chatMetadata?.variables ?? {}),
        global: structuredClone(ctx.extensionSettings?.variables?.global ?? {}),
      };
    }
    if (c.original !== undefined) env.original = c.original;
    return out;
  } finally {
    ctx.chat.length = 0;
    for (const m of chatBackup) ctx.chat.push(m);
    ctx.chatMetadata.variables = varsBackup;
    if (!ctx.extensionSettings.variables) ctx.extensionSettings.variables = {};
    ctx.extensionSettings.variables.global = gvarsBackup;
    Object.keys(ctx.chatMetadata).forEach(k => { if (!(k in chatMetaBackup)) delete ctx.chatMetadata[k]; });
    Object.assign(ctx.chatMetadata, chatMetaBackup);
    if (textarea) textarea.value = textareaBackup;
    for (const k of outletKeys) {
      const key = `customWIOutlet_${k}`;
      if (outletBackup[k] === undefined) delete sc.extension_prompts[key];
      else sc.extension_prompts[key] = outletBackup[k];
    }
    rand.restore();
  }
}

async function runMacroCases(cases) {
  const results = [];
  for (const c of cases) results.push(await runMacroCase(c));
  return results;
}
