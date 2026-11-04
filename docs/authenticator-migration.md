# 身份验证器搜索与 Google 二维码迁移

2026-11-04。身份验证器新增平台 / 账号名称搜索，以及 Google Authenticator 批量迁移二维码导入、导出。

## 使用

- 在身份验证器输入平台名或账号名即可筛选，不区分英文大小写，支持中文关键词；清除搜索可恢复全部账号。
- 从 Google Authenticator 的「转移账号 → 导出账号」生成二维码，在工具箱中通过相机逐张扫描，或通过「从二维码图片导入」多选图片。也可在添加账号的密钥输入框粘贴 `otpauth-migration://` 链接。
- 相机扫码为应用内 CameraX + ZXing 实现，无需 Google Play 服务；支持对焦、补光灯、缩放、图片入口和手动输入。无效二维码会提示并允许继续扫描，见 [相机扫码说明](camera-scanner.md)。
- 每张迁移码可包含多个账号；多码批次显示已读取 / 总张数和账号预览，只有收齐后才能确认导入。支持乱序扫描、同页重扫去重，拒绝混入其他批次或同序号不同内容的二维码。多选图片先全部解析成功再更新待导入状态。
- 确认后将账号合并写入原 Keystore 加密的本地存储，重复账号跳过，不覆盖已有账号。取消不写入；待确认的数据仅保留在内存中，重启或重建 Activity 后需重新读取。
- 点击「导出 Google 批量迁移二维码」，搜索并勾选账号后生成二维码。在目标 Google Authenticator 中选择「转移账号 → 导入账号」，依次扫描全部页面。可以逐张保存或通过系统面板分享；图片文件名独立，避免后续页面覆盖已分享的页面。
- 导出二维码包含可生成验证码的原始账号密钥，展示页禁止系统截图；用户仍可通过明确的保存 / 分享按钮导出。原有口令加密备份继续可用。

## 协议与范围

独立实现 `otpauth-migration://offline?data=`：百分号编码的标准 Base64 数据，内部为 Google MigrationPayload v1 protobuf。运行时不增加 protobuf 依赖；测试使用 Google protobuf-java 生成和解读独立数据进行交叉验证。

支持 TOTP、SHA1 / SHA256 / SHA512、6 / 8 位验证码、30 秒周期，保留平台、账号名和原始密钥。协议支持负数 `batch_id`；`batch_size`、`batch_index` 与 `batch_id` 一起用于批次检查。未知 protobuf 字段按线格式跳过；截断、溢出、错误编码、未来版本或非法批次参数均拒绝。

Google 批量迁移格式没有周期字段，不能无损表达自定义周期。现有 7 位或非 30 秒 TOTP 账号在导出选择中明确标出原因并禁用，可用口令加密备份迁移。当前账号模型不提供 HOTP 计数器或 MD5；含此类账号的批次整体拒绝并说明原因，不静默忽略账号或更改参数。

单次最多 1000 个账号；导出每张最多 10 个，另按编码后长度自动提前分组，保留二维码容量余量。遇到单个账号信息超过本次二维码容量限制，提示使用加密备份，不截断密钥。二维码保留四个模块的空白静区。导入的普通 `otpauth://totp` 二维码继续可用，图片选择支持多张普通二维码的原子合并。

## 开源参考

- [Proton Authenticator](https://github.com/protonpass/android-authenticator)：参考主页搜索、无匹配状态、账号列表与导入 / 导出流程；[主页搜索入口](https://github.com/protonpass/android-authenticator/blob/main/features/home/master/src/main/kotlin/proton/android/authenticator/features/home/master/ui/HomeScreen.kt)。产品代码采用独立实现，未移植 Proton 源码。
- [Google 提供的 Android 开源版本](https://github.com/google/google-authenticator-android)：该仓库已归档，README 明确其与商店版本并不同步，因此不能据此声称商店版互扫已经验证。
- [Aegis 的 Google migration 协议定义](https://github.com/beemdevelopment/Aegis/blob/master/app/src/main/proto/google_auth.proto)：交叉核对 protobuf 字段编号、枚举与批次元数据。

## 验证

JVM 测试覆盖独立 protobuf 导入 / 导出、中文名称及特殊字符、负批次 ID、二维码图像编码 / ZXing 解码 / 账号还原 / 验证码一致性、多页乱序与重复页、混合批次、重复账号合并、不兼容参数、未知字段、损坏输入以及平台 / 账号搜索。

本轮共 34 项 JVM 测试全部通过；23 个测试账号自动导出为 3 张二维码，逐张从像素解码并按乱序还原后，账号参数及验证码保持一致。Debug APK 与设备测试 APK 编译通过，Lint 为 0 错误、9 警告、1 提示。本机当前未连接设备，未执行真实 Google Authenticator 双向互扫。

构建检查使用 `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug`。设备可用后，需在真实 Google Authenticator 和工具箱之间双向互扫，并验收 1 张、多张、重复扫码、缺页、跨批次与取消导入；核对导入后同一时间的验证码，并在重启后检查账号持久化。
