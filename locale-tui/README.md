# locale-tui

Android 语言文件翻译管理 TUI 工具。

## 功能

- 模块选择界面
- 翻译表格显示所有语言
- AI 自动翻译缺失条目
- Dead entry 检测和过滤
- 搜索过滤
- 编辑和删除条目

## 快捷键

| 快捷键 | 功能 |
|--------|------|
| `Enter` | 选择/编辑 |
| `t` | AI翻译模块全部缺失条目（只暂存，按 `s` 保存） |
| `Ctrl+t` | 确认后重新翻译当前行已有的目标译文 |
| `c` | 取消正在运行的翻译 |
| `d` | 切换Dead Entry过滤 |
| `m` | 切换Missing过滤 |
| `/` | 聚焦搜索框 |
| `Delete` | 暂存删除条目（按 `s` 后才修改文件） |
| `s` | 保存更改 |
| `r` | 刷新数据 |
| `Escape` | 取消翻译；否则返回（未保存时先确认） |
| `q` | 退出 |

## 运行

```bash
cd locale-tui
uv run python src/main.py
```

## 测试 AI 连接

```bash
cd locale-tui
uv run python src/main.py test-connection
```

## 配置

编辑 `config.yml` 配置模块列表、语言列表和翻译设置。原配置无需迁移；新增字段均可省略。

可以在每种目标语言下选择性添加 `glossary`，例如（仅示例，不会自动改写你的配置）：

```yaml
languages:
  - code: values
    name: English
    is_source: true
  - code: values-zh
    name: Chinese (Simplified)
    glossary:
      Prompt: 提示词
      Context: 上下文
```

术语表会追加到该语言的原有 prompt，按语言代码优先解析，避免同名语言混用。它是模型的**提示约束**，不是正确译法或实际遵循术语的证明；最终译文仍需人工审核。保留用户的模型、prompt、语言和资源配置。`translation.batch_size` 必须为正整数，术语表必须为非空字符串到非空字符串的映射。

在 `.env` 文件中配置 OpenAI API：

```
OPENAI_API_KEY=your_api_key
OPENAI_BASE_URL=https://api.openai.com/v1
```

## 批量补全与重新翻译

```bash
# 离线预览，无需 API key、不创建客户端、不写文件
uv run python src/main.py translate-missing -m app -l values-ja --dry-run
uv run python src/main.py retranslate -m app -l values-zh --match '\\bprompts?\\b' --dry-run

# 以下是实际翻译命令，会请求已配置的服务；按需自行运行
uv run python src/main.py translate-missing -m app -l values-ja --concurrency 4 --retries 3
uv run python src/main.py retranslate -m app -l values-zh --key system_prompt --key prompt_template
```

`-m/--module` 和 `-l/--lang` 可重复，省略时遍历全部配置模块/目标语言。源语言保持 Android 的 `values` 约定。补全包括不存在、空或仅空白的目标条目，跳过空源文及源/目标声明 `translatable="false"` 的条目。重新翻译仅选已有非空目标条目，必须指定 `--key` 或 `--match`（忽略大小写的源文正则）；两者同时使用时取并集，不会补齐缺失条目。

并发默认 8，最多尝试默认 3 次（`--retries` 是总尝试次数，不是额外重试次数）；SDK 隐式重试关闭，单请求超时 60 秒。只重试失败的键；限制、JSON 对象/键、空值、Java 格式占位符、换行和 XML 内联标记/属性均有离线校验，文本引号自动进行 Android 转义。进度表示已处理键数（包括最终失败），不表示全成功。校验失败不替换旧值；CLI 会列出失败键并以非零状态退出。

### 保存与取消边界

- CLI 每个语言文件完成后仅补丁更新成功的 string 节点，保留已有顺序、属性、其他字符串、plurals、arrays、注释及内联 spans。文件内采用同目录临时文件、fsync、原子替换；**不是跨文件事务**。取消/保存异常后明确报告已完成文件数；当前未完成文件不保存，先前文件保留。
- TUI AI 结果只暂存在内存中。当前语言完成前不应用部分批次结果；`c` 或翻译期间 `Escape` 会取消并等待子任务退出。先前已完成语言保留为未保存修改；不会自动保存，也不会把取消当作全成功。编辑/删除/刷新/保存不能与翻译同时执行。
- `s` 只写修改节点，预检源文/目标译文以及加载时文件指纹。删除同样先暂存；刷新/返回丢弃未保存修改须确认。逐文件保存失败时，已保存文件保留，未完成修改仍在内存中，可修复错误后重试。
- 已存在 XML 损坏、重复 string 名称、DOCTYPE、符号链接写入以及翻译期间源/目标文件被外部修改都会拒绝覆盖。TUI 加载损坏文件会显示错误并禁用编辑/翻译/保存，修复后按 `r` 重新加载。
- XML 序列化可规范化声明、实体和所改节点的命名空间格式，但不会整体重建或排序资源树；无变化文件保持字节不变。乐观指纹检查不是文件锁，不能保证检查/replace 瞬间与外部写入完全互斥。
- 这里只处理 `<string>`，不自动翻译 plurals/arrays。校验偏保守，不等同于 Android aapt 验证，也不证明译文质量。最终人工审核和应用构建另行执行。

## 默认离线测试

```bash
uv run pytest -q
# 无 uv 时，在本目录虚拟环境中运行同等命令
.venv/bin/python -m pytest -q
.venv/bin/ruff check --isolated --select E4,E7,E9,F src tests
.venv/bin/python -m compileall -q src tests
```

`tests/test_translator.py` 是真实服务质量测试，**默认在应用/SDK/config 导入之前整模块跳过**。只有显式设置 `LOCALE_TUI_RUN_LIVE=1` 才启用（可能付费；不属于离线验收）。其余测试使用临时配置/资源及内存假 API，默认禁止 socket 连接和 DNS；不会调用翻译服务或改写用户配置/项目语言资源。
