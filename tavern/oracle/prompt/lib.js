// 提示词组装差分录制的公共夹具（record.sh 把它拼在每个 record_*.js 前面，整体作为函数体在 oracle 页面执行）。
//
// 做法：走真发送路径 `sc.Generate(type)`，拦截 `/api/backends/chat-completions/generate` 的 fetch，
// 录下请求体里的 `messages`（squash 已生效，因为 squash_system_messages 只在非 dryRun 时跑）并返回假应答。
// 世界书用真实 `wi.getWorldInfoPrompt` 跑同参数一次，把结果录成回放输入（我们的组装器只消费结果，不重扫）。
// 宏求值不依赖我们自己的宏引擎：把每个进入 substituteParams 的原文 → 求值结果录成 `subs` 对，回放时按查表重放。
//
// case JSON：
//   name, type, prompts[PromptEntry JSON], order[{identifier,enabled}], options{oai_settings 覆盖},
//   powerUser{persona_description*, pin_examples, strip_examples, prefer_character_*},
//   card{description,personality,scenario,mesExamples,systemPrompt,jailbreak,depthPrompt,depthPromptDepth,depthPromptRole},
//   chat[{role,name,text}]（role: user/assistant/system/narrator。system = 隐藏消息，上游不发；narrator = 以 system 角色发；
//        swipe/regenerate 时最后一条会被上游去掉）,
//   authorsNote{prompt,interval,position,depth,role,chara?}, quietPrompt, textarea（--@ bias 用）,
//   worlds{书名:{entries}}, wiSettings, global[书名], scanWIData{personaDescription…覆盖}, extraEP{key:{value,position,depth,role}}
// 录出的 golden case：
//   name, input{preset,options,powerUser,type,names,card,persona*,history,worldInfo,authorsNote,extraEP,quietPrompt,bias},
//   subs{原文:求值后}, expected{messages,assistantPrefill?}
const st = window.__st;
const ctx = st.ctx();
const sc = st.sc;
const oa = st.oa;
const wi = st.wi;
const pu = ctx.powerUserSettings;

// Math.random 必须在页面上下文里替换（oracle 的脚本沙箱有自己的 Math）。
if (!window.__promptRand) {
  const s = document.createElement('script');
  s.textContent = `window.__promptRand = (() => {
    const real = Math.random; let seq = []; let used = 0;
    return {
      install(json) { seq = JSON.parse(json); used = 0; Math.random = () => { if (used >= seq.length) throw new Error('random sequence exhausted'); return seq[used++]; }; },
      used() { return used; },
      restore() { Math.random = real; },
    };
  })();`;
  document.head.appendChild(s);
}
const rand = window.__promptRand;

const OAI_OPTION_KEYS = [
  'openai_max_context', 'openai_max_tokens', 'names_behavior', 'send_if_empty',
  'impersonation_prompt', 'new_chat_prompt', 'new_example_chat_prompt',
  'continue_nudge_prompt', 'wi_format', 'scenario_format', 'personality_format',
  'assistant_prefill', 'assistant_impersonation', 'squash_system_messages',
  'continue_prefill', 'continue_postfix',
];

const DEFAULT_WI_SETTINGS = {
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

function buildWorld(def) {
  const entries = {};
  for (const [uid, partial] of Object.entries(def.entries)) {
    entries[uid] = { uid: Number(uid), ...structuredClone(wi.newWorldInfoEntryTemplate), ...partial };
  }
  return { entries };
}

// 与 getExtensionPrompt(IN_CHAT, depth, '\n', role, wrap=false) 同口径的 join（求值前的串）
function extJoined(extPrompts, depth, roleCode) {
  const values = Object.keys(extPrompts).sort()
    .map(k => extPrompts[k])
    .filter(x => x.position == 1 && x.value)
    .filter(x => x.depth === undefined || x.depth === depth)
    .filter(x => roleCode === undefined || x.role === undefined || x.role === roleCode)
    .map(x => String(x.value).trim())
    .join('\n');
  return values;
}

async function runPromptCase(c) {
  const ch = ctx.characters[ctx.characterId];
  const saved = {
    chat: structuredClone(ctx.chat),
    md: structuredClone(sc.chat_metadata),
    prompts: structuredClone(oa.oai_settings.prompts),
    order: structuredClone(oa.oai_settings.prompt_order),
    oaiOpts: Object.fromEntries(OAI_OPTION_KEYS.map(k => [k, oa.oai_settings[k]])),
    source: oa.oai_settings.chat_completion_source,
    model: oa.oai_settings.openai_model,
    stream: oa.oai_settings.stream_openai,
    ep: structuredClone(sc.extension_prompts),
    pu: structuredClone({
      persona_description: pu.persona_description,
      persona_description_position: pu.persona_description_position,
      persona_description_depth: pu.persona_description_depth,
      persona_description_role: pu.persona_description_role,
      pin_examples: pu.pin_examples,
      strip_examples: pu.strip_examples,
      prefer_character_prompt: pu.prefer_character_prompt,
      prefer_character_jailbreak: pu.prefer_character_jailbreak,
      user_prompt_bias: pu.user_prompt_bias,
    }),
    ch: structuredClone({
      description: ch.description, personality: ch.personality, scenario: ch.scenario,
      mes_example: ch.mes_example, system_prompt: ch.data?.system_prompt,
      post_history_instructions: ch.data?.post_history_instructions,
      depth_prompt: ch.data?.extensions?.depth_prompt,
    }),
    wiSettings: structuredClone(wi.getWorldInfoSettings()),
    wiSelected: [...wi.selected_world_info],
    chatWorld: sc.chat_metadata[wi.METADATA_KEY],
    charWorld: ch.data.extensions.world,
    noteChara: structuredClone(ctx.extensionSettings.note?.chara ?? null),
    textarea: document.querySelector('#send_textarea')?.value ?? '',
    noteText: document.querySelector('#extension_floating_prompt')?.value ?? '',
  };
  const worldNames = Object.keys(c.worlds ?? {});
  const out = { name: c.name };

  try {
    sc.setOnlineStatus('online');
    st.freezeClock('2025-03-04T05:06:07Z');
    rand.install(JSON.stringify(c.random ?? []));
    oa.oai_settings.chat_completion_source = 'openai';
    oa.oai_settings.openai_model = 'gpt-3.5-turbo'; // cl100k
    oa.oai_settings.stream_openai = false;
    for (const [k, v] of Object.entries(c.options ?? {})) oa.oai_settings[k] = v;
    if (c.prompts) oa.oai_settings.prompts = structuredClone(c.prompts);
    if (c.order) oa.oai_settings.prompt_order = [{ character_id: 100001, order: structuredClone(c.order) }];

    if (c.powerUser) Object.assign(pu, c.powerUser);

    if (c.card) {
      if ('description' in c.card) ch.description = c.card.description;
      if ('personality' in c.card) ch.personality = c.card.personality;
      if ('scenario' in c.card) { ch.scenario = c.card.scenario; delete sc.chat_metadata.scenario; }
      if ('mesExamples' in c.card) ch.mes_example = c.card.mesExamples;
      if ('systemPrompt' in c.card) ch.data.system_prompt = c.card.systemPrompt;
      if ('jailbreak' in c.card) ch.data.post_history_instructions = c.card.jailbreak;
      if ('depthPrompt' in c.card || 'depthPromptDepth' in c.card || 'depthPromptRole' in c.card) {
        ch.data.extensions.depth_prompt = {
          prompt: c.card.depthPrompt ?? '',
          depth: c.card.depthPromptDepth ?? 4,
          role: c.card.depthPromptRole ?? 'system',
        };
      }
    }

    const md = sc.chat_metadata;
    // 作者注释的文本取自界面输入框（authors-note.js:setFloatingPrompt 读 $('#extension_floating_prompt').val()，
    // Generate 在 script.js:4619 调它）；位置/深度/角色/间隔才取自 chat_metadata。只改 metadata 文本不会生效。
    const noteBox = document.querySelector('#extension_floating_prompt');
    if (noteBox) noteBox.value = c.authorsNote?.prompt ?? '';
    if (c.authorsNote) {
      md.note_prompt = c.authorsNote.prompt ?? '';
      md.note_interval = c.authorsNote.interval ?? 1;
      md.note_position = c.authorsNote.position ?? 1;
      md.note_depth = c.authorsNote.depth ?? 4;
      md.note_role = c.authorsNote.role ?? 0;
      if (c.authorsNote.chara) {
        if (!ctx.extensionSettings.note) ctx.extensionSettings.note = {};
        ctx.extensionSettings.note.chara = [{
          name: ch.avatar.replace(/\.[^/.]+$/, ''),
          prompt: c.authorsNote.chara.prompt ?? '',
          useChara: c.authorsNote.chara.useChara ?? true,
          position: c.authorsNote.chara.position ?? 0,
        }];
      }
    } else {
      md.note_prompt = ''; md.note_interval = 0;
    }

    // chat（swipe 需要最后一条有 swipes 数组）
    ctx.chat.length = 0;
    (c.chat ?? []).forEach((m, i) => {
      // system：隐藏的系统消息（is_system=true），Generate 在 script.js:4496 直接过滤，不进提示词
      // narrator：sendNarratorMessage 的形状（is_system=false + extra.type=narrator），openai.js:590 让它以 system 角色进提示词
      const isSystem = m.role === 'system';
      const isNarrator = m.role === 'narrator';
      const isUser = m.role === 'user';
      ctx.chat.push({
        mes: m.text, name: m.name, is_user: isUser, is_system: isSystem,
        swipe_id: 0, swipes: [m.text, 'prev-swipe'], send_date: Date.now() - ((c.chat.length - i) * 60000),
        extra: isNarrator ? { type: 'narrator' } : {},
      });
    });

    // 世界书夹具
    for (const n of worldNames) wi.worldInfoCache.set(n, buildWorld(c.worlds[n]));
    if (c.wiSettings || c.global) wi.updateWorldInfoSettings({ ...DEFAULT_WI_SETTINGS, ...(c.wiSettings ?? {}) }, [...(c.global ?? [])]);
    if ('chatBook' in c) sc.chat_metadata[wi.METADATA_KEY] = c.chatBook;
    if ('charWorld' in c) ch.data.extensions.world = c.charWorld;

    // extra extension prompts
    for (const [key, ep] of Object.entries(c.extraEP ?? {})) {
      ctx.setExtensionPrompt(key, ep.value, ep.position, ep.depth ?? 0, false, ep.role ?? 0);
    }

    // ---- 回放输入快照 ----
    const fields = sc.getCharacterCardFields();
    const chatForWI = ctx.chat
      .filter(x => !x.is_system)
      .map(x => (c.wiSettings?.world_info_include_names ?? DEFAULT_WI_SETTINGS.world_info_include_names) ? `${x.name}: ${x.mes}` : x.mes)
      .reverse();
    const scan = { ...DEFAULT_SCAN, ...(c.scanWIData ?? {}) };
    scan.personaDescription = scan.personaDescription || fields.persona || '';
    scan.characterDescription = scan.characterDescription || fields.description || '';
    scan.characterPersonality = scan.characterPersonality || fields.personality || '';
    scan.characterDepthPrompt = scan.characterDepthPrompt || fields.charDepthPrompt || '';
    scan.scenario = scan.scenario || fields.scenario || '';
    scan.creatorNotes = scan.creatorNotes || fields.creatorNotes || '';
    scan.trigger = 'normal';
    const maxCtx = oa.oai_settings.openai_max_context ?? 4095;
    let worldInfo = {
      worldInfoString: '', worldInfoBefore: '', worldInfoAfter: '',
      worldInfoExamples: [], worldInfoDepth: [], anBefore: [], anAfter: [], outletEntries: {},
    };
    // 总是真调用一次：默认角色卡自带关联世界书，聊天里出现关键词就会触发，真请求里有而输入里没有会让回放对不上
    {
      const r = await wi.getWorldInfoPrompt(chatForWI, maxCtx, false, scan);
      worldInfo = JSON.parse(JSON.stringify({
        worldInfoString: r.worldInfoString, worldInfoBefore: r.worldInfoBefore, worldInfoAfter: r.worldInfoAfter,
        worldInfoExamples: r.worldInfoExamples, worldInfoDepth: r.worldInfoDepth,
        anBefore: r.anBefore, anAfter: r.anAfter, outletEntries: r.outletEntries,
      }));
    }

    // input.history：swipe/regenerate 上游会去掉最后一条
    // swipe：Generate 在组装前 coreChat.pop()（script.js:4497）。regenerate：末条不是用户消息时上游会先
    // removeLastMessage()（script.js:4410，依赖页面里渲染出的消息元素，直接塞 ctx.chat 的聊天在无头页面里会一直等），
    // 所以 regenerate 只录“末条是用户消息”的形态；“删掉末条 assistant”属于调用方准备历史，组装器只看到少一条的历史。
    if (c.type === 'regenerate' && (c.chat ?? []).length && !(c.chat[c.chat.length - 1].role === 'user')) {
      throw new Error('regenerate 场景的 chat 末条必须是 user（见上面的说明）');
    }
    const histSource = c.type === 'swipe' ? (c.chat ?? []).slice(0, -1) : (c.chat ?? []);
    // history = 组装器看到的聊天：隐藏的 system 消息被 Generate 过滤；narrator 在提示词里是 system 角色
    const history = histSource.filter(m => m.role !== 'system')
      .map(m => ({ role: m.role === 'narrator' ? 'system' : m.role, name: m.name, text: m.text }));

    const input = {
      preset: { prompts: oa.oai_settings.prompts, prompt_order: oa.oai_settings.prompt_order },
      options: Object.fromEntries(OAI_OPTION_KEYS.map(k => [k, oa.oai_settings[k]])),
      powerUser: {
        pin_examples: !!pu.pin_examples,
        strip_examples: !!pu.strip_examples,
        persona_description: pu.persona_description ?? '',
        persona_description_position: pu.persona_description_position ?? 0,
        persona_description_depth: pu.persona_description_depth ?? 0,
        persona_description_role: pu.persona_description_role ?? 0,
      },
      type: c.type ?? 'normal',
      names: { user: ctx.name1, char: ctx.name2 },
      card: {
        description: fields.description, personality: fields.personality, scenario: fields.scenario,
        mesExamples: fields.mesExamples, charDepthPrompt: fields.charDepthPrompt,
        systemPrompt: fields.system, jailbreak: fields.jailbreak,
        depthPromptDepth: ch.data?.extensions?.depth_prompt?.depth ?? 4,
        depthPromptRole: ch.data?.extensions?.depth_prompt?.role ?? 'system',
      },
      history,
      worldInfo,
      authorsNote: c.authorsNote ?? null,
      extraEP: c.extraEP ?? {},
      quietPrompt: c.quietPrompt ?? '',
      // getBiasStrings：impersonate/continue 没有 bias；简化路径只覆盖 user_prompt_bias（textarea 不用于 bias）
      bias: (c.type === 'impersonate' || c.type === 'continue') ? '' : sc.substituteParams(pu.user_prompt_bias ?? ''),
      textarea: c.textarea ?? '',
      maxContext: maxCtx,
      recordedAtEpoch: Date.now(),
      random: c.random ?? [],
    };

    // ---- subs 对：先收集所有原文，Generate 后用同环境 substituteParams 求值 ----
    const subTexts = new Set();
    const addSubs = (t) => { if (typeof t === 'string' && t.includes('{{')) subTexts.add(t); };
    for (const p of oa.oai_settings.prompts ?? []) addSubs(p.content);
    for (const k of ['impersonation_prompt', 'new_chat_prompt', 'new_example_chat_prompt',
      'continue_nudge_prompt', 'scenario_format', 'personality_format', 'assistant_prefill',
      'assistant_impersonation']) addSubs(oa.oai_settings[k]);
    addSubs(c.quietPrompt);
    addSubs(c.textarea);
    for (const m of history) addSubs(m.text);
    addSubs(oa.oai_settings.send_if_empty);
    for (const ep of Object.values(c.extraEP ?? {})) addSubs(ep.value);
    addSubs(fields.charDepthPrompt);
    if (pu.user_prompt_bias) addSubs(pu.user_prompt_bias);

    // ---- 发送路径：fetch 拦截 ----
    let captured = null;
    let chatAtSend = null;
    const origFetch = window.fetch;
    window.fetch = async (url, opts) => {
      if (String(url).includes('chat-completions/generate')) {
        captured = JSON.parse(opts.body);
        // 发送瞬间的聊天 = 组装器真正看到的历史（含 Generate 在组装前追加的用户消息，见下）
        chatAtSend = ctx.chat.filter(m => !m.is_system).map(m => ({
          role: m.extra?.type === 'narrator' ? 'system' : (m.is_user ? 'user' : 'assistant'), name: m.name, text: m.mes,
        }));
        return new Response(JSON.stringify({ choices: [{ message: { content: 'FAKE' } }] }), { status: 200 });
      }
      return origFetch(url, opts);
    };
    let genError = null;
    const genOpts = c.type === 'quiet' ? { quiet_prompt: c.quietPrompt ?? '' } : {};
    try {
      // 某些场景会让 Generate 一直不返回（页面里没有请求发出）；超时就报出是哪个场景，别让整份录制挂住
      let timer;
      const timeout = new Promise((_, rej) => { timer = setTimeout(() => rej(new Error('Generate timeout (15s)')), 15000); });
      try {
        await Promise.race([sc.Generate(c.type ?? 'normal', genOpts), timeout]);
      } finally { clearTimeout(timer); }
    } catch (e) {
      genError = String(e && e.message || e);
    } finally {
      window.fetch = origFetch;
    }
    if (!captured && !genError) genError = 'no request captured';
    if (genError) out.genError = genError;

    // script.js:4455 —— 真发送路径（normal、输入框为空、非预演）会先 sendMessageAsUser(send_if_empty)，
    // 把它写成一条真实用户消息，之后才进 openai.js；所以 openai.js:931 那个“末条是 assistant 才补”的分支在这条路径上
    // 不会触发。history 记成发送瞬间的聊天，回放就不需要在测试里模拟 Generate 这一层。
    if ((c.type ?? 'normal') === 'normal' && chatAtSend && chatAtSend.length > history.length) {
      input.history.length = 0;
      input.history.push(...chatAtSend);
    }

    // ---- subs 求值（Generate 之后，同环境、确定性宏）----
    const subs = {};
    for (const epKey of Object.keys(sc.extension_prompts)) {
      const v = sc.extension_prompts[epKey]?.value;
      if (typeof v === 'string' && v.includes('{{')) subTexts.add(v);
    }
    // 注入 join：IN_CHAT 各 (depth,role) 组合的拼串
    const epSnapshot = sc.extension_prompts;
    const depths = new Set([0]);
    for (const ep of Object.values(epSnapshot)) if (ep.position == 1 && ep.depth !== undefined) depths.add(ep.depth);
    for (const d of depths) for (const role of [0, 1, 2]) {
      const j = extJoined(epSnapshot, d, role);
      if (j && j.includes('{{')) subTexts.add(j);
    }
    // 世界书 outlet：Generate 期间 script.js:4676 把它们挂成 customWIOutlet_<名> 扩展提示词（值用 \n 连接），
    // 生成结束时（script.js:5680）清掉；{{outlet::名}} 必须在同样的状态下求值，否则 subs 里是空串
    const outletKeys = [];
    for (const [name, vals] of Object.entries(worldInfo.outletEntries ?? {})) {
      const key = `customWIOutlet_${name}`;
      if (!(key in sc.extension_prompts)) {
        sc.extension_prompts[key] = { value: vals.join('\n'), position: -1, depth: 0, role: 0 };
        outletKeys.push(key);
      }
    }
    try {
      for (const t of subTexts) {
        try { subs[t] = sc.substituteParams(t); } catch (e) { subs[t] = 'ERR:' + e.message; }
      }
    } finally {
      for (const key of outletKeys) delete sc.extension_prompts[key];
    }

    out.input = input;
    out.subs = subs;
    out.expected = captured ? {
      messages: captured.messages,
      ...(captured.assistant_prefill !== undefined ? { assistantPrefill: captured.assistant_prefill } : {}),
    } : null;
  } finally {
    // 还原
    rand.restore();
    ctx.chat.length = 0; ctx.chat.push(...saved.chat);
    for (const k of Object.keys(sc.chat_metadata)) delete sc.chat_metadata[k];
    Object.assign(sc.chat_metadata, saved.md);
    oa.oai_settings.prompts = saved.prompts;
    oa.oai_settings.prompt_order = saved.order;
    for (const [k, v] of Object.entries(saved.oaiOpts)) oa.oai_settings[k] = v;
    oa.oai_settings.chat_completion_source = saved.source;
    oa.oai_settings.openai_model = saved.model;
    oa.oai_settings.stream_openai = saved.stream;
    for (const k of Object.keys(sc.extension_prompts)) delete sc.extension_prompts[k];
    Object.assign(sc.extension_prompts, saved.ep);
    Object.assign(pu, saved.pu);
    ch.description = saved.ch.description;
    ch.personality = saved.ch.personality;
    ch.scenario = saved.ch.scenario;
    ch.mes_example = saved.ch.mes_example;
    if (ch.data) {
      ch.data.system_prompt = saved.ch.system_prompt;
      ch.data.post_history_instructions = saved.ch.post_history_instructions;
      ch.data.extensions.depth_prompt = saved.ch.depth_prompt;
      ch.data.extensions.world = saved.charWorld;
    }
    const s = { ...saved.wiSettings }; delete s.world_info;
    wi.updateWorldInfoSettings(s, saved.wiSelected);
    if (saved.chatWorld === undefined) delete sc.chat_metadata[wi.METADATA_KEY]; else sc.chat_metadata[wi.METADATA_KEY] = saved.chatWorld;
    if (ctx.extensionSettings.note) ctx.extensionSettings.note.chara = saved.noteChara;
    for (const n of worldNames) wi.worldInfoCache.delete(n);
    const ta = document.querySelector('#send_textarea');
    if (ta) ta.value = saved.textarea;
    const noteBoxBack = document.querySelector('#extension_floating_prompt');
    if (noteBoxBack) noteBoxBack.value = saved.noteText;
  }
  return out;
}

async function recordPromptCases(cases) {
  const results = [];
  for (const c of cases) {
    const r = await runPromptCase(c);
    results.push(r);
    if (r.genError && r.genError.startsWith('Generate timeout')) break; // 页面的生成状态可能已卡住，后面的录不准
  }
  return { upstream: 'SillyTavern 1.19.0', user: ctx.name1, char: ctx.name2, cases: results };
}
