# NFD Client

本地网盘分享链接客户端。粘贴分享链接，在本机解析出文件和下载地址，然后下载，并可以查看进度、暂停和继续。

当前包含两个客户端：

- `desktop/`：Windows 及其他桌面平台的 JavaFX 客户端，直接依赖 Maven Central 上的 `cn.qaiu:parser:10.2.6`。
- `android/`：Kotlin 客户端。解析使用 `cn.qaiu:parser-android`。下载引擎优先使用 aria2（JSON-RPC），没有 aria2 二进制时使用同一套暂停/继续接口的内置传输。

解析在客户端进程内完成，不调用、不部署现有的线上服务。仓库里没有生产环境的 Cookie、Token 或账号。

## 依赖版本

| 客户端 | 坐标 | 版本 | 修改位置 |
| --- | --- | --- | --- |
| 桌面 | `cn.qaiu:parser` | **10.2.6** | `desktop/pom.xml` 的 `parser.version` |
| Android | `cn.qaiu:parser-android` | **10.2.5** | `android/gradle/libs.versions.toml` 的 `parserAndroid` |

2026-11-26 核对过 Maven Central：

- `cn.qaiu:parser:10.2.6` 已可从 `https://repo1.maven.org/maven2/cn/qaiu/parser/10.2.6/` 解析。
- `parser-android` 最新仍是 10.2.5，没有 10.2.6。Android 模块因此固定 10.2.5，避免整个客户端被一个尚未发布的构件挡住。两个客户端的解析调用都写在 `shared/`，接口与 10.2.6 相同的部分可以一起升级。

不要自行向 Maven Central 发布或覆盖 `parser` / `parser-android`。10.2.5 保持不动。

## 解析后怎么下载

两个客户端使用同一套调用：

1. `ParserCreate.fromShareUrl` 识别分享链接，提取码可以写在输入框里。
2. `parseFileList` 列出文件。文件夹行的 `parserUrl` 带有 `dirId` 等参数，进入文件夹时写回 `otherParam`。
3. 文件行的 `parserUrl` 里带有解析库自己生成的 `/v2/redirectUrl/{type}/{param}`。客户端用 `CommonUtils.urlBase64Decode` 还原 `paramJson`，再调用 `parseById`。
4. 单文件分享如果没有这个 token，就退回 `parse`。
5. 直链和 `downloadHeaders` 从 `ShareLinkInfo.otherParam` 读取，交给下载器。请求头会原样带上，这样需要 Referer 或 Cookie 的直链才能下。

这些 `parserUrl` 只是解析库编码下载参数的方式，客户端不会去请求线上的 NFD 服务。

## 桌面客户端

环境：JDK 17 或以上（打包脚本按 JDK 21 编写），Maven 3.8 或以上。JavaFX 21 由 Maven 拉取，不需要单独安装。

```bash
cd desktop
mvn test
mvn javafx:run
```

`mvn package` 会生成 `desktop/target/nfd-desktop/`：

- `nfd-desktop.jar`
- `lib/`：解析库及其依赖
- `javafx/`：当前系统的 JavaFX 模块
- `nfd-client.sh`、`nfd-client.bat`

Linux / macOS：

```bash
sh target/nfd-desktop/nfd-client.sh
```

Windows 可以双击或在该目录运行 `nfd-client.bat`。生成带运行时的应用目录（已安装 WiX 时还会尝试 exe 安装包）：

```powershell
cd desktop
powershell -ExecutionPolicy Bypass -File scripts\package-windows.ps1
```

Linux/macOS 的 app-image：

```bash
sh desktop/scripts/package-app-image.sh
```

产物在 `desktop/target/dist/NFDClient`。

下载文件默认在 `~/Downloads/nfd`（没有 Downloads 目录时用 `~/nfd-downloads`）。暂停会停掉当前连接并保留 `.nfdpart`，继续时用 HTTP Range 接着写。

## Android 客户端

环境：JDK 17、Android SDK（compileSdk 35）、网络。可以用 Android Studio，也可以用仓库里的 Gradle Wrapper。

```bash
cd android
# 若没有 local.properties，设置 ANDROID_HOME 指向 SDK
./gradlew :share:test :engine:test
./gradlew :app:assembleDebug
```

调试 APK：`android/app/build/outputs/apk/debug/app-debug.apk`。

### 为什么不用 Termux 当架构

Android 10 以后，应用私有目录通常不能再执行临时解压出来的二进制。Termux 能绕过这件事，但要用户再装一个应用，并且自己启动 aria2，不适合作为默认路径。

本客户端的默认路径是标准做法：把 aria2 编成各 ABI 的可执行文件，命名为 `libaria2c.so`，放到

```text
android/app/src/main/jniLibs/<abi>/libaria2c.so
```

系统会把它安装到可执行的 `nativeLibraryDir`。应用启动后通过 JSON-RPC（仅监听 127.0.0.1，密钥随机）调用 `aria2.addUri`、`aria2.pause`、`aria2.unpause` 和 `aria2.tellStatus`。

这些 `.so` 没有放进仓库。aria2 是 GPL-2.0，和本仓库的 MIT 代码分开分发；这里只通过进程调用它，不把 aria2 链进 APK 的 Java/Kotlin 代码。自行编译后把文件放到上面的目录再打包即可。没有这个文件时，界面会改用内置传输，暂停和继续仍然可用，状态里会写明当前不是 aria2。

如果已经在 Termux 或其他地方跑了 aria2，可以在界面里打开「连接本机 aria2 RPC」，填写主机、端口和密钥。同一台手机上的 loopback 是共享的，Termux 里示例：

```bash
aria2c --enable-rpc=true --rpc-listen-all=false --rpc-listen-port=6800 --rpc-secret=你的密钥
```

这是可选项，不是安装要求。

下载目录是应用外部文件目录下的 `Download`，不申请全部存储权限。下载进行时会尝试启动前台服务；拒绝通知权限后，进度仍显示在应用内，但离开应用后系统可能回收进程。

## 本环境验证过什么

在这台 Linux 环境里实际跑过：

- `desktop` 的 `mvn test`：6 个分享链接识别测试（不访问网盘）和 1 个本地 HTTP 暂停/继续测试，文件内容与原文一致。
- `mvn package` 后运行 `cn.qaiu.nfd.desktop.Check`，输出 `夸克网盘 / qk`，说明打出来的目录能加载 `parser` 10.2.6。接着用模块路径启动了 JavaFX 主窗口，进程保持运行直到被主动结束，没有在界面上点选。
- `./gradlew :share:test`：同一套识别测试，跑在 `parser-android` 10.2.5 上。
- `./gradlew :engine:test`：内置传输的暂停/继续，以及本机 `aria2c` 1.37 通过 JSON-RPC 对本地 HTTP 服务的暂停/继续。
- `./gradlew :app:assembleDebug` 成功，调试 APK 已生成。

没有在真实 Windows 桌面、真机或模拟器上操作过界面，也没有跑过 `package-windows.ps1`。Windows 安装包和 APK 的点击路径需要在对应系统上再确认。在线解析取决于网盘本身，自动化测试不访问网盘，也不访问任何线上 NFD 服务。

## 目录

```text
shared/     两个客户端共用的解析会话
desktop/    JavaFX 桌面客户端
android/    Android 工程（share、engine、app）
```

## 许可

MIT，见仓库根目录的 `LICENSE`。aria2 本体若由打包者另行放入 APK，遵循 aria2 自己的 GPL-2.0。
