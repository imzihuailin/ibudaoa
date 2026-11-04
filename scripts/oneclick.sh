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
cache="$HOME/.cache/ibudaoa"
lock="$HOME/.cache/.ibudaoa-oneclick.lock"
for parent in "$HOME" "$HOME/.cache" "$HOME/.local" "$HOME/.local/share" "$HOME/.local/share/ibudaoa"; do
  [ ! -L "$parent" ] || { echo "路径不能经过符号链接：$parent" >&2; exit 1; }
done
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
assert_no_link() {
  local cursor=$1
  [[ "$cursor" == /* && "$cursor" != *$'\n'* && "$cursor" != *$'\r'* ]] || { echo '无效路径' >&2; return 1; }
  while [ "$cursor" != / ]; do
    [ ! -L "$cursor" ] || { echo "路径不能经过符号链接：$cursor" >&2; return 1; }
    cursor=$(dirname -- "$cursor")
  done
}
assert_owned() {
  assert_no_link "$1"
  assert_no_link "$1/.owner"
  [ -f "$1/.owner" ] && [ "$(cat "$1/.owner")" = "$owner" ] || { echo "不是本工具创建的目录：$1" >&2; return 1; }
}
offline_uninstall() {
  local config="$HOME/.local/share/ibudaoa/config" output='' apk fingerprint created answer
  assert_no_link "$config"
  if [ -e "$config" ]; then assert_owned "$config"; fi
  echo "将删除本工具保存的源码、工具、缓存、Key、签名及输出 APK。"
  echo "缓存目录：$cache"
  echo "配置目录：$config"
  for record in output.path output.sha256 output.created; do assert_no_link "$config/$record"; done
  if [ -f "$config/output.path" ]; then
    output=$(cat "$config/output.path")
    assert_no_link "$output"
    echo "输出 APK：$output/ibudaoa.apk"
  fi
  echo "删除签名后，将无法用新签名覆盖升级原来安装的应用。"
  echo "如需保留升级能力，请先备份整个配置目录：$config"
  read -r -p '确认全部删除？[y/N]：' answer || answer=''
  if [[ "$answer" != y && "$answer" != Y ]]; then echo '已取消，未删除文件。'; return; fi
  if [ -n "$output" ]; then
    apk="$output/ibudaoa.apk"
    assert_no_link "$apk"
    if [ -e "$apk" ]; then
      [ -f "$config/output.sha256" ] && [ "$(sha256 "$apk")" = "$(cat "$config/output.sha256")" ] || {
        echo "输出 APK 已被替换，保留该文件并停止清理：$apk" >&2; return 1;
      }
      rm -- "$apk"
    fi
    if [ -f "$config/output.created" ] && [ "$(cat "$config/output.created")" = "$output" ]; then rmdir -- "$output" 2>/dev/null || true; fi
  fi
  if [ -e "$config" ]; then assert_owned "$config"; rm -rf -- "$config"; fi
  assert_owned "$cache"
  rm -rf -- "$cache"
  echo '清理完成。'
}
echo 'ibudaoa 一键工具'
assert_owned "$cache"
if [ "$mode" = uninstall ]; then offline_uninstall; exit 0; fi
for tool in curl tar; do command -v "$tool" >/dev/null || { echo "缺少系统工具：$tool" >&2; exit 1; }; done
# Save the complete entry before any download; uninstall never needs Java or the network.
assert_no_link "$cache/uninstall.sh"
if [ "${BASH_SOURCE[0]}" != "$cache/uninstall.sh" ]; then cp -- "${BASH_SOURCE[0]}" "$cache/uninstall.sh"; fi
chmod 700 "$cache/uninstall.sh"
stage='下载最新源码'
echo '[1/5] 下载最新源码'
archive="$scratch/source.tar.gz"
source_url='https://github.com/imzihuailin/ibudaoa/archive/refs/heads/main.tar.gz'
source_ready=false
for prefix in 'https://ghfast.top/' 'https://gh-proxy.org/' ''; do
  if fetch "$prefix$source_url" "$archive"; then
    # Validate the complete table before extraction. Reject links and special files.
    if ! tar --quoting-style=escape -tzf "$archive" > "$scratch/members" ||
       ! tar --quoting-style=escape -tvzf "$archive" > "$scratch/types" ||
       ! awk '$0 !~ /^ibudaoa-main(\/|$)/ || $0 ~ /(^|\/)\.\.(\/|$)/ || index($0,"\\") {bad=1} END {exit bad}' "$scratch/members" ||
       ! awk 'substr($0,1,1)!="-" && substr($0,1,1)!="d" {bad=1} END {exit bad}' "$scratch/types"; then
      echo '源码包无效或含不安全路径，尝试下一个来源。' >&2; continue
    fi
    rm -rf -- "$scratch/source"
    mkdir "$scratch/source"
    if tar --no-same-owner --no-same-permissions -xzf "$archive" -C "$scratch/source" &&
       [ -f "$scratch/source/ibudaoa-main/gradlew" ] &&
       [ -f "$scratch/source/ibudaoa-main/app/build.gradle.kts" ] &&
       [ -f "$scratch/source/ibudaoa-main/scripts/download-manifest.properties" ] &&
       [ -f "$scratch/source/ibudaoa-main/scripts/OneClick.java" ]; then
      source_ready=true; break
    fi
    echo '源码包内容不完整，尝试下一个来源。' >&2
  fi
done
[ "$source_ready" = true ] || { echo '所有源码下载来源均失败。' >&2; exit 1; }
revision=$(sha256 "$archive")
assert_no_link "$cache/work"
rm -rf -- "$cache/work"
mv -- "$scratch/source" "$cache/work"
printf '%s' "$revision" > "$cache/work/source.sha256"
source="$cache/work/ibudaoa-main"
assert_no_link "$cache/OneClick.java"
cp -- "$source/scripts/OneClick.java" "$cache/OneClick.java"
stage='准备 Java 17'
jdk="$cache/tools/jdk"
assert_no_link "$jdk"
java_bin=''
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then java_bin="$JAVA_HOME/bin/java"; fi
if [ -z "$java_bin" ] && command -v javac >/dev/null && command -v java >/dev/null; then java_bin=$(command -v java); fi
if [ -n "$java_bin" ] && ! "$java_bin" -version 2>&1 | head -1 | grep -Eq 'version "17[.]'; then java_bin=''; fi
if [ -z "$java_bin" ]; then
  if [ ! -x "$jdk/bin/java" ]; then
    echo '正在下载 Java 17……'
    cp -- "$source/scripts/download-manifest.properties" "$scratch/downloads.properties"
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
stage='构建'
"$java_bin" -Dfile.encoding=UTF-8 "$cache/OneClick.java" build "$revision" "$cache"
