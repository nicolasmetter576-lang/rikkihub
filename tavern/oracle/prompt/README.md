# 提示词组装 golden 的录制

`tavern/src/test/resources/golden/prompt/*.json` 是在**真 SillyTavern 1.19.0** 里录的：走真发送路径
`Generate(type)`，拦截 `/api/backends/chat-completions/generate` 的 `fetch`，把请求体里的 `messages`
（`squash_system_messages` 已生效——它只在非 dry-run 时跑，`openai.js:1608`，所以不能用 `generate(..., dryRun=true)`）
录成 `expected`，再返回一个假应答。回放时 `PromptGoldenTest` 把同样的输入喂给 `PromptAssembler`，逐条比对 role / name / content。

## 文件

| 文件 | 主题 |
|---|---|
| `lib.js` | 公共夹具；`record.sh` 之类的入口把它拼在 `record_*.js` 前面，整体当作 async 函数体在 oracle 页面执行 |
| `record_smoke.js` | 默认预设冒烟（`PromptSmokeTest`） |
| `record_basic.js` | 默认预设、squash 开关、空历史/空卡字段、`names_behavior`、`send_if_empty`（normal 与 impersonate 两条路径）、隐藏系统消息与旁白、quiet、bias |
| `record_markers.js` | 逐个关闭标记、调序、绝对位置注入（depth/order/role）、`injection_trigger` 与生成类型 |
| `record_authors.js` | 作者注释（位置/深度/角色/间隔/空/宏/角色卡级笔记）与 persona 描述各落点 |
| `record_worldinfo.js` | 世界书结果的七种落点、`wi_format`、与作者注释的拼接、outlet |
| `record_gentype.js` | continue / impersonate / swipe / regenerate、示例对话（pin/strip/多段）、格式模板、名字清洗、token 预算扫描 |

## 怎么录

```bash
export ST_ORACLE=/path/to/oracle.py                                 # 本地的 oracle 脚本（不在仓库里）
python3 "$ST_ORACLE" start      # 起 ST 和无头 Firefox
python3 "$ST_ORACLE" evalstr 'await __st.reset(); return __st.ctx().name2'   # 先热一次页面
cat lib.js record_markers.js > /tmp/x.js
python3 "$ST_ORACLE" eval /tmp/x.js > ../../src/test/resources/golden/prompt/markers.json
```

录完检查 `cases[*].genError` 和 `expected` 是否为空；回放：`cd tavern && ../gradlew test --tests '*PromptGoldenTest*'`，
新的 golden 文件名要加进 `PromptGoldenTest.FILES`。

**先热页面**：`lib.js` 在 `reset()` 之前就取了 `const ctx = st.ctx()`，冷启动的页面里这个 `ctx` 是过期的（没有选中角色）。
`oracle.py start` 可能因为 Firefox 版本比共享 profile 旧而起不来，换新 profile 即可。

## `lib.js` 控制的夹具（每一条都踩过坑）

- **history = 组装器看到的聊天，不是录制前的聊天。** `Generate` 会在进入 `openai.js` 之前改聊天：
  - `script.js:4455`：normal、输入框为空、非预演时，先 `sendMessageAsUser(send_if_empty)`，把它写成一条真实用户消息，
    所以 `openai.js:931`（"末条是 assistant 才补"）在这条路径上不触发，只有 impersonate 之类不经过它的类型才会走到。
    库在 `fetch` 拦截瞬间快照聊天，normal 类型且聊天被追加过时用这份做 `input.history`。
  - `script.js:4496`：`is_system` 的消息被过滤，不进提示词；`sendNarratorMessage` 形状（`is_system=false` + `extra.type=narrator`）
    才以 system 角色进提示词（`openai.js:590`）。所以聊天条目的 `role: 'system'` = 隐藏消息，`role: 'narrator'` = 旁白。
  - swipe：组装前 `coreChat.pop()`（`script.js:4497`），库按此切 history。
  - regenerate：末条不是用户消息时上游会先 `removeLastMessage()`，它依赖页面里渲染出的消息元素，直接塞 `ctx.chat`
    的聊天在无头页面里会一直等。所以 regenerate 只录"末条是 user"的形态；"删掉末条 assistant"属于调用方准备历史。
- **作者注释的文本取自界面输入框** `#extension_floating_prompt`（`authors-note.js:setFloatingPrompt`，`Generate` 在 `script.js:4619` 调它），
  位置/深度/角色/间隔才取自 `chat_metadata`。间隔按**用户消息条数**算，不是聊天总条数。
- **世界书总是真调用一次** `getWorldInfoPrompt`：默认卡自带关联世界书，聊天里出现关键词（如 `forest`）就会触发，
  真请求里有而输入里没有，回放就对不上。
- **outlet**：`script.js:4676` 在生成期间把它们挂成 `customWIOutlet_<名>` 扩展提示词，结束后清掉；`subs`（宏求值表）要在同样的状态下算。
- **`subs`**：组装器不依赖宏引擎——每个进入 `substituteParams` 的原文 → 求值结果录成一张表，回放时 `PromptHost.substitute` 查表。
- **卡字段、选项、persona、预设、世界书、作者注释输入框** 都在场景结束后还原。预设被污染过一次（一个卡死的场景没来得及还原），
  所以每个场景的 `input.preset.prompts` 应该是 12 条加上场景自己追加的；看到多余条目说明页面状态脏了。
- 每个场景 `Generate` 有 15 秒超时，超时即停止录制并在该场景上记 `genError`，不会再让整份录制挂住。
- 分词：`openai_model = gpt-3.5-turbo`（cl100k）。预算类场景（`budget-*`）对同一份长聊天逐档缩小 `openai_max_context`。

## 没有 golden 的部分

- `rikkihubContext` 标记（rikkihub 自有，上游没有）：`RikkihubContextTest`。
- 预设 JSON 的解码/编码无损往返：`PromptPresetRoundTripTest`（`Default.json` 的 62 个字段 + 字段齐全的自造预设）。

## 没覆盖的上游分支

- 群聊（`selected_group`、`groupNudge`、`new_group_chat_prompt`）：不做。
- Claude 的 `assistant_prefill` / `continue_prefill` 的 prefill 拼接：录制固定走 openai 来源，Kotlin 里有开关但没有 golden。
- 工具调用相关（`ToolManager`、`tool_invocations`、`reasoning`/`signature` 随消息回放）、图片/视频/音频内联：`:tavern` 不感知这些，历史是不透明文本。
- `regenerate` 末条是 assistant 的删除步骤（见上）。
- 向量记忆（`3_vectors` / `4_vectors_data_bank` / `chromadb` 等已知扩展提示词）：Kotlin 里有槽位，没有 golden。
