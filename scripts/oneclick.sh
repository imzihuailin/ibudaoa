#!/usr/bin/env bash
# Bash 3.2+ (including the macOS system Bash). No Git, Python or jq required.
set -euo pipefail
umask 077
mode=${1:-build}
case "$mode" in build|uninstall) ;; *) echo '用法：oneclick.sh [build|uninstall]' >&2; exit 1;; esac
case "$(uname -s):$(uname -m)" in
  Linux:x86_64) platform=linux; arch=x64;;
  Darwin:x86_64) platform=mac; arch=x64;;
  Darwin:arm64) platform=mac; arch=aarch64;;
  *) echo '暂不支持此系统或架构。' >&2; exit 1;;
esac
for tool in curl tar; do
  command -v "$tool" >/dev/null || { echo "缺少系统工具：$tool" >&2; exit 1; }
done
cache="$HOME/.cache/ibudaoa"
lock="$HOME/.cache/.ibudaoa-oneclick.lock"
mkdir -p "$HOME/.cache"
if ! mkdir "$lock" 2>/dev/null; then
  echo "另一个构建或清理正在执行。若上次被强制终止，请确认没有任务运行，再删除：$lock" >&2
  exit 1
fi
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"; rmdir "$lock" 2>/dev/null || true' EXIT
trap 'exit 130' INT TERM
stage='准备入口'
trap 'echo "失败阶段：$stage。请检查网络或上述错误，再执行同一命令重试。" >&2' ERR
owner='ibudaoa-oneclick-v1'
if [ "$mode" = uninstall ] && [ ! -e "$cache" ] && [ ! -e "$HOME/.local/share/ibudaoa/config" ]; then
  echo '没有需要清理的构建文件。'
  exit 0
fi
if [ -L "$cache" ]; then echo '缓存目录不能是符号链接。' >&2; exit 1; fi
if [ -e "$cache" ]; then
  [ -f "$cache/.owner" ] && [ ! -L "$cache/.owner" ] && [ "$(cat "$cache/.owner")" = "$owner" ] || {
    echo "目录不是本工具创建的，停止以保护原文件：$cache" >&2; exit 1;
  }
else
  mkdir "$cache"
  printf '%s' "$owner" > "$cache/.owner"
fi
fetch() { curl --fail --location --retry 2 --connect-timeout 20 --max-time 900 --proto '=https' --tlsv1.2 "$1" -o "$2"; }
echo 'ibudaoa 一键工具'
stage='查询最新版本'
revision=0000000000000000000000000000000000000000
if [ "$mode" = build ] || [ ! -f "$cache/OneClick.java" ]; then
  fetch 'https://api.github.com/repos/imzihuailin/ibudaoa/commits?per_page=1' "$scratch/commit.json"
  revision=$(sed -n 's/^[[:space:]]*"sha"[[:space:]]*:[[:space:]]*"\([a-f0-9]*\)".*/\1/p' "$scratch/commit.json" | head -1)
  [[ "$revision" =~ ^[a-f0-9]{40}$ ]] || { echo '无法确定最新源码版本。' >&2; exit 1; }
fi
stage='准备 Java 17'
jdk="$cache/tools/jdk"
java_bin=''
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then java_bin="$JAVA_HOME/bin/java"; fi
if [ -z "$java_bin" ] && command -v javac >/dev/null && command -v java >/dev/null; then java_bin=$(command -v java); fi
if [ -n "$java_bin" ] && ! "$java_bin" -version 2>&1 | head -1 | grep -Eq 'version "17[.]'; then java_bin=''; fi
if [ -z "$java_bin" ]; then
  if [ ! -x "$jdk/bin/java" ]; then
    echo '正在下载 Java 17……'
    fetch "https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=$arch&image_type=jdk&os=$platform&vendor=eclipse" "$scratch/jdk.json"
    checksum=$(sed -n 's/^[[:space:]]*"checksum"[[:space:]]*:[[:space:]]*"\([a-f0-9]*\)".*/\1/p' "$scratch/jdk.json")
    url=$(sed -n 's/^[[:space:]]*"link"[[:space:]]*:[[:space:]]*"\(https:[^"]*\)".*/\1/p' "$scratch/jdk.json")
    [[ "$checksum" =~ ^[a-f0-9]{64}$ ]] && [[ "$url" == https://github.com/adoptium/* ]] || { echo 'Java 下载元数据异常。' >&2; exit 1; }
    fetch "$url" "$scratch/jdk.tar.gz"
    if command -v sha256sum >/dev/null; then actual=$(sha256sum "$scratch/jdk.tar.gz")
    else actual=$(shasum -a 256 "$scratch/jdk.tar.gz"); fi
    actual=${actual%% *}
    [ "$actual" = "$checksum" ] || { echo 'Java 下载校验失败。' >&2; exit 1; }
    mkdir "$scratch/jdk"
    tar -xzf "$scratch/jdk.tar.gz" -C "$scratch/jdk" --strip-components=1
    prepared="$scratch/jdk"
    [ "$platform" != mac ] || prepared="$scratch/jdk/Contents/Home"
    [ -x "$prepared/bin/java" ] && [ -x "$prepared/bin/javac" ]
    mkdir -p "$cache/tools"
    rm -rf "$jdk"
    mv "$prepared" "$jdk"
  fi
  java_bin="$jdk/bin/java"
fi
stage='下载构建与清理程序'
if [ "$mode" = uninstall ] && [ -f "$cache/OneClick.java" ]; then
  cp "$cache/OneClick.java" "$scratch/OneClick.java"
else
  fetch "https://raw.githubusercontent.com/imzihuailin/ibudaoa/$revision/scripts/OneClick.java" "$scratch/OneClick.java"
  cp "$scratch/OneClick.java" "$cache/OneClick.java"
fi
stage="$mode"
if "$java_bin" -Dfile.encoding=UTF-8 "$scratch/OneClick.java" "$mode" "$revision" "$cache"; then
  result=0
else
  result=$?
fi
if [ "$result" -eq 10 ]; then
  # The JVM has exited, so its own cached JDK can now be removed safely.
  [ ! -L "$cache" ] && [ "$(cat "$cache/.owner")" = "$owner" ]
  shopt -s dotglob nullglob
  for item in "$cache"/*; do
    [ "$item" = "$cache/.owner" ] || rm -rf -- "$item"
  done
  rm -f "$cache/.owner"
  rmdir "$cache"
  echo '清理完成。'
elif [ "$result" -ne 0 ]; then
  echo "失败阶段：$stage。请查看上述原因，重新执行同一命令重试。" >&2
  exit "$result"
fi
