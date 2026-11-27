# 快递运单扫描（ExpressScanner）

一款用于**驿站找快递**的 Android 应用。

驿站货架上快递太多，一件件翻太慢。这个应用让你先把待找的运单号批量粘贴进去，然后用摄像头扫视货架 —— **画面里凡是命中待找运单的条形码，都会被实时框出来并显示运单号**，一眼就能定位到那件快递。

界面采用 **MIUI X 设计风格**：纯白底、科技蓝强调色、大圆角卡片、胶囊按钮。

---

## 功能特性

### 1. 批量文字提取运单号

粘贴一整段文字（订单详情、聊天记录、Excel 复制等），自动识别并去重提取出所有运单号。

- 支持格式：
  - 字母前缀 + 数字：`SF1234567890123`、`JD1234567890`、`YT1234567890123`
  - 纯数字 12~15 位：圆通 / 中通 / 韵达 / 申通等常用格式
  - 国际 EMS：2 字母 + 9 数字 + 2 字母（13 位）
- 根据前缀自动推测快递公司（顺丰、京东、圆通、中通、申通、韵达、百世、邮政 EMS、德邦等）
- 单个运单号可一键复制

### 2. 摄像头实时扫描 + 画面标注

这是核心功能，在货架前使用：

- 持续识别画面中的**所有**条形码，不是扫一个就停
- **命中待找运单**：绿色粗框 + 发光 + 运单号气泡，实时跟随条码位置移动
- **未命中条码**：半透明细框，提示画面里还有哪些码（不抢眼）
- 找到即震动提示（同一运单只震一次），并自动标记为「已找到」
- 顶部实时显示进度：`剩余 3 / 10 · 已找到 7`
- 点击屏幕任意位置触发对焦，解决面单条码小、对不上焦的问题

### 3. 支持全码制

使用 ML Kit `FORMAT_ALL_FORMATS`，覆盖快递面单常见码制：

`CODE_128` / `CODE_39` / `CODE_93` / `Codabar` / `PDF417` / `QR_CODE` / `DATA_MATRIX` / `EAN_13` / `EAN_8` / `UPC_A` / `ITF` 等。

---

## 使用方法

1. **提取**：打开应用，把含有运单号的文字粘贴到输入框，点「提取运单号」，下方列表会列出识别到的运单号
2. **扫描**：点「打开摄像头扫描」，对着货架缓慢扫视
3. **定位**：画面里目标运单的条形码会变成绿色高亮框并显示运单号，直接去拿那件快递

> 扫不到时：靠近一些（10~15 cm）、避开反光和褶皱，并**点击屏幕上条码的位置**触发对焦。

---

## 下载安装

前往 [Releases](https://github.com/la-1314/ExpressScanner/releases) 下载最新版 `ExpressScanner-debug.apk`，直接安装即可（debug 签名）。

- 最低系统版本：Android 7.0（API 24）
- 目标版本：Android 14（API 34）
- 首次打开扫描会请求摄像头权限，需允许

---

## 从源码构建

### 环境要求

- JDK 17
- Android SDK（compileSdk 34）
- Gradle 8.5+（或直接用 Android Studio 打开）

### 步骤

```bash
git clone https://github.com/la-1314/ExpressScanner.git
cd ExpressScanner
gradle assembleDebug
```

产物路径：`app/build/outputs/apk/debug/app-debug.apk`

用 Android Studio 打开项目目录，Gradle 同步完成后可直接运行到手机。

---

## 自动构建与发布（CI/CD）

仓库已配置 GitHub Actions：[.github/workflows/android.yml](.github/workflows/android.yml)

- **触发方式**：推送到 `main` 分支，或手动 `workflow_dispatch`
- **流程**：JDK 17 → Gradle 8.5 → `assembleDebug` → 自动创建 Release（tag 为 `v1.0.<运行序号>`）→ 上传 APK

也就是说，每次 push 到 `main` 都会自动产出一个新的 Release 版本。

---

## 项目结构

```
ExpressScanner/
├── .github/workflows/android.yml     # CI：构建 APK 并发布 Release
├── app/
│   ├── build.gradle                  # 依赖：CameraX / ML Kit / Material
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/express/scanner/
│       │   ├── MainActivity.kt            # 主界面：文字输入 + 运单列表
│       │   ├── CameraActivity.kt          # 扫描界面：CameraX + ML Kit 识别
│       │   ├── OverlayView.kt             # 实时标注层：在预览上画条码框
│       │   ├── adapter/WaybillAdapter.kt  # 运单列表适配器
│       │   ├── model/Waybill.kt           # 运单数据模型
│       │   └── util/
│       │       ├── WaybillExtractor.kt    # 运单号正则提取 + 匹配规则
│       │       └── WaybillStore.kt        # 内存数据存储（页面间共享）
│       └── res/                           # MIUI X 风格主题、配色、布局、图标
├── build.gradle
├── settings.gradle
└── gradle.properties
```

---

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 语言 | Kotlin |
| 摄像头 | AndroidX CameraX（Preview + ImageAnalysis） |
| 条码识别 | Google ML Kit Barcode Scanning 17.2.0 |
| UI | Material Components（MIUI X 风格定制主题） + ViewBinding |
| 列表 | RecyclerView + ListAdapter / DiffUtil |
| 构建 | Gradle 8.5 + AGP 8.2.2 + Kotlin 1.9.22 |

---

## 实现要点

### 运单号提取

[WaybillExtractor.kt](app/src/main/java/com/express/scanner/util/WaybillExtractor.kt) 用三条正则分别匹配 EMS 国际格式、字母前缀格式、纯数字格式，合并去重（`LinkedHashSet` 保序），再按前缀查表推测快递公司。

### 条码匹配

扫码内容与运单号不要求完全相等，支持以下几种实际场景：

- 完全相等（忽略大小写）
- 条码只编码了运单号的数字部分（去掉字母前缀）
- 数字串互相包含（应对条码带校验位、运单号带额外数字前缀）
- 后缀对齐（常见的去前缀编码方式）

### 实时标注

[OverlayView.kt](app/src/main/java/com/express/scanner/OverlayView.kt) 把 ML Kit 返回的 `boundingBox`（图像坐标系）按 `PreviewView` 的 `FIT_CENTER` 规则等比映射到屏幕坐标，然后在 Canvas 上绘制圆角框和气泡。

### 一个必须注意的坑

`ImageAnalysis.Analyzer` 里**每帧处理完必须 `proxy.close()`**，否则 CameraX 的帧缓冲槽位（2~3 个）会耗尽，从此不再送新帧，表现为「扫码完全没反应」。本项目在 `addOnCompleteListener` 中统一关闭，并在关闭前取出图像尺寸。

---

## 常见问题

**Q：对着条码却没反应，也不弹提示？**

A：扫描界面底部有一行常驻诊断信息：

```
已分析 128 帧 · 识别到 2 个条码：SF1234567890… · 命中 1/10
```

- 「已分析 N 帧」的 N 在增长 → 识别链路正常
- N 卡住不动 → 送帧异常（请提 issue）
- 「识别到 M 个条码」在增长但命中 0 → 说明扫到了码，但不在你的待找列表里，或运单号输入有误
- 一直「识别到 0 个条码」→ 靠近一些、避开反光、**点击屏幕对焦**；部分手机广角镜头最近对焦距离较远，可试试稍微拿远一点

**Q：为什么进度不会保存，退出应用就没了？**

A：当前运单列表只存在内存里，应用进程结束即清空。每次使用前重新粘贴一次文字即可。

**Q：APK 提示未签名 / 无法覆盖安装？**

A：Release 里的包是 debug 签名，仅供自用测试。如需正式签名，请在 `app/build.gradle` 配置 `signingConfigs` 后构建 release 包。

---

## 说明与限制

- 运单号识别基于格式正则，对非常规编号可能遗漏或误判；可在 `WaybillExtractor.kt` 中按需调整正则与快递公司前缀表
- 画面标注框受手持抖动会有轻微跳动，属正常现象
- 目前仅支持竖屏
