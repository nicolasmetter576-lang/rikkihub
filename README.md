# Rikkihub

Rikkihub 是一个原生 Android AI 聊天客户端，基于开源项目 [RikkaHub](https://github.com/rikkahub/rikkahub) 二次开发。

> [!IMPORTANT]
> **这是 RikkaHub 的修改版本，不是官方版本。**
> 本项目与 RikkaHub 官方及其原作者无关，也未获得其认可。使用本项目遇到的问题请在本仓库反馈，**不要**提交到 RikkaHub 官方仓库。但不保证处理，也不保证会看。

## 来源与修改声明

- **原项目**：[RikkaHub](https://github.com/rikkahub/rikkahub)，作者 [re-ovo](https://github.com/re-ovo) 及 RikkaHub 贡献者，以 [GNU AGPL-3.0](https://github.com/rikkahub/rikkahub/blob/master/LICENSE) 许可证发布。
- **基线**：RikkaHub 官方 2.5.1 版本的源码。
- **修改**：自 2026 年 9 月起由 [nicolasmetter576-lang](https://github.com/nicolasmetter576-lang) 修改，修改内容见下文“与 RikkaHub 的主要区别”；完整修改记录见本仓库的提交历史。
- 包名改为 `me.rerere.rikkihub`，应用名改为 Rikkihub 系列，可以与官方 RikkaHub 同时安装。

## 与 RikkaHub 的主要区别

在 RikkaHub 原有功能的基础上，本项目主要增加或修改了以下内容：

- **内嵌 Web 端增强**：在网页端管理助手、提供商、扩展、技能、记忆、工作区和存储，提供独立翻译页、压缩历史、收藏，Web 聊天页支持专注模式、编辑器字体与排版设置；可开启 HTTPS 和访问密码。
- **新增提供商与登录方式**：Codex、Devin、Antigravity（Google 账号 OAuth，多账户轮询）；新增 DuckDuckGo 搜索。
- **Claude Code 请求形态**：可选开关，把 Claude `/messages` 请求改写为 Claude Code 客户端的形态。
- **Prompt 缓存保活**：可选开关，在对话间隙低成本续期提供商的 prompt cache。
- **工作区增强**：持久 bash、子代理、工具调用并行执行、工作目录限定、压缩包解压、文件移动与复制路径、编辑器自动保存，以及 Markdown 语义编辑。
- **创造助手**：在对话中通过提案创建和修改助手等配置，需要用户批准后才会生效，支持审计和撤销。
- **助手系统提示词分块编辑**：可在整块和分块两种模式间切换，分块可排序、单独开关。
- **推敲**：在工作区 Markdown 文件中选中文字，按词、句、段三个档位生成改写候选，一键替换并可撤销。
- 以及若干问题修复和界面调整。

## 构建

### 环境要求

- JDK 21
- Android SDK（`compileSdk` 37）
- Node.js 20.19 或更高版本，pnpm 10 或更高版本（用于构建内嵌 Web 前端）

### 准备

```bash
git clone https://github.com/nicolasmetter576-lang/rikkihub.git
cd rikkihub
(cd web-ui && pnpm install --frozen-lockfile)
```

还需要准备以下文件。它们包含私人配置，已被 `.gitignore` 忽略，**不要提交到仓库**：

- `app/google-services.json`：从你自己的 Firebase 项目下载。
- `local.properties`：写入 `sdk.dir`；构建 release 版本时还要写入签名配置：

  ```properties
  sdk.dir=/path/to/Android/Sdk
  storeFile=release.keystore
  storePassword=...
  keyAlias=...
  keyPassword=...
  ```

### 打包

| 命令 | 应用名 | applicationId |
|---|---|---|
| `./gradlew :app:assembleRelease` | RikkiRana | `me.rerere.rikkihub` |
| `./gradlew :app:assembleDebug` | RikkiUmi | `me.rerere.rikkihub.debug` |
| `./gradlew :app:assembleDebug -Prikkihub.coexist=true` | RikkiTomo | `me.rerere.rikkihub.subagent.debug` |

## 许可证

本项目以 [GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0）发布，与原项目 RikkaHub 相同。

```
Rikkihub — a modified version of RikkaHub
Copyright (C) re-ovo and RikkaHub contributors
Copyright (C) 2026 nicolasmetter576-lang (modifications)

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published
by the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

根据 AGPL-3.0：如果你分发本项目的 APK，或让他人通过网络使用本项目（例如内嵌 Web 端），需要向这些用户提供对应的完整源代码。
