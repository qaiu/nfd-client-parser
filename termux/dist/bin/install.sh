#!/usr/bin/env bash
# Installs or updates the NFD daemon inside Termux.
#
# From the Android app (it serves this script and the bundle on 127.0.0.1 for a few minutes):
#   curl -fsSL http://127.0.0.1:PORT/KEY/install.sh | bash -s -- --source http://127.0.0.1:PORT/KEY --token TOKEN
# From a downloaded release bundle:
#   tar -xzf nfd-termux-daemon-X.Y.Z.tar.gz && bash nfd-termux/bin/install.sh --bundle nfd-termux-daemon-X.Y.Z.tar.gz --token TOKEN
# Add --mirror china to download OpenJDK from Termux's mainland China mirror group (TUNA, USTC, Aliyun, ...).
#
# Everything runs inside main() so `curl | bash` has read the whole script before pkg can touch stdin.

main() {
    set -euo pipefail
    local source="${NFD_SOURCE:-}" bundle="" token="" port="" mirror="" allow_app=0 skip_pkg=0 skip_storage=0 no_start=0
    while [ $# -gt 0 ]; do
        case "$1" in
            --source) source="$2"; shift 2 ;;
            --bundle) bundle="$2"; shift 2 ;;
            --token) token="$2"; shift 2 ;;
            --port) port="$2"; shift 2 ;;
            --mirror) mirror="$2"; shift 2 ;;
            --allow-app-control) allow_app=1; shift ;;
            --skip-packages) skip_pkg=1; shift ;;
            --skip-storage) skip_storage=1; shift ;;
            --no-start) no_start=1; shift ;;
            *) echo "未知参数: $1" >&2; exit 2 ;;
        esac
    done

    local nfd_home="${NFD_HOME:-$HOME/.nfd}"
    local tmp="${TMPDIR:-/tmp}/nfd-install.$$"
    mkdir -p "$tmp"
    trap "rm -rf '$tmp'" EXIT

    # 1. Fetch the bundle first: the app only serves it while its installer screen is open.
    if [ -z "$bundle" ]; then
        if [ -z "$source" ]; then
            echo "缺少 --source 或 --bundle" >&2
            exit 2
        fi
        say "下载守护进程安装包"
        curl -fSL --retry 3 -o "$tmp/nfd.tar.gz" "$source/nfd-termux-daemon.tar.gz"
        bundle="$tmp/nfd.tar.gz"
    fi
    tar -tzf "$bundle" >/dev/null

    # 2. Runtime packages. aria2 is optional because the daemon has an HTTP downloader.
    if [ "$skip_pkg" -eq 0 ]; then
        if command -v pkg >/dev/null 2>&1 && { ! java_ok || ! command -v aria2c >/dev/null 2>&1; }; then
            local pkg_opts=()
            if [ "$mirror" = china ]; then
                use_china_mirrors && pkg_opts=(--check-mirror)
            elif [ -n "$mirror" ]; then
                echo "未知镜像: $mirror（可用: china）" >&2
                exit 2
            fi
            if ! java_ok; then
                say "安装 OpenJDK 21: pkg install openjdk-21（首次约 200MB，请耐心等待）"
                DEBIAN_FRONTEND=noninteractive pkg "${pkg_opts[@]}" install -y openjdk-21 </dev/null
            fi
            if ! command -v aria2c >/dev/null 2>&1; then
                say "安装 aria2 多连接下载器"
                DEBIAN_FRONTEND=noninteractive pkg "${pkg_opts[@]}" install -y aria2 </dev/null \
                    || say "aria2 安装失败，将使用内置下载器"
            fi
        fi
        java_ok || { echo "未找到 Java 17 或更新版本，请先安装 OpenJDK" >&2; exit 1; }
    fi

    # 3. Swap in the new version; tasks, config and downloads live outside app/.
    say "安装到 $nfd_home/app"
    mkdir -p "$nfd_home"
    if [ -x "$nfd_home/app/bin/nfd-termux" ]; then
        "$nfd_home/app/bin/nfd-termux" stop >/dev/null 2>&1 || true
    fi
    rm -rf "$nfd_home/app.new"
    mkdir -p "$nfd_home/app.new"
    tar -xzf "$bundle" -C "$nfd_home/app.new" --strip-components=1
    chmod +x "$nfd_home/app.new/bin/"*
    # Termux has no /usr/bin/env; termux-exec usually rewrites it, but not in every shell.
    if command -v termux-fix-shebang >/dev/null 2>&1; then
        termux-fix-shebang "$nfd_home/app.new/bin/"*
    fi
    rm -rf "$nfd_home/app.old"
    if [ -d "$nfd_home/app" ]; then
        mv "$nfd_home/app" "$nfd_home/app.old"
    fi
    mv "$nfd_home/app.new" "$nfd_home/app"
    rm -rf "$nfd_home/app.old"

    local bin_dir="${PREFIX:-$HOME/.local}/bin"
    mkdir -p "$bin_dir"
    ln -sf "$nfd_home/app/bin/nfd-termux" "$bin_dir/nfd-termux"

    # 4. Token and port shared with the app.
    local conf="$nfd_home/daemon.properties"
    touch "$conf"
    chmod 600 "$conf"
    if [ -n "$token" ]; then
        set_prop "$conf" token "$token"
    fi
    if [ -n "$port" ]; then
        set_prop "$conf" port "$port"
    fi

    # 5. Shared storage, so files land in Download/nfd instead of Termux's private home.
    if [ "$skip_storage" -eq 0 ] && [ ! -d "$HOME/storage/downloads" ] && command -v termux-setup-storage >/dev/null 2>&1; then
        say "请求存储权限：请在系统弹窗中点「允许」，文件将保存到 内部存储/Download/nfd"
        termux-setup-storage || true
        local i
        for i in $(seq 1 20); do
            [ -d "$HOME/storage/downloads" ] && break
            sleep 1
        done
    fi

    # 6. Optional: let the NFD app start/stop the daemon through Termux's RUN_COMMAND intent.
    if [ "$allow_app" -eq 1 ]; then
        local props="$HOME/.termux/termux.properties"
        mkdir -p "$HOME/.termux"
        touch "$props"
        if grep -Eq '^[[:space:]]*allow-external-apps[[:space:]]*=' "$props"; then
            sed -i -E 's/^[[:space:]]*allow-external-apps[[:space:]]*=.*/allow-external-apps = true/' "$props"
        else
            printf '\nallow-external-apps = true\n' >>"$props"
        fi
        if command -v termux-reload-settings >/dev/null 2>&1; then
            termux-reload-settings || true
        fi
        say "已开启 allow-external-apps：NFD App 可在授权后一键启动/停止守护进程"
    fi

    if [ "$no_start" -eq 0 ]; then
        "$nfd_home/app/bin/nfd-termux" start
    fi
    say "完成。回到 NFD App 点「重新检测」。常用命令: nfd-termux start | stop | status | logs"
}

say() {
    printf '\033[1;32m==>\033[0m %s\n' "$*"
}

# Same switch termux-change-repo makes: pkg then picks a reachable mirror from the official
# chinese_mainland group (mirrors.tuna.tsinghua.edu.cn, mirrors.ustc.edu.cn, mirrors.aliyun.com, ...).
use_china_mirrors() {
    local etc="${PREFIX:-/data/data/com.termux/files/usr}/etc"
    if [ -d "$etc/termux/mirrors/chinese_mainland" ]; then
        if [ -L "$etc/termux/chosen_mirrors" ]; then
            unlink "$etc/termux/chosen_mirrors"
        fi
        if [ ! -e "$etc/termux/chosen_mirrors" ]; then
            ln -s "$etc/termux/mirrors/chinese_mainland" "$etc/termux/chosen_mirrors"
            say "已切换到 Termux 官方的中国大陆镜像组（可随时运行 termux-change-repo 修改）"
            return 0
        fi
        echo "保留已有的镜像选择（$etc/termux/chosen_mirrors）" >&2
        return 1
    fi
    # Older Termux without mirror groups: point apt at TUNA directly.
    say "已切换到清华大学 TUNA 镜像（可随时运行 termux-change-repo 修改）"
    echo "deb https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main stable main" >"$etc/apt/sources.list"
    export TERMUX_PKG_NO_MIRROR_SELECT=1
    apt update </dev/null || true
    return 1
}

java_ok() {
    command -v java >/dev/null 2>&1 || return 1
    java -version 2>&1 | grep -Eq 'version "(1[7-9]|[2-9][0-9])'
}

set_prop() {
    local file="$1" key="$2" value="$3"
    if grep -q "^$key=" "$file"; then
        sed -i "s|^$key=.*|$key=$value|" "$file"
    else
        printf '%s=%s\n' "$key" "$value" >>"$file"
    fi
}

main "$@"
