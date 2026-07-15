# RemoteKVM — Phase 1
## 被控端：屏幕采集 + H.265 硬编码

---

## 你需要准备

1. **一台电脑**（Windows / Mac / Linux 都行）
2. **Android Studio**（官网 https://developer.android.com/studio 下载最新版，自带 JDK 和 Gradle）
3. **一台安卓手机**（Android 8.0+，用来测试）
4. 手机开启 **开发者选项** 和 **USB 调试**

### 开启开发者选项（每台手机大同小异）

1. 设置 → 关于手机
2. 连续点击「版本号」7 次，提示"已开启开发者模式"
3. 返回设置，找到「开发者选项」
4. 打开「USB 调试」
5. 用数据线连电脑，手机弹窗选"允许调试"

---

## 搭建步骤

### 1. 新建 Android Studio 项目

1. 打开 Android Studio → New Project
2. 选 **Empty Views Activity**（不是 Empty Activity）
3. 填写：
   - Name: `RemoteKVM`
   - Package name: `com.remote.kvm`
   - Language: **Kotlin**
   - Minimum SDK: **API 26 (Android 8.0)**
   - Build configuration language: **Kotlin DSL**
4. 点击 Finish，等待 Gradle 同步完成

### 2. 替换文件

把本目录下的文件**覆盖**到项目对应位置：

```
RemoteKVM/
├── build.gradle.kts          → 替换项目根目录的 build.gradle.kts
├── settings.gradle.kts       → 替换项目根目录的 settings.gradle.kts
├── gradle.properties         → 替换项目根目录的 gradle.properties
├── app/
│   └── build.gradle.kts      → 替换 app/build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/remote/kvm/
│       │   ├── MainActivity.kt
│       │   ├── service/
│       │   │   └── ScreenCaptureService.kt
│       │   └── encoder/
│       │       └── H265Encoder.kt
│       └── res/
│           ├── layout/activity_main.xml
│           └── values/
│               ├── strings.xml
│               └── themes.xml
```

### 3. 同步与运行

1. Android Studio 顶部弹出 "Sync Now" → 点击
2. 等待同步完成（第一次会下载依赖，较慢）
3. 手机连电脑，顶部选择你的手机设备
4. 点击绿色 ▶️ Run 按钮
5. App 安装到手机上

### 4. 测试验证

1. 打开 RemoteKVM App
2. 点击「开始采集」
3. 系统弹出「开始录制或投射？」→ 点击「立即开始」
4. 在手机上随便操作（滑动桌面、打开 App）
5. 操作 10-20 秒后回到 App，点击「停止采集」
6. 打开手机「文件管理」→ Movies → RemoteKVM
7. 里面有一个 capture_xxx.mp4 文件，用播放器打开
8. 能看到录屏内容 = Phase 1 成功 ✓

---

## 看到什么说明正常

- App 界面显示「● 采集中」（绿色）
- 停止后 Movies/RemoteKVM/ 有 MP4 文件
- MP4 文件能正常播放，画面清晰，60fps 流畅

## 常见问题

| 问题 | 解决 |
|------|------|
| 编译报错 "Unresolved reference" | 检查文件是否放对目录，包名是否为 com.remote.kvm |
| 运行后闪退 | 看 Android Studio 的 Logcat，搜 "H265Encoder" 看错误 |
| MP4 文件无法播放 | 设备可能不支持 HEVC 编码，Logcat 会显示降级到 H.264 |
| 提示"无法创建虚拟显示器" | 确认已授权屏幕采集权限 |
| 通知栏显示"屏幕采集中" | 正常，这是前台服务保活机制 |
