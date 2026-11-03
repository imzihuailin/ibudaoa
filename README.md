# ibudaoa 我步道啊

一个自动生成校园跑记录的 APP

## 安装

- 使用下方一键命令生成 APK 后安装。
-支持 Android 10 及以上、arm64-v8a 设备。

## 使用与限制

-完成引导设置后就可以开始用了，看不懂就问 AI 。
-需要配置自己的 API Key。


## 数据与权限

定位用于确定起点及地图展示。临时 VPN 用于用户主动发起的账号连接，需要 Android 系统授权。
协议所需的最小认证材料通过 Android Keystore 加密保存在应用私有存储；应用禁用系统备份。
账号和运动请求发送至对应平台，地图、定位及路径规划调用高德 SDK。

高德 SDK 的数据处理见[高德隐私政策](https://lbs.amap.com/pages/privacy/)。使用地图服务时请遵守高德的授权及隐私要求。

## 一键生成 APK

无需 Git、Java 或 Android Studio。每次执行都会下载默认分支最新源码；工具、Key 和签名会保留供下次使用。首次运行需要联网下载工具并接受 Android SDK 许可：**y 同意，N 拒绝**。

支持 Windows x64、Linux x64、macOS Intel / Apple Silicon。请在普通用户的终端执行，无需管理员权限。macOS / Linux 使用系统的 Bash、curl 和 tar。

**Windows：打开 PowerShell，粘贴整行命令。**

```powershell
& { $p = Join-Path ([IO.Path]::GetTempPath()) ('ibudaoa-' + [guid]::NewGuid() + '.ps1'); try { Invoke-WebRequest -UseBasicParsing 'https://raw.githubusercontent.com/imzihuailin/ibudaoa/HEAD/scripts/oneclick.ps1' -OutFile $p; powershell -NoProfile -ExecutionPolicy Bypass -File $p build } finally { Remove-Item -LiteralPath $p -Force -ErrorAction SilentlyContinue } }
```

**macOS / Linux：打开终端，粘贴整行命令。**

```sh
bash -c 'set -e; p=$(mktemp); trap '\''rm -f "$p"'\'' EXIT; curl -fL --retry 2 https://raw.githubusercontent.com/imzihuailin/ibudaoa/HEAD/scripts/oneclick.sh -o "$p"; bash "$p" build'
```

按提示粘贴 Key 并回车，输入内容隐藏。已有 Key 时可直接回车复用。Key 必须与应用包名及脚本显示的签名匹配；格式校验通过不代表地图鉴权已通过。

完成后显示可点击的 APK 完整路径，不自动打开文件夹。终端不支持链接时可复制路径。APK 位于系统“下载”目录下的 `ibudaoa/ibudaoa.apk`；无法识别下载目录时放到用户主目录的 `ibudaoa-output`。将 APK 传到手机安装即可。

网络或编译失败时，处理终端显示的问题后重新执行同一命令。脚本不会将旧 APK 报为本次构建成功。

Windows 中途退出后，直接重新执行同一命令即可：残留旧锁会自动处理，未完成的工具下载和解压会重新准备，已有 Key、签名和完整缓存继续保留。另一个构建仍在运行时会提示等待，不会同时覆盖文件。

### 文件位置与备份

| 内容 | Windows | macOS / Linux |
| --- | --- | --- |
| Key、签名和配置 | `%LOCALAPPDATA%\ibudaoa\config` | `~/.local/share/ibudaoa/config` |
| 源码和编译产物 | `%LOCALAPPDATA%\ibudaoa\work` | `~/.cache/ibudaoa/work` |
| 工具和下载缓存 | `%LOCALAPPDATA%\ibudaoa\tools` | `~/.cache/ibudaoa/tools` |
| Gradle 缓存 | `%LOCALAPPDATA%\ibudaoa\gradle` | `~/.cache/ibudaoa/gradle` |

**请备份整个 `config` 文件夹。** 后续覆盖升级、换电脑构建都需要原签名。签名不会上传或云端备份。其余缓存可以重新下载。

### 一键清理

删除本工具保存的源码、工具、缓存、Key、签名及输出 APK，不影响电脑原有的开发环境或手机上已安装的应用。**删除签名后，新签名生成的 APK 无法覆盖原来的安装；如需继续升级，请先备份 `config`。**

Windows PowerShell：

```powershell
& { $p = Join-Path ([IO.Path]::GetTempPath()) ('ibudaoa-' + [guid]::NewGuid() + '.ps1'); try { Invoke-WebRequest -UseBasicParsing 'https://raw.githubusercontent.com/imzihuailin/ibudaoa/HEAD/scripts/oneclick.ps1' -OutFile $p; powershell -NoProfile -ExecutionPolicy Bypass -File $p uninstall } finally { Remove-Item -LiteralPath $p -Force -ErrorAction SilentlyContinue } }
```

macOS / Linux：

```sh
bash -c 'set -e; p=$(mktemp); trap '\''rm -f "$p"'\'' EXIT; curl -fL --retry 2 https://raw.githubusercontent.com/imzihuailin/ibudaoa/HEAD/scripts/oneclick.sh -o "$p"; bash "$p" uninstall'
```

输入 `y` 确认全部删除；直接回车或输入 `N` 取消。输出文件夹中的其他文件会保留；APK 被替换时停止清理并提示。清理失败会显示残留位置，可处理后重试。不会删除自行备份或复制到其他位置的文件，也不清除终端历史等系统记录。

已经下载的入口也可直接执行 `bash scripts/oneclick.sh uninstall` 或 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/oneclick.ps1 uninstall`。缓存中的执行程序和 Java 仍完整时，清理无需再次下载源码。

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
