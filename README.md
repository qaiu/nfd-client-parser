# NFD Client

开源的网盘分享链接下载客户端：粘贴分享链接 → 列出文件 → 多线程下载，支持进度、暂停、继续、断点续传和直链过期自动重新解析。

- **桌面端**（Windows / Linux / macOS）：JavaFX，进程内直接调用 Maven Central 上的 `cn.qaiu:parser:10.2.6`，安装包自带 Java 运行时。
- **Android 端**：Kotlin + Compose 界面。解析和下载由运行在 **Termux 默认 OpenJDK** 上的守护进程完成，守护进程同样使用标准的 `cn.qaiu:parser:10.2.6`，不再使用停在 10.2.5 的 `parser-android`。

所有解析都在用户自己的设备上完成，不调用、不依赖任何线上 NFD 服务；仓库和安装包里没有任何账号、Cookie 或 Token。

## 目录

- [下载安装](#下载安装)
- [架构与产物矩阵](#架构与产物矩阵)
- [Android + Termux 使用说明](#android--termux-使用说明)
- [桌面端使用说明](#桌面端使用说明)
- [从源码构建](#从源码构建)
- [版本与发布流程](#版本与发布流程)
- [已知限制](#已知限制)

## 下载安装

正式版本在 [Releases](https://github.com/qaiu/nfd-client-parser/releases)。每个 PR 和 `main` 的构建也会在 Actions 运行页面的 **Artifacts** 里提供同样的文件（需要登录 GitHub 才能下载）。

| 文件 | 用途 |
| --- | --- |
| `nfd-android-<版本>-arm64-v8a.apk` | 绝大多数 Android 手机（64 位 ARM） |
| `nfd-android-<版本>-armeabi-v7a.apk` | 32 位 ARM 旧手机 |
| `nfd-android-<版本>-x86_64.apk` / `-x86.apk` | 模拟器、少数 x86 平板 |
| `nfd-android-<版本>-universal.apk` | 不确定 CPU 时用这个，包含全部 ABI |
| `nfd-termux-daemon-<版本>.tar.gz` | Termux 守护进程。APK 里已内置，单独下载只用于手动安装 |
| `NFDClient-<版本>-windows-x64.zip` / `.msi` | Windows 10/11 x64；Windows on Arm 也用这个（x64 仿真） |
| `NFDClient-<版本>-linux-x64.tar.gz` / `.deb` | Linux x86_64 |
| `NFDClient-<版本>-linux-arm64.tar.gz` / `.deb` | Linux aarch64 |
| `NFDClient-<版本>-macos-arm64.dmg` / `.zip` | Apple Silicon Mac |
| `NFDClient-<版本>-macos-x64.dmg` / `.zip` | Intel Mac |
| `SHA256SUMS.txt` | 所有文件的 SHA-256（仅 Release 中提供） |

PR 构建的 Artifacts 按平台分组：`android-apks`、`termux-daemon`、`desktop-windows-x64`、`desktop-linux-x64`、`desktop-linux-arm64`、`desktop-macos-arm64`、`desktop-macos-x64`。

## 架构与产物矩阵

```text
                 ┌──────────────── 桌面端（单进程） ────────────────┐
分享链接 ──────▶ │ JavaFX 界面 → shared/ShareSession → parser 10.2.6 │
                 │            → core/DownloadManager（多连接下载）   │
                 └───────────────────────────────────────────────────┘

                 ┌── Android App ──┐  127.0.0.1:17890   ┌──────── Termux ────────┐
分享链接 ──────▶ │ Compose 界面     │ ── HTTP + 令牌 ──▶ │ nfd-termux 守护进程      │
                 │ (只做界面和控制) │                    │ OpenJDK 21 (pkg 安装)   │
                 └─────────────────┘                    │ parser 10.2.6 + core    │
                                                        │ → Download/nfd          │
                                                        └─────────────────────────┘
```

两端共用两份纯 Java 代码：

- `shared/`：`ShareSession`（识别链接、列目录、进入文件夹、解析直链、重新解析）、`ParserRuntime`（初始化 parser 需要的 Vert.x）、`ShareErrors`（把解析异常转成中文提示）、`SelfTest`（打包后的运行时自检）。
- `core/`：无第三方依赖的下载核心。按 Range 分段多连接下载、`.nfdpart` 断点续传、任务状态持久化、网络错误指数退避重试、直链 401/403/410 或返回网页时自动重新解析、连续超时时换一个新解析的直链、`If-Range`/`Content-Range` 校验防止文件在续传时被替换、`Content-Disposition` 文件名、Windows 非法文件名处理和同名不覆盖。

CI 实际构建并测试的矩阵：

| 平台 | 架构 | 产物 | CI 运行环境 | CI 验证内容 |
| --- | --- | --- | --- | --- |
| Android | arm64-v8a | APK（按 ABI 拆分） | ubuntu-latest | 单元测试；APK 签名校验；内置守护进程包完整；只含 arm64 本地库 |
| Android | armeabi-v7a、x86_64、x86、universal | APK | ubuntu-latest | 同上 |
| Termux | aarch64 | 守护进程 tar.gz | ubuntu-24.04-arm + `termux/termux-docker:aarch64` | `pkg` 安装 openjdk-21 → `install.sh` → `nfd-termux self-test` → 启动守护进程并调用 API |
| Termux | x86_64 | 同一个 tar.gz | ubuntu-latest + `termux/termux-docker:x86_64` | 同上；另跑一次在线列目录（依赖网络，失败不阻断） |
| Windows | x64 | zip（便携）、msi | windows-latest | 打包前后各跑一次 `--self-test`（解析库、GBK、TLS、Nashorn、Vert.x、本地下载） |
| Windows | arm64 | 使用 x64 包 | windows-11-arm | 在 x64 仿真下运行 x64 包的 `--self-test`（不阻断） |
| Linux | x64 | tar.gz、deb | ubuntu-22.04 | `--self-test` |
| Linux | arm64 | tar.gz、deb | ubuntu-22.04-arm | `--self-test` |
| macOS | arm64 | dmg、zip | macos-latest | `--self-test` |
| macOS | x64 | dmg、zip | macos-15-intel | `--self-test` |

Termux 的 armv7（`arm`）和 i686 也有 openjdk-21 包，守护进程是纯 Java，理论上可用，但 CI 没有对应的 termux-docker 测试。

## Android + Termux 使用说明

### 为什么用 Termux

`cn.qaiu:parser` 依赖 Vert.x、Netty、Nashorn（JavaScript 解析器）和 JDK 的 `javax.script`，这些不能在 Android 的 ART 上原样运行，所以以前需要单独维护 `parser-android`，而它停在了 10.2.5。Termux 的 `openjdk-21` 是完整的 OpenJDK（aarch64/arm/x86_64/i686 都有构建），可以直接运行 Maven Central 上未经修改的 `cn.qaiu:parser:10.2.6`，以后 parser 升级只需改一个版本号。CI 在 termux-docker 里用 Termux 官方仓库的 OpenJDK 21.0.13 跑过完整自检，包括在线解析。

代价是需要额外安装 Termux。作为交换，下载也放到了守护进程里：App 被系统杀掉后下载仍会继续，手机上不再需要 aria2（以前的 aria2 方案已移除，仓库保持纯 MIT，不分发 GPL 二进制）。

### 首次使用

1. 安装 Termux：从 [F-Droid](https://f-droid.org/packages/com.termux/) 或 [GitHub Releases](https://github.com/termux/termux-app/releases) 安装。**Google Play 上的 Termux 版本过旧，无法安装软件包。**
2. 安装并打开 NFD App，进入「连接」页：
   - 勾选「允许 App 一键启动/停止」（推荐），点「生成安装命令」→「复制并打开 Termux」。
   - 在 Termux 中长按粘贴并回车。命令形如：

     ```bash
     curl -fsSL http://127.0.0.1:<随机端口>/<随机路径>/install.sh | bash -s -- --source http://127.0.0.1:<随机端口>/<随机路径> --token <令牌> --port 17890 --allow-app-control
     ```

     App 在前台服务里用 127.0.0.1 临时提供安装脚本和守护进程包（随机端口、随机路径，15 分钟后或 Termux 下载完成后自动关闭），不需要访问外网下载守护进程。
   - 脚本会执行 `pkg install openjdk-21`（首次约 130MB 下载）、安装守护进程到 `~/.nfd/app`、请求存储权限（系统弹窗点「允许」），然后启动守护进程。
3. 回到 App 点「重新检测」，显示「已连接」后即可在「解析」页粘贴链接。

下载的文件保存在手机的 **内部存储/Download/nfd**。如果没有授予存储权限，会保存在 Termux 私有目录 `~/.nfd/downloads`，App 会提示。

### 日常使用

- 手机重启或 Termux 被关闭后，在 App 里点「一键启动」，或在 Termux 中运行 `nfd-termux start`。
- 「一键启动」使用 Termux 的 `RUN_COMMAND` 接口，需要安装时加 `--allow-app-control`（会在 `~/.termux/termux.properties` 写入 `allow-external-apps = true`），并在 App 首次请求时授予「在 Termux 环境中运行命令」权限。
- App 支持从其他应用「分享」文本到 NFD，会自动提取链接和提取码。

`nfd-termux` 命令：

| 命令 | 说明 |
| --- | --- |
| `nfd-termux start` / `stop` / `restart` | 启动、停止、重启守护进程（启动时会申请 `termux-wake-lock`） |
| `nfd-termux status` | 查看运行状态 |
| `nfd-termux logs [行数]` | 查看日志（`~/.nfd/daemon.log`） |
| `nfd-termux token [新令牌]` | 查看或修改令牌（重装 App 后令牌不一致时用） |
| `nfd-termux self-test [--online]` | 自检 Java、parser、编码、TLS、Nashorn、Vert.x；`--online` 再实际解析一个公开分享 |
| `nfd-termux version` | 版本 |
| `nfd-termux uninstall` | 删除程序（保留任务记录、配置和已下载文件） |

### 手动安装守护进程（不经过 App）

```bash
pkg install -y openjdk-21 curl
curl -fLO https://github.com/qaiu/nfd-client-parser/releases/download/v<版本>/nfd-termux-daemon-<版本>.tar.gz
tar -xzf nfd-termux-daemon-<版本>.tar.gz
bash nfd-termux/bin/install.sh --bundle nfd-termux-daemon-<版本>.tar.gz
nfd-termux token   # 把这个令牌填到 App「连接」页的高级设置里
```

### 安全设计

- 守护进程默认只监听 `127.0.0.1:17890`。除 `/api/health` 的最小信息外，所有接口都要求请求头 `X-NFD-Token`（常量时间比较）。
- 监听回环地址时拒绝 `Host` 不是 localhost 的请求（防 DNS rebinding），不返回 CORS 头，请求体限制 256KB。
- 配置文件 `~/.nfd/daemon.properties` 权限为 600。任务记录里保存分享链接和提取码，用于直链过期后重新解析，同样只在本机。
- 如需让局域网电脑上的守护进程给手机用，可以用 `--bind 0.0.0.0` 启动，此时只靠令牌保护，请只在可信网络中使用。

## 桌面端使用说明

1. 粘贴分享链接（也可以粘贴整段分享文本，会自动提取链接和「提取码：xxxx」），回车或点「解析」。窗口获得焦点时，如果剪贴板里有分享链接且输入框为空，会自动填入（不会自动解析）。也可以把链接拖进窗口。
2. 双击文件夹进入，用「上一级」或面包屑返回（已打开过的目录不会重新请求）；双击文件直接下载，或多选后点「下载所选」，「全部下载」下载当前目录的所有文件。
3. 单文件分享或列不出目录时，用「直接下载」。
4. 右侧列表显示进度、速度、剩余时间；可以暂停、继续、重试、打开文件、在文件夹中显示、移除（可选同时删除文件）。
5. 「设置」里可以改下载目录、同时下载的任务数和每个任务的连接数。

关闭窗口时未完成的任务会保存，下次打开自动继续。数据位置：

| 系统 | 任务记录 | 默认下载目录 |
| --- | --- | --- |
| Windows | `%LOCALAPPDATA%\NFDClient` | `%USERPROFILE%\Downloads\nfd` |
| macOS | `~/Library/Application Support/NFDClient` | `~/Downloads/nfd` |
| Linux | `$XDG_DATA_HOME/nfd-client`（默认 `~/.local/share/nfd-client`） | `~/Downloads/nfd` |

首次打开未签名的安装包：

- **macOS**：右键点 `NFDClient.app` → 打开，或执行 `xattr -dr com.apple.quarantine /Applications/NFDClient.app`。
- **Windows**：SmartScreen 提示时点「更多信息」→「仍要运行」。
- **Linux**：`sudo apt install ./NFDClient-<版本>-linux-x64.deb`，或解压 tar.gz 后运行 `NFDClient/bin/NFDClient`。deb 在 Ubuntu 22.04 上构建，适用于 Ubuntu 22.04+ / Debian 12+。

排查问题时可以在终端运行 `NFDClient --self-test`（Windows：`NFDClient.exe --self-test --report report.txt`），会检查打包的运行时是否完整。

## 从源码构建

### 桌面端

需要 JDK 17+（打包需要带 `jpackage` 的 JDK，CI 用 Temurin 21）和 Maven 3.8+。JavaFX 由 Maven 按当前平台拉取。

```bash
cd desktop
mvn test            # core、shared 和桌面端的测试
mvn javafx:run      # 直接运行
scripts/package.sh  # 生成当前平台的便携包和安装包，产物在 desktop/target/dist/
```

`package.sh` 会先在普通 classpath 上跑一次 `--self-test`，再用 `jpackage` 生成自带运行时的应用并对它再跑一次自检，通过后才生成 zip/tar.gz 和 msi/deb/dmg。Windows 上在 Git Bash 中运行；生成 msi 需要 WiX 3（`choco install wixtoolset`），没有时只生成 zip。Linux 生成 deb 需要 `dpkg-deb` 和 `fakeroot`。

`mvn package` 还会生成 `desktop/target/nfd-desktop/`，里面的 `nfd-client.sh` / `nfd-client.bat` 可以用系统里的 Java 17+ 直接运行。

### Android 与 Termux 守护进程

需要 JDK 17+ 和 Android SDK（compileSdk 35）。设置 `ANDROID_HOME` 或在 `android/local.properties` 中写 `sdk.dir`。

```bash
cd android
./gradlew :core:test :daemon:test :app:testDebugUnitTest   # 测试
./gradlew :app:assembleRelease                              # app/build/outputs/apk/release/app-<abi>-release.apk
./gradlew :daemon:daemonDist                                # daemon/build/dist/nfd-termux-daemon-<版本>.tar.gz
```

`android/daemon` 模块编译 `termux/` 和 `shared/` 的源码并打成守护进程包，`:app` 构建时会把它和 `install.sh` 放进 APK 的 `assets/termux/`。

没有配置签名时，release APK 使用调试密钥签名，可以安装测试，但不适合分发。正式签名通过环境变量提供：`NFD_KEYSTORE_FILE`、`NFD_KEYSTORE_PASSWORD`、`NFD_KEY_ALIAS`、`NFD_KEY_PASSWORD`。

版本号：`versionName` 来自仓库根目录的 `VERSION`；`versionCode = (主×10000 + 次×100 + 修订) × 10 + ABI 序号`（universal 0、armeabi-v7a 1、arm64-v8a 2、x86 3、x86_64 4），同一版本的各 ABI 包可以同时上架。

### parser 版本

| 位置 | 版本 |
| --- | --- |
| `desktop/pom.xml` 的 `parser.version` | 10.2.6 |
| `android/gradle/libs.versions.toml` 的 `parser`（Termux 守护进程） | 10.2.6 |

parser 发布新版本后，改这两处并跑一遍 CI 即可。本仓库不向 Maven Central 发布任何构件。

## 版本与发布流程

版本号只有一个来源：根目录的 `VERSION`（格式 `X.Y.Z`）。`desktop/pom.xml` 的 `<revision>` 默认值需要与它一致，CI 会检查。

CI（`.github/workflows/build.yml`）：

- **PR 和 push 到 `main`**：跑全部测试，构建上面矩阵里的所有产物并做自检，作为 Actions Artifacts 上传（保留 90 天）。
- **推送标签 `vX.Y.Z`**（必须等于 `VERSION`）：在上述全部任务成功后，创建正式 GitHub Release 并上传所有文件和 `SHA256SUMS.txt`。
- **推送标签 `vX.Y.Z-后缀`**（如 `v0.2.0-rc.1`）：同样构建，但创建的是**草稿 + 预发布**，需要人工在网页上确认后才公开。
- 标签与 `VERSION` 不一致时，发布任务直接失败。

人工发布一个正式版本：

1. 在合并到 `main` 的 PR 里修改 `VERSION` 和 `desktop/pom.xml` 的 `<revision>`，例如都改为 `0.3.0`。
2. （可选）在仓库 Settings → Secrets → Actions 中配置 Android 签名：`NFD_KEYSTORE_BASE64`（`base64 -w0 release.jks` 的结果）、`NFD_KEYSTORE_PASSWORD`、`NFD_KEY_ALIAS`、`NFD_KEY_PASSWORD`。不配置时 APK 为调试签名，Release 说明里应注明。
3. 先打预发布标签验证：

   ```bash
   git checkout main && git pull
   git tag v0.3.0-rc.1
   git push origin v0.3.0-rc.1
   ```

   等 Actions 全部通过，在 Releases 页面检查草稿里的文件，必要时下载安装试用。

4. 打正式标签：

   ```bash
   git tag v0.3.0
   git push origin v0.3.0
   ```

   CI 通过后 Release 自动发布。若某个任务失败，修复后可以在 Actions 页面重新运行；已存在的 Release 会用 `--clobber` 覆盖同名文件。

## 已知限制

- **签名**：Windows 和 macOS 包都没有代码签名，macOS 也没有公证，首次打开需要手动放行（见上文）。Android 包在没有配置签名密钥时使用调试签名，不同构建机的调试签名不同，不能互相覆盖安装。
- **Windows on Arm**：JavaFX 没有发布 win-aarch64 的本地库，所以没有原生 arm64 包。x64 包在 Windows 11 on Arm 上通过系统 x64 仿真运行，CI 会在 windows-11-arm 上做自检，但该任务不阻断构建。
- **Android 依赖 Termux**：必须另装 Termux（F-Droid/GitHub 版），首次需要下载约 130MB 的 OpenJDK。Android 12+ 的后台进程限制（phantom process killer）和厂商省电策略可能结束 Termux 里的进程，长时间下载建议在系统设置中关闭 Termux 的电池优化；`nfd-termux start` 会自动申请 wake lock，被结束后任务会在下次启动时继续。
- **Android ABI**：APK 按 arm64-v8a、armeabi-v7a、x86_64、x86 拆分，App 本身只有 Compose 的一个小本地库，ABI 主要影响包名和 versionCode。Termux 守护进程是纯 Java，跟随 Termux 自身的架构。
- **CI 覆盖**：termux-docker 不是真机，CI 只验证 Termux 的 OpenJDK、安装脚本和守护进程 API；Termux 存储权限、`RUN_COMMAND` 授权和 App 界面需要在真机上确认。桌面端的界面在 Linux 上做过手工验证，Windows/macOS 只做了打包后的无界面自检。
- **网盘侧**：能否解析取决于网盘本身和 parser 的支持情况；部分网盘限制同一直链的并发连接数，遇到失败可以在设置里把每任务连接数调低。

## 目录

```text
core/       下载核心（纯 Java 17，无依赖），两端共用
shared/     解析会话、parser 运行时初始化、错误提示、自检，两端共用
desktop/    JavaFX 桌面端（Maven），scripts/package.sh 打包
termux/     Termux 守护进程源码、nfd-termux 与 install.sh、CI 冒烟测试
android/    Gradle 工程：:core、:daemon（打包 termux/）、:app（Compose 界面）
VERSION     版本号
```

## 许可

MIT，见 `LICENSE`。运行时依赖（parser、Vert.x、Netty、Nashorn、JavaFX、OpenJDK 等）各自遵循其许可证；仓库和安装包中不包含 aria2 等 GPL 程序。
