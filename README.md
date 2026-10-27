# 安卓工具箱

基于 Jetpack Compose 的 Android 工具箱初版，界面参照 [`docs/toolbox-prototype.png`](docs/toolbox-prototype.png)。

## 已实现

- 首页、搜索、预置收藏列表和最近访问入口。
- 二维码：文本、网址和 Wi-Fi 二维码生成；保存 PNG、分享图片；调用 Google Code Scanner 扫描并复制解析结果。
- 加解密：AES-256-GCM（PBKDF2-HMAC-SHA256 派生口令密钥）、RSA-OAEP、Base64、SHA-256、SHA-512。AES 每次生成独立盐和 IV；RSA 可生成临时密钥对。
- 2FA：手动输入 Base32 密钥或扫描 `otpauth://totp`；支持 SHA1/SHA256/SHA512、6–8 位动态码及 15–120 秒周期。账号数据使用 Android Keystore 的 AES-GCM 密钥加密保存在设备本地。

## 构建

1. 安装 Android Studio、JDK 17 和 Android SDK Platform 36。
2. 使用 Android Studio 打开本目录，等待 Gradle 同步。
3. 在项目目录执行 `./gradlew assembleDebug`（Windows 使用 `.\gradlew.bat assembleDebug`）。
4. 运行单元测试：`./gradlew testDebugUnitTest`。

扫码依赖设备上的 Google Play 服务。首次使用时，Google Code Scanner 可能需要下载扫码模块；应用不申请相机权限。加解密与动态验证码均在设备本地计算。

## 当前边界

- 设计图中的“安全备份与迁移”尚未提供导出操作；界面会提示用户保存服务方的恢复码。
- 在线加解密服务需要明确服务端 API、传输范围和隐私策略后再接入；本版本提供本地算法处理。
- 收藏目前是预置列表；历史仅保存在当前应用进程中。
- 自动备份已关闭，避免恢复 2FA 密文时缺失原设备的 Android Keystore 密钥。

## 本次环境验证

本机检测到 JDK 17，但未检测到 Android SDK。Gradle Wrapper JAR 已与 Gradle 官方 8.13 SHA-256 对照校验。尝试运行 `gradlew --version` 时，Gradle 发行包下载连接超时，因此尚未完成实际编译或设备运行验证。



## ❤️ 赞赏支持
[![赞赏作者](https://pic1.afdiancdn.com/static/img/welcome/button-sponsorme.png)](https://afdian.com/a/abc88)
