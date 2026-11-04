# 应用内相机扫码

2026-11-04。添加 2FA 账号的「扫描二维码」改为工具箱自己的扫码页面；普通二维码工具也复用同一实现。移除 Google Code Scanner 依赖和扫码模块下载声明。

## 使用

进入「身份验证器 → 添加账号 → 扫描二维码」，首次由系统申请相机权限。允许后显示实时预览和取景框，自动对焦，也可轻点画面对焦。设备支持时，可打开补光灯并使用缩放滑杆（最高 5×，受设备能力限制）。

二维码在本机通过内置 ZXing 识别，不依赖 Google Play 服务，不上传相机画面，不保存视频或照片。普通 TOTP 和 Google 批量迁移二维码继续使用既有解析、校验、去重和预览确认流程。识别到无效的 2FA 二维码时，保留扫码页面并显示原因，点击「继续扫描」重试。

底部可切换到图片导入或手动输入。相机权限被拒绝、永久拒绝、无可用相机或相机被其他应用占用时，也保留这些入口，并提供重试或打开应用设置。正在导入 Google 多码批次时，手动添加前须先完成或取消该批次，避免隐藏未完成的迁移状态。

关闭扫码页或使用系统返回不添加账号。Google 批量迁移仍需收齐同一批次全部二维码并确认导入；相机扫描一张后返回既有预览，可继续扫描下一张。

## 实现

- CameraX 1.6.2：Camera2、PreviewView、ImageAnalysis，与 Android 生命周期绑定；优先后置相机，无后置时尝试前置相机。相机硬件声明为可选，保留无相机设备上的其他工具。
- 采用最新帧策略和单线程识别，最多每 250 ms 尝试一帧，识别成功后锁定回调，避免同一二维码连续添加。无效码经用户重试后再恢复识别。
- 读取 YUV 亮度平面，按 rowStride、pixelStride、ByteBuffer position / limit 及 0° / 90° / 180° / 270° 方向处理；支持明暗反色二维码。
- 识别成功、跳帧、损坏帧和异常路径均在 finally 中关闭 ImageProxy。进入后台暂停识别、关闭补光灯；退出后清除分析器、解绑本页的相机用例并关闭识别线程。异步初始化完成时再次检查页面是否仍存在及相机权限，避免退出后重新打开相机。
- 2FA 扫码页禁止系统截图；扫码结果只通过未导出的应用内部 Activity 返回，识别帧不会写入磁盘。应用合并清单不包含 INTERNET 权限；ACCESS_NETWORK_STATE 来自 CameraX 间接依赖，不授予应用联网能力。

## 参考

参考 [Proton Authenticator 的相机预览](https://github.com/protonpass/android-authenticator/blob/main/shared/ui/src/main/kotlin/proton/android/authenticator/shared/ui/domain/components/camera/CameraQrScan.kt) 与 [扫码添加界面](https://github.com/protonpass/android-authenticator/blob/main/features/home/scan/src/main/kotlin/proton/android/authenticator/features/home/scan/ui/HomeScanScreen.kt) 中的应用内预览、对焦、权限、图片 / 手动输入流程；本项目代码为独立实现，未移植 Proton 源码。

CameraX 使用方式核对 [Android 官方图像分析说明](https://developer.android.com/media/camera/camerax/analyze) 和 [稳定版发布记录](https://developer.android.com/jetpack/androidx/releases/camera)。

## 验证

自动测试覆盖四种相机帧方向、设备行填充、像素步长、非零缓冲区偏移、末行无填充、反色二维码、中文文本、Google 多张迁移二维码及验证码一致性、空帧 / 截断帧 / 参数越界。分析器测试还检查单次回调、重试恢复、后台停用、节流跳帧、损坏帧和回调异常时的帧释放。

本轮共 43 项 JVM 测试通过；Debug APK 与设备测试 APK 编译通过，Lint 无错误。ADB 当前没有连接设备，因此未执行真实相机或权限界面验收。

构建检查：`testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug`。真实设备还需验收首次授权、拒绝 / 永久拒绝后恢复、无 Play 服务和飞行模式扫码、对焦、补光灯、缩放、横竖屏、切换后台 / 返回、无效二维码重试，以及 Google 多张迁移二维码。没有设备时不将帧解码测试等同于相机实机验收。
