# ibudaoa 我步道啊

一个自动生成校园跑记录的 APP

## 安装

-自己编译后安装。
-支持 Android 10 及以上、arm64-v8a 设备。

## 使用与限制

-完成引导设置后就可以开始用了，看不懂就问 AI 。
-需要配置自己的 API Key。


## 数据与权限

定位用于确定起点及地图展示。临时 VPN 用于用户主动发起的账号连接，需要 Android 系统授权。
协议所需的最小认证材料通过 Android Keystore 加密保存在应用私有存储；应用禁用系统备份。
账号和运动请求发送至对应平台，地图、定位及路径规划调用高德 SDK。

高德 SDK 的数据处理见[高德隐私政策](https://lbs.amap.com/pages/privacy/)。使用地图服务时请遵守高德的授权及隐私要求。

## 源码构建

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
