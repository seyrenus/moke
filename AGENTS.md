# AGENTS.md

面向在本仓库工作的编码代理（AI 助手或人类协作者）的项目指南。

## 项目概述

Moke（墨客）是一个原生 Android **SSH / mosh 终端客户端**（Kotlin + Jetpack Compose）。用户在应用内直连远程服务器，运行 shell、tmux、vim 或全屏 TUI（如 Claude Code）。终端解析与渲染复用 termux 的 `terminal-emulator` / `terminal-view`（Apache-2.0，vendored），自研部分集中在连接层（SSH / mosh / SFTP）与产品 UI。

- 环境要求：**JDK 17** + Android SDK（compileSdk / targetSdk 35），`local.properties` 指向 SDK（`sdk.dir=...`）
- minSdk 24；mosh 原生组件仅打包 **arm64-v8a**
- 单 Activity（`MainActivity`）+ 纯 Compose；导航基于 `sealed interface Screen` 的状态切换，未用 Navigation Compose
- 状态管理以 Kotlin Flow / `StateFlow` 为主，无第三方状态库
- 双语 i18n：默认英文 `values/strings.xml`，中文 `values-zh/strings.xml`；默认跟随系统语言，可在设置中切换（`LocaleManager`）

## 常用命令

```bash
./gradlew assembleStandardDebug   # 构建 standard 变体 debug APK（CI 只验证此变体）
./gradlew testDebugUnitTest       # 全部 JVM 单测（app + terminal-emulator + terminal-view）
./gradlew lintStandardDebug       # Android Lint（发版前必跑，任务名随变体生成）

# maple 变体自带 Maple Mono 作为中文回退字体；字体（OFL，~20MB）不入库，先取再编：
./scripts/fetch-maple-font.sh && ./gradlew assembleMapleDebug
```

- CI（`.github/workflows/ci.yml`）= `assembleStandardDebug` + `testDebugUnitTest`；改完代码至少跑这两步。
- mosh 原生产物（GPLv3）不入库，由 `scripts/build-mosh-native.sh`（mosh 1.4.0，需 NDK r29）从公开源码可复现构建；本地 debug 包若缺 `libmosh-client.so`，mosh 连接会在运行时给出可读提示而不是崩溃。
- 发布签名经 `MOKE_KEYSTORE` 等 GitHub secrets 注入；本地无该环境变量时 release 回退 debug 签名，便于出包。

## 模块结构

| 模块 | 包名 | 说明 |
|---|---|---|
| `app/` | `com.briqt.moke` | 产品层：Compose UI、会话编排、SSH/mosh/SFTP 传输实现 |
| `terminal-emulator/` | `com.termux.emulator` | vendored 终端解析/状态核心；本地 PTY(JNI) 已移除，改由 app 层经 `TerminalTransport` 接入 |
| `terminal-view/` | `com.termux.view` | vendored 终端渲染 View，未修改（仅少量向后兼容的行距/字距微调） |

`app/` 内的分层（`src/main/java/com/briqt/moke/`）：

- `terminal/` — 连接与会话核心：`SshTransport`（sshj）、`MoshTransport`（native mosh-client 子进程）、`SessionManager` / `TermSession`（跨页面存活的会话）、`SshConnector`（TOFU 主机密钥、跳板机）、`Tmux`（远端 tmux 侧通道管理）、字体管理（`FontRepository` / `FontCatalog`）
- `terminal/sftp/` — 远端文件管理：`SftpSession`、`TransferManager`（含断点续传）、`TransferStore`
- `data/` — 持久化与模型：`HostStore` / `SettingsStore` / `UiPrefs`（DataStore，JSON）、`CredentialCrypto`（Android Keystore AES-GCM 加密凭据）、`Host`
- `ui/` — 各屏（Home / HostEdit / Terminal / Files / Fonts / Appearance / About…）、`MokeViewModel`（应用级状态中枢）、`theme/`
- `update/` — GitHub Releases 更新检查（默认只认正式版，可选含预览版）

## 架构要点

### 传输层抽象（最重要的扩展点）

`terminal-emulator` 的 `TerminalTransport`（Java 接口）是把任意字节流通道（SSH shell / mosh / 预览）接到 `TerminalSession` 的接缝，**实现全部在 app 层**：

- 实现自管读线程，远端字节 → `session.processToEmulator(...)`；结束 → `session.onTransportFinished(...)`
- `write` 可能在 UI 线程被调用，实现内部必须缓冲或走独立写线程（现有实现用单线程 `writeExec`）
- `exec(command)`：带外执行控制命令（tmux 面板等），SSH 复用现有连接、mosh 按需另起 SSH 控制连接
- 现有实现：`SshTransport`（sshj + TOFU + 心跳 + 跳板机 direct-tcpip）、`MoshTransport`（sshj 引导 `mosh-server new` → 本地 forkpty 子进程跑 `libmosh-client.so`，UDP 漫游）、`PreviewTransport`（外观实时预览）

### 会话生命周期

会话（`TermSession`）持有 transport / emulator / 滚屏历史，**跨页面存活**；终端页进入时重建 `TerminalView` 并 attach 到既有 session。`MokeSessionService`（foregroundServiceType=specialUse）后台保活连接；`MokeTransferService`（dataSync）后台保活传输。

### 数据流

UI（Compose）← collect ← `MokeViewModel`（StateFlow 组合）→ 调用 `SessionManager` / 各 Store。持久化在 DataStore 中以 JSON 字符串存储；主机凭据落盘前经 `CredentialCrypto` 加密。

## 约定与雷区

- **vendored 代码不可动核心行为**：`terminal-view/` 与 `terminal-emulator/` 是 termux 上游代码，行为改动放 app 层或经 `TerminalTransport` 扩展；确需改 vendored 代码时保持向后兼容并在文件头注释说明。
- **单测用真的 `org.json`**（见 `gradle/libs.versions.toml` 注释）：Android 打包的那份在 JVM 单测中只是抛异常的桩；主机表/任务表的 JSON 往返有测试钉住，改动持久化格式必须同步更新测试。
- **UI 字符串双语都要加**：新增文案同时写入 `values/strings.xml` 与 `values-zh/strings.xml`。
- **代码注释以中文为主**，且偏重解释“为什么”与协议/平台约束（跟随现有风格）；`kotlin.code.style=official`。
- **提交信息用 [Conventional Commits](https://www.conventionalcommits.org/)**。
- **发布规则**（发版工作流会强制校验）：`versionName` = tag 去掉前导 `v`（如 `v0.1.20` → `0.1.20`）；`versionCode` 每个对外 APK 严格递增（预发布之后的正式版也要更高）；打 tag 前更新 `CHANGELOG.md`、跑单测、assemble + lint。stable 用 `vX.Y.Z`，预发布带 SemVer 后缀（如 `vX.Y.Z-rc.1`）。
- **对外只接受 issue、不接受 PR**（见 CONTRIBUTING）；这不影响本地开发与提交。
- standard 与 maple 两个 flavor 同 `applicationId`、同源（差异仅 `BuildConfig.BUNDLE_MAPLE` + 内置字体），编译等价；平时只需构建/测试 standard。

## 关键背景知识

- `Host.startupCommand`（SSH `exec` / `mosh-server -- …` 协议级启动）与 `loginCommand`（shell 起来后往 PTY 敲一行）语义不同，勿混淆；startupCommand **不加 sh 包装**（Windows OpenSSH 走 cmd/powershell）。
- tmux 附加前要用会话协商出的 `negotiatedTerm`（部分主机缺 `xterm-256color` 定义会导致 tmux 拒绝启动）。
- mosh 不传滚动缓冲，滑动滚屏在 mosh 下的行为由 tmux 鼠标模式接管（见设置项）与 CHANGELOG 0.1.19 的设计说明。
- 全屏 TUI（vim、Claude Code）下的滑动方向键/滚轮判定集中在设置 `ScrollMode`，改动前先读 CHANGELOG 中相关修复记录，避免回归。
