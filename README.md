# ibudaoa 我步道啊

一个自动生成校园跑记录的 APP （目前只支持武汉工程大学流芳校区）

截图

## 安装

- 下载安装向导，根据向导引导制作 APK 安装包
向导下载链接

## 终端安装命令

**Windows**

```powershell
& { $p = Join-Path ([IO.Path]::GetTempPath()) ('ibudaoa-' + [guid]::NewGuid() + '.ps1'); $u = 'https://raw.githubusercontent.com/imzihuailin/ibudaoa/HEAD/scripts/oneclick.ps1'; $ok = $false; try { foreach ($prefix in @('https://ghfast.top/', 'https://gh-proxy.org/', '')) { try { Invoke-WebRequest -UseBasicParsing -TimeoutSec 30 ($prefix + $u) -OutFile $p -ErrorAction Stop; $text = [IO.File]::ReadAllText($p); if ($text -notmatch '^\s*param\(' -or !$text.Contains('ibudaoa-oneclick-v1')) { throw '入口内容无效' }; $ok = $true; break } catch { Write-Host '当前入口不可用，尝试下一个来源。' } }; if (!$ok) { throw '所有入口下载来源均失败。' }; powershell -NoProfile -ExecutionPolicy Bypass -File $p build } finally { Remove-Item -LiteralPath $p -Force -ErrorAction SilentlyContinue } }
```

**Linux**

```sh
bash -c 'set -e; p=$(mktemp); trap '\''rm -f "$p"'\'' EXIT; u=https://raw.githubusercontent.com/imzihuailin/ibudaoa/HEAD/scripts/oneclick.sh; ok=false; for prefix in https://ghfast.top/ https://gh-proxy.org/ ""; do if curl -fL --retry 1 --connect-timeout 15 --max-time 60 "$prefix$u" -o "$p" && [ "$(head -n 1 "$p")" = "#!/usr/bin/env bash" ] && grep -q ibudaoa-oneclick-v1 "$p"; then ok=true; break; fi; done; [ "$ok" = true ] || { echo 所有入口下载来源均失败 >&2; exit 1; }; bash "$p" build'
```

脚本展示包名和 SHA-1 后，去[高德控制台](https://console.amap.com/dev/key/app)，添加 Key 将 SHA-1 填入发布安全码SHA1 ，并提交创建 Key （其他的按图片里填），在终端按回车继续。
<img width="1890" height="1137" alt="image" src="https://github.com/user-attachments/assets/c4c011bb-ae4b-4bd8-be31-c43929347c40" />


### 文件位置

| 内容        | Windows                         | Linux                           |
| --------- | ------------------------------- | ------------------------------- |
| Key、签名和配置 | `%LOCALAPPDATA%\ibudaoa\config` | `~/.local/share/ibudaoa/config` |
| 源码和编译产物   | `%LOCALAPPDATA%\ibudaoa\work`   | `~/.cache/ibudaoa/work`         |
| 工具和下载缓存   | `%LOCALAPPDATA%\ibudaoa\tools`  | `~/.cache/ibudaoa/tools`        |
| Gradle 缓存 | `%LOCALAPPDATA%\ibudaoa\gradle` | `~/.cache/ibudaoa/gradle`       |

### 卸载

Windows PowerShell：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:LOCALAPPDATA\ibudaoa\uninstall.ps1" uninstall
```

Linux：

```sh
bash "$HOME/.cache/ibudaoa/uninstall.sh" uninstall
```


## 手动源码构建（开发者）

需要 JDK 17、Android SDK 36、Build Tools 35.0.0；使用仓库中的 Gradle Wrapper。
在不入库的 `local.properties` 中配置 `sdk.dir`、`amap.android.key` 和 `project.repository.url`。
地图 Key 应绑定构建使用的包名及签名。
当前构建脚本读取 `.local/signing/debug.keystore`，该名称为历史遗留；需自行准备兼容脚本签名配置的本地密钥。请安全保管自己的签名密钥。

```sh
./gradlew :app:testReleaseUnitTest :app:assembleRelease :app:lintRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`。项目仅维护不可调试的 release 变体。

## 许可

项目原创代码采用 [GPL-3.0-only](LICENSE)。第三方组件遵循各自许可，详见 [第三方说明](THIRD_PARTY_NOTICES.md)。GPL 不授予第三方平台或地图服务的使用授权。
