# Android 内置解析版

内置版适用于 Android 8.0 及以上的 ARM64 或 x86_64 设备。打开 APK 后，应用会在本机启动裁剪后的 Android OpenJDK 21 和解析服务，并复用 `cn.qaiu:parser:10.2.6`。现有 Termux 版仍可按根目录 README 构建，用于 32 位设备及已有 Termux 下载任务。

内置版的下载任务和网盘登录数据保存在应用自己的目录。下载完成后可在「下载」页用「另存为」导出到共享目录。历史记录仍使用原有应用数据。内置版与 Termux 版使用同一应用 ID，切换时需要安装签名一致的包。各自的守护进程数据留在原有存储位置。

应用进入前台时会自动启动引擎。「设置 → 查看运行日志」可查看和复制启动阶段、JVM 退出码及可用的故障位置。日志只保存在当前设备。引擎遇到原生崩溃时会自动尝试一次兼容模式。

ARM64 启动器会在加载 HotSpot 前为引擎子进程关闭 Android 堆指针标签，避免 JVM 释放去标签指针时被 Bionic 终止。

支持 HTTP Range 的大文件使用 aria2c 多连接下载，可在设置中调整每个任务的连接数。队列保留 aria2 的暂停和续传记录。需要 Cookie 或特殊请求头的链接，以及不支持 Range 的链接，由 Java 下载器处理。Termux 版使用 `pkg install aria2` 提供的命令。

## 构建

Linux 构建机需要 JDK 21、Android SDK 35、Android NDK r27d、CMake 3.22+、Ninja 和 Go。每个 APK 只放对应架构的运行时。

```sh
abi=arm64-v8a
runtime="$(mktemp -d)"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.3.13750724"
android/runtime/build.sh "$abi" "$runtime"
(cd android && ./gradlew -PembeddedAbi="$abi" -PembeddedRuntimeDir="$runtime" \
  :core:test :daemon:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease)
android/runtime/verify-apk.py "android/app/build/outputs/apk/release/app-$abi-release.apk" "$abi" 10.2.6
```

JRE 使用 XZ 压缩，首次启动时解压到应用目录。体积检查以 100,000,000 字节为上限，计入 APK、首次启动解压的运行时和守护进程、提取的原生库以及 DEX 预留。打包检查也验证 APK 和 JRE 中每个 ELF 的 16 KiB 对齐。实际系统编译缓存和文件系统占用由具体设备决定。

## 解析回归

在未安装 Termux 的测试模拟器或设备上启动内置版，确认服务已就绪，然后使用 APK 内的运行时自检。离线测试使用真实解析器和本地 HTTP 样本，覆盖蓝奏提取码、取文件直链后重开目录、分页游标及跨页 ID。设置 `NFD_TEST_ARIA2C` 为本机 aria2c 路径后，`:core:test` 也会验证多连接、暂停、进程重启后续传和最终内容。

```sh
android/runtime/device-smoke.sh "android/app/build/outputs/apk/debug/app-$abi-debug.apk" \
  "android/app/build/outputs/apk/release/app-$abi-release.apk"
NFD_TEST_LIVE=1 android/runtime/device-smoke.sh "android/app/build/outputs/apk/debug/app-$abi-debug.apk"
```

在专用测试设备上可加 `NFD_TEST_RECOVERY=1`，验证 JVM 收到 SIGABRT 后兼容模式能自动接管。

在线用例取自 parser 上游，覆盖蓝奏分页和单文件回退、带提取码的目录、城通目录和 PDF 直链，以及夸克访客直链。在线模式要求这些公共样本全部通过。公共分享变更、地区限制或服务端限流也会使在线用例失败。登录解析的线上覆盖需要测试账号，已有账号配置单元测试可独立运行。 [上游测试](https://github.com/qaiu/netdisk-fast-download/tree/4361d96c52da5c67da3be2638a14a46f99f06d39/parser/src/test/java/cn/qaiu/parser/impl)

APK 中的运行时许可和源码见 [第三方声明](NOTICE.md)。公开分发内置版时，同时提供由 `android/runtime/source-bundle.sh nfd-android-runtime-source.tar` 生成的对应源码包。
