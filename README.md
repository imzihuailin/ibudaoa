# ibudaoa

Kotlin + Jetpack Compose 编写的 Android 校园跑应用，包含账号连接、学校与道路同步、路线规划、自动任务执行和结果核验。

## 安装

本仓库仅提供源码，不提供预编译 APK。使用者需要自行配置高德地图 Key 和签名并构建。
支持 Android 10 及以上、arm64-v8a 设备。自行签名的 APK 无法覆盖使用其他签名安装的版本。

## 使用与限制

首次使用按“跑前热身”完成授权、账号连接和学校同步。后台运行需要通知权限及不受限制的省电设置；暂停后可继续或结束。
自动生成的路径和运动数据不代表设备真实观测。接口成功、本地完成不能替代平台对具体记录的有效性判定。
当前主要验证环境为武汉工程大学流芳校区、Redmi Note 14 Pro+ / Android 16；其他学校和系统兼容性未充分验证。
不支持需要人脸核验的配置。长期账号续期、真实运动后台稳定性及首次安装完整流程仍需实际使用验证。

## 数据与权限

定位用于确定起点及地图展示。临时 VPN 用于用户主动发起的账号连接，需要 Android 系统授权。
协议所需的最小认证材料通过 Android Keystore 加密保存在应用私有存储；应用禁用系统备份。
账号和运动请求发送至对应平台，地图、定位及路径规划调用高德 SDK；不应将认证材料、个人诊断或签名私钥提交到仓库。
高德 SDK 的数据处理见[高德隐私政策](https://lbs.amap.com/pages/privacy/)。公开发行前需核对地图服务授权及应用隐私披露要求。

## 源码构建

需要 JDK 17、Android SDK 36、Build Tools 35.0.0；使用仓库中的 Gradle Wrapper。
在不入库的 `local.properties` 中配置 `sdk.dir`、`amap.android.key` 和 `project.repository.url`。
地图 Key 应绑定构建使用的包名及签名。源码构建者应使用自己的 Key 和签名，并遵守高德的服务条款。
当前构建脚本读取 `.local/signing/debug.keystore`，该名称为历史遗留；需自行准备兼容脚本签名配置的本地密钥。发行维护者必须安全保管原有密钥。

```sh
./gradlew :app:testReleaseUnitTest :app:assembleRelease :app:lintRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`。项目仅维护不可调试的 release 变体。

## 许可

项目原创代码采用 [GPL-3.0-only](LICENSE)。第三方组件遵循各自许可，详见 [第三方说明](THIRD_PARTY_NOTICES.md)。GPL 不授予第三方平台或地图服务的使用授权。
