# g-shadow-android：让 Android 真的画出下行阴影（无轮廓、上方无影、无阶梯）

**设备**：emulator-5554（1080x2400，420dpi ≈ 2.625 px/dp，亮色主题）。绝不碰真机
`192.168.1.180:5555`（所有 adb/gradle 命令都显式 `-s emulator-5554` / `ANDROID_SERIAL`）。

**父代补充路线（2026-11-15，优先级高于任务书第 2–4 级）**：第 1 级最小证明之后，
**默认实现 = 渐变带（gradient shelf）**：`drawBehind` 在卡片下沿画垂直 alpha 渐变
（α₀ → 0，近层 16dp 短促、远层绵长，宽度 = 卡宽，两端随圆角收边），
不需要 BlurMaskFilter、不需要裁剪、不需要 offscreen。模糊路线降级为可选优化；
`clipPath` 一律不再试。

## 级数日志（先写文字再改代码）

### 第 0 级 · pristine 基线（完成 ✅ 2026-11-15）

origin/main（4c1579a）原样 `installDebug` 到 emulator-5554（`ANDROID_SERIAL` 锁定，未触真机）。
坐标全部来自 UI 树 / 像素扫描定位（`driver.py` + `measure.py`，纯 stdlib PNG 解码）。

**before-sessions.png（顶栏玻璃 = GlassSurface + `Modifier.shadow(16.dp)`）**：
胶囊顶缘 y≈84、底缘 y≈230、左缘 x=32（像素扫描定位）。
- **D10**：顶缘之上（y 63–83）暗带，最深 **10.7/255**（238.3 vs 画布 249），上沿 40px 均值 **5.5/255**；
- **D6**：圆角外（y=90/y=100 横扫）外侧渐变带深 ~12/255，轮廓边界处 **1px 内 237.3→249.3 的硬台阶**（无过渡）；
- 下沿（y 231–260）：阴影带峰值 **14.7/255**（超 8/255 预算），~30px 内衰减到画布。

**before-composer.png（composer = MaterialInputBar + `layeredShadow(outsideOnly=true)`）**：
面板 [2116, 2294]（attach 中心 2205 ± 34dp×2.625）。「新建会话」后 composer 下方为纯画布
（x=700 / x=30，y 2296–2398 恒 247.3）：
- **D1**：面板下沿之下 **峰值 0.0/255 —— 零像素**，本构建复现上一棒结论。✅

（插曲：会话中途 core（宿主 8443）进程死掉导致「会话列表加载失败」，已用仓库 venv
后台重启 `python -m vade` 恢复，非本任务改动。）

### 第 1 级 · 最小编译证（完成 ✅）

探针屏 ShadowProbeActivity（androidMain，无 intent-filter，`am start -n vade.ui/.ShadowProbeActivity`；
exported=true —— uid 2000 在本 API 级别拉不起非导出 Activity）。
`probe-solid @ (500,150) rgb = (51,102,204)` = #3366CC 精确命中 ⇒ **drawBehind 落像素，
环境/截图链路无问题**。✅

（插曲：中途弹系统「Updating...」全屏遮罩把 screencap 染灰（canvas 177），等待自行消失后
重测；另外 gradle 装出的包一度缺探针 Activity（Error type 3），改用
`adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk` 直装解决。）

### 第 1.5 级 · 渐变带定标（完成 ✅ —— 默认实现 = gradient shelf，不再走模糊/裁剪路线）

**量化发现（本轮最关键的机理）**：每个 band 的 α 按 8-bit 独立取整，组合峰落在粗阶梯上。
float token 三层等比缩放下，可达组合峰 = {6, 9, 10}/255 —— **8 不可达**（逐格子验证：
(4,3,1)/(4,2,2)/(3,3,2)/(5,2,1) 均被 α 比例 1:0.714:0.429 卡死）。而 8 恰好是两道验收
的交集（Android 峰值 ≤8；桌面 guard `below ≥ 8` 且其窗口从贴边 ~0.4dp 起，与 Android
窗口重叠）。⇒ 引擎在亮色主题下**从最深 band 逐量子削峰到 ≤8.49/255 预算**：
float → **(3,3,2)/255**，三层全保留。暗色不削（预算按可见 Δ = ink×14/255 计，×1.8 后
仅 1.0 灰阶）。实测注记：HWUI 对渐变做 ±1 级有序抖动（x 不变、y 周期的纹理），
原始行序非严格单调，平滑后严格单调 —— 抖动是抗梯带纹理，非阶梯。

**probe-v2.png 实测表**（x=540，bottom=UI 树节点下缘，窗口 = 下沿 +1..+40px）：

| 档位 | band 量子 | 峰值 | 台阶数 | 最大步进 | 上方均值 | 判定 |
|---|---|---|---|---|---|---|
| shelf-eng（削峰） | (3,3,2) | **8.0** | 7 | 2 | 0.00 | **✅ 选定** |
| shelf-noTrim | (5,3,2) | 10.0 ✗ | 9 | 2 | 0.00 | 超预算（反例，钉住 trim 存在的理由） |
| shelf-q8（单带参照） | (8) | 8.0 | 4 | 1 | 0.00 | 达标但层数不足 §8.1 |
| shelf-q7（单带参照） | (7) | 7.0 | 3 | 1 | 0.00 | 桌面 guard 窗口 ≤7.5 有失守风险 |

**第 2–4 级（BlurMaskFilter/clipRect/DstIn）按父代补充路线跳过**：渐变带一次性满足全部
判据（上方 0 像素为构造性质），模糊路线降级为可选优化，`clipPath` 永不再试。

### 提交 A（完成 ✅ 8e08589）

`GlassSurface.kt` 的 `Modifier.shadow(16.dp)` → `.downwardShelf(VadeShadows.float, shape,
color = skin.colors.shadowFloat, dark = skin.colors.isDark)`。守卫测试
`DownwardShelfMathTest`（desktopTest，纯数学）：峰 ≥1/255、上方 ≤1.5/255（=0）、层数 >0、
量化峰 ≤ 预算、量化衰减单调无阶梯（≤2/255）、≥3 个可见灰阶、暗色可见 Δ ≤ 预算、
band 圆角 ≤ 高度一半（9 条全绿）。

**Android 前后数字**（afterA-sessions.png，顶栏胶囊几何由像素扫描定位：顶缘 84、
底缘 230、左缘 32）：

| 判据 | 修前（Modifier.shadow） | 修后（downwardShelf） |
|---|---|---|
| D10 上方暗带 | 最深 **10.7/255**，40px 均值 5.5 | 阴影分量 **0**（残余均值 0.33 ≤1，为玻璃模糊层边缘渗出，峰值在带中部非贴边，非阴影轮廓） |
| D6 圆角外硬线 | 外侧渐变 ~12/255 + 1px 内 237→249 硬台阶 | **全平**（轮廓外 248.3–249.3，无第二道轮廓） |
| 下沿像素 | 14.7/255 起步（超 8 预算） | **8.0/255**、7 台阶、步进 ≤2、单调衰减 ~40dp |
| 上方 40px 均值 | 5.5 | **0.33** ✓ |

### 提交 B（完成 ✅ 64eea1b）

**新机理（本轮最重要的发现）**：composer 的 0 像素有**两层**根因——
1. `layeredShadow(outsideOnly=true)` 的 clipPath 高斯（g-shadow-real 已证伪路线）；
2. **页面给 `skin.InputBar` 套的 `animateContentSize` 包装自带裁剪层（rest 态也在）**，
   把下沿之外的整条阴影裁成 0（probe-v3 实测：同链无包装 = 239 有影，套包装 = 247 全平）。
   上一棒「去掉 outsideOnly 也 0 像素」的实验同样被这层裁剪污染。
   ⇒ §7 的 200ms 高度跟随移进皮肤内部（动画节点在胶囊里面，胶囊与阴影在裁剪层之外）：
   Material 包内容列、Miuix 包文本域 Box；调用点与 guard 测试字面量不动。

**Android 前后数字**（afterB-composer.png，「新建会话」纯画布，面板 [2116,2294] 来自
UI 树 attach 中心 2205 ± 34dp×2.625；x=700，画布 247.3）：

| 判据 | 修前（clipPath 高斯） | 修后（渐变带 shelf） |
|---|---|---|
| 下沿峰值 | **0.0/255（零像素）** | **8.0/255** |
| 台阶数 | 0 | **9**（239→247，含画布） |
| 相邻步进 | — | ≤2（平滑后单调；原始行序带 HWUI 渐变抖动 ±1） |
| 面板内部 | —（84% 玻璃） | 单一 254 填充（spread 0，阴影墨不入玻璃） |
| 圆角外第二道轮廓 | — | 无（左下圆角外纯画布 247.3） |
| 上方 | — | 构造性 0（shelf 只画在下沿之下；探针实测 0.00，顶栏实测 0.33） |

桌面像素测试 ComposerShadowShapeTest 全绿（`below ≥ 8` 刀锋断言落在 8.0——
这正是第 1.5 级把量化峰钉在 8.0 的原因），全量 desktopTest 绿。

### 脚手架与收尾

- ShadowProbeActivity + manifest 探针入口随 docs 提交（仅 adb 显式拉起，无产品路径）。
- 测量工具：`shots/g-shadow-android/{driver,measure}.py`（stdlib PNG 解码，无 PIL 依赖；
  坐标全部来自 UI 树 / 像素扫描定位，遵守 g-shadow-real 的取样行纪律）。
- 三目标门禁（android/desktop/wasmJs 编译）在最后一笔之后跑一次：
  `compileDebugKotlinAndroid + compileKotlinDesktop + compileKotlinWasmJs + desktopTest`
  → **BUILD SUCCESSFUL**（1m18s，21 tasks）。✅

## 验收判据（父代任务书 + 补充，逐条给前后数字）

| 判据 | 目标 |
|---|---|
| 相邻像素差 | ≤ 2/255（无阶梯） |
| 峰值/外缘 | ≤ 3%（≤ 8/255，仓库现行预算口径） |
| 元素上方 | 上沿之上 40px 均值 ≈ 画布值，≤ 1/255 |
| 下方衰减 | 单调递减，≥ 3 个亮度台阶 |
| D6 | 圆角外第二道硬线消失 |
| D10 | 上方 10–11/255 暗带消失（≤ 1/255） |

（实测注记随级数日志追加。）
