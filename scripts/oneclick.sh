#!/usr/bin/env bash
# Linux Bash entrypoint. No Git, Python or jq required.
set -euo pipefail
umask 077
mode=${1:-build}
case "$mode" in build|uninstall) ;; *) echo '用法：oneclick.sh [build|uninstall]' >&2; exit 1;; esac
case "$(uname -s):$(uname -m)" in
  Linux:x86_64) platform=linux; arch=x64;;
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
sha256() {
  local result
  if command -v sha256sum >/dev/null; then result=$(sha256sum "$1"); else result=$(shasum -a 256 "$1"); fi
  printf '%s' "${result%% *}"
}
fetch() {
  local url=$1 destination=$2 expected=${3:-} attempt
  [[ "$url" == https://* ]] || return 1
  for attempt in 1 2; do
    echo "下载文件：${destination##*/}；来源：${url#https://}（尝试 $attempt/2）"
    if curl --fail --location --connect-timeout 15 --speed-limit 1 --speed-time 30 --max-time 1800 \
        --proto '=https' --proto-redir '=https' --tlsv1.2 "$url" -o "$destination.part"; then
      if [ -z "$expected" ] || [ "$(sha256 "$destination.part")" = "$expected" ]; then
        mv -f "$destination.part" "$destination"
        return 0
      fi
      echo '文件校验失败，丢弃本次下载。' >&2
    fi
    rm -f "$destination.part"
    echo '下载失败或长时间无进展，正在重试或切换来源。' >&2
  done
  return 1
}
get_revision() {
  fetch 'https://api.github.com/repos/imzihuailin/ibudaoa/commits?per_page=1' "$scratch/commit.json"
  revision=$(sed -n 's/^[[:space:]]*"sha"[[:space:]]*:[[:space:]]*"\([a-f0-9]*\)".*/\1/p' "$scratch/commit.json" | head -1)
  [[ "$revision" =~ ^[a-f0-9]{40}$ ]] || { echo '无法确定最新源码版本。' >&2; exit 1; }
}
echo 'ibudaoa 一键工具'
stage='查询最新版本'
revision=0000000000000000000000000000000000000000
if [ "$mode" = build ] || [ ! -f "$cache/OneClick.java" ]; then
  get_revision
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
    [ "$revision" != 0000000000000000000000000000000000000000 ] || get_revision
    fetch "https://raw.githubusercontent.com/imzihuailin/ibudaoa/$revision/scripts/download-manifest.properties" "$scratch/downloads.properties"
    property() { awk -F= -v key="$1" '$1 == key {sub(/^[^=]*=/, ""); sub(/\r$/, ""); print; exit}' "$scratch/downloads.properties"; }
    checksum=$(property "jdk.$platform.$arch.sha256")
    mirror=$(property "jdk.$platform.$arch.mirror")
    official=$(property "jdk.$platform.$arch.official")
    [[ "$checksum" =~ ^[a-f0-9]{64}$ ]] && [ -n "$mirror" ] && [ -n "$official" ] || { echo 'Java 下载清单异常。' >&2; exit 1; }
    downloaded=false
    for url in "$mirror" "$official"; do
      if fetch "$url" "$scratch/jdk.tar.gz" "$checksum"; then downloaded=true; break; fi
    done
    [ "$downloaded" = true ] || { echo 'Java 所有下载来源均失败。' >&2; exit 1; }
    mkdir "$scratch/jdk"
    tar -xzf "$scratch/jdk.tar.gz" -C "$scratch/jdk" --strip-components=1
    prepared="$scratch/jdk"
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
