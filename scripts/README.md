# scripts/

独立交付诊断脚本（Python 3 标准库，零第三方依赖，完全离线，不读凭据）。

## apk_build_info.py — APK build-info / hash / 验收

对**已构建并签名完成**的 APK 收集诊断信息：sha256、大小、mtime、zip 条目数、
实际打包的 ABI、内嵌 `META-INF/version-control-info.textproto` 的源码 revision、
签名存在性（v1 `META-INF/*.SF` 或 v2+ `APK Sig Block 42`），并执行验收检查：

- zip 可正常打开（不解压任何条目到磁盘，zip-slip 安全）；
- APK 实际打包的每个 ABI 都必须有 `libworkspace_pty.so`、`libproot_exec.so`、`libproot_loader.so`、
  `libtermux.so`；完全没有 `lib/` 条目判为不完整；未打包的 ABI 不要求；
- `static/assets/` 下必须有 `workspaces*.js`、`storage*.js`、`translator*.js`；
- 必须有签名（v1 或 v2+）；
- `classes*.dex` 里 Web 引擎（Netty）的反射入口必须还在：`NioServerSocketChannel` 公开无参构造、
  真实的 `NioSocketChannel`、`ChannelInitializer` 的 `@Sharable`、`NettyDirectEncoder` 与
  `MessageToByteEncoder` 的泛型签名、`ConcurrentSkipListIntObjMultimap` 的 `acquireFence*`（dex 只在内存中解析，不落盘）。
  2.6.0～2.8.1 的 release 包这五项全缺，Web 服务起不来。

默认输出 sidecar `<apk>.build-info.json`；`-o FILE` 输出合并报告；`--check-only`
只检查不写文件。脚本绝不写回/移动输入 APK，不联网，不读取 `config.yaml`
或任何凭据——只读 zip 元数据与 VCS textproto。退出码：全部通过 `0`，
任一 APK 有问题 `1`，用法错误 `2`。

```bash
# 旁挂 sidecar（默认）
python3 scripts/apk_build_info.py apk/debug/app-debug.apk

# 合并报告 / 只打印 / 只验收
python3 scripts/apk_build_info.py -o dist/build-info.json apk/release/*.apk
python3 scripts/apk_build_info.py --stdout apk/coexist/app-debug.apk
python3 scripts/apk_build_info.py --check-only app/build/outputs/apk/release/*.apk

# git commit/dirty 默认取脚本所在仓库；可指定或关闭
python3 scripts/apk_build_info.py --repo /path/to/repo apk/debug/app-debug.apk
python3 scripts/apk_build_info.py --no-git apk/debug/app-debug.apk
```

> APK sha256 **绝不注入 APK 自身**（`BuildConfig` 只含 commit/dirty/构建时间），
> 只能在外部对成品计算——本脚本即承担此事。

## test_apk_build_info.py — 离线单测

用临时 zip 模拟 APK 结构，覆盖 hash 正确性、ABI/pty/asset/签名验收、
Web 引擎 dex 检查（测试内合成最小 dex）、zip-slip 不解压、输出拒绝规则、退出码。不依赖真实 APK、不联网：

```bash
python3 scripts/test_apk_build_info.py
```

## web_smoke.py — 运行中的 Web 服务烟测

对已经启动的 Web 服务（通常是 release 包，经 `adb forward`）检查：首页、24 个并发新连接、
SSE（`/api/events`）、WebSocket 升级（终端路由 + 不存在的会话，期望 101 后收到关闭帧）。
HTTPS 不校验证书（本地自签 CA）。后两项需要 API 可访问：关闭身份验证，或加 `--password`。

```bash
adb forward tcp:18383 tcp:18383
python3 scripts/web_smoke.py http://127.0.0.1:18383
python3 scripts/web_smoke.py --password PASS https://127.0.0.1:18383
```

## mock-openai/ — 离线模拟模型服务（仪器测试用）

`mock_openai.py` 是 OpenAI 兼容的本机模拟服务（端口 18080），不连接任何真实或付费模型；
`mockctl.sh start|stop` 启停。`app/src/androidTest/.../e2e/LocalMockEndToEndTest` 在模拟器内经
`10.0.2.2:18080` 访问它；用户消息含 `[[script:NAME]]` 时按 `SCRIPTS[NAME]` 依次返回工具调用和文本。推敲请求（提示词含「需要推敲的原文：」）自动回 N 行 JSON 候选（N 取自「只输出 N 行」，保留原文标记）；文档里放 `[[tuiqiao:broken]]` 得到去掉 Markdown 标记的候选，放 `[[tuiqiao:plain]]` 得到非 JSON 的编号行。推敲角色的请求（含「给出 1 个结果」）约 1.5 秒后回 1 行：选区加「（角色要求前 6 个字）」；角色要求里含 `[[tuiqiao:role-fail]]` 时回 HTTP 500（单张角色卡失败）。再造请求（第一条用户消息含「需要再造的原文：」）回 `<result>底稿（类型：要求｜第N轮｜后文M字｜避开K稿）</result>`：底稿第一轮是选区、之后是上一条回复的 result，N 是用户消息条数，M 是发来的后文长度，K 是要避开的稿数，用来核对历史链、后文开关和换一版；重写类型先回 `<plan>`。本轮要求里放 `[[zaizao:slow]]` 约 6 秒才写完（测停止 / 收起），`[[zaizao:fail]]` 回 HTTP 500，`[[zaizao:notag]]` 不带标签，`[[zaizao:think]]` 带推理内容，`[[zaizao:empty]]` 只回 `<plan>`。
脚本步骤：`tool`+`args` 一个调用；`calls` 一次回复多个调用（各自 index/id，用于并行执行）；
`raw_args`/`same_index` 构造拼接参数；`delay` 推迟回复的秒数；`null_noise` 先在流里发中转常见的噪声
（`data: null`、`reasoning_details`/`annotations` 里的 null 元素）；`with_text` 让文字和工具调用出现在同一条回复里。
另有 `POST /v1/responses`（Responses API 流式，回 `mock responses reply #N: <最后一条用户消息>`）和
`POST /v1/images/generations`、`/v1/images/edits`（回一张 16×16 PNG）。
`shell_probe` / `apt_coreutils` / `coreutils_check` 在工作区里跑基础命令（PRoot 与发行版检查，
`apt_coreutils` 需要设备能联网）。压缩请求（最后一条用户消息是结构化总结指令「Stop the current task and write
a structured summary …」）回 `[Summary of previous conversation]` 加一句「summary ready (N user / M assistant messages)」
（数的是请求里指令之前的消息）；附加要求里放 `[[compress:slow]]` 推迟约 4 秒，`[[compress:tool]]` 改回一个工具调用
（模型没写摘要），`[[compress:fail]]` 回 HTTP 500。`mock-requests.jsonl` 每行带收到请求的
时间戳 `t`，`parallel_subagents`（两个子代理，各自请求延迟 2 秒回复）可据此判断两个子代理是否同时运行。

```bash
scripts/mock-openai/mockctl.sh start
adb shell am instrument -w -e class me.rerere.rikkihub.e2e.LocalMockEndToEndTest \
  me.rerere.rikkihub.debug.test/androidx.test.runner.AndroidJUnitRunner
scripts/mock-openai/mockctl.sh stop
```

## models-dev/ — 模型能力快照

`update.py` 从 https://models.dev/api.json 重新生成 `app/src/main/assets/models_dev.json`（图片输入/输出、工具调用、
推理、上下文长度；同一型号在各家提供商下的记录按多数投票合并，持平算支持）。App **不联网刷新**，要更新就跑脚本再发版；
下载不完整（条目 < 1000）时拒绝覆盖旧快照。手写规则（`ModelRegistry`）优先，快照只补规则没覆盖的型号。

```bash
python3 scripts/models-dev/update.py                  # 下载并写入
python3 scripts/models-dev/update.py --input api.json # 用已保存的 api.json
python3 scripts/models-dev/update.py --check          # 快照与将写入的内容不一致时退出码 1
python3 -m unittest discover -s scripts/models-dev    # 脚本自身的测试
```
