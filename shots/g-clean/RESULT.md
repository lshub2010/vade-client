# g-clean · A5 桌面产物自包含验证 RESULT

分支 `tmp/g-clean`（基于 main @ **b6f87ba**）。本批**不改任何源码/构建文件**，只新增
本目录与三个临时脚本 `tools/clean-check-scan.py` / `tools/clean-check-launch.py` /
`tools/clean-check-capture.ps1`（作用与安全性见 §8）。

任务：补上 g-pack 自己承认的「干净机器未验证」欠账——证明桌面 app-image
**不依赖本机 JDK / Gradle / Android SDK / D:/Hermes**。全文只贴原始输出与判定，
不含「已通过」表述。

---

## 1. 构建 app-image（createDistributable）

构建命令（客户端仓根）：

```bash
source /d/Hermes/tools/env/vade-env.sh
cd D:/HermesWorkspace/Vade-client-wt/g-clean
/d/Hermes/tools/gradle-9.6.1/bin/gradle :composeApp:createDistributable
```

原始输出（完整日志见同目录 `createDistributable.log`）：

```
> Task :composeApp:createDistributable
The distribution is written to D:\HermesWorkspace\Vade-client-wt\g-clean\composeApp\build\compose\binaries\main\app

BUILD SUCCESSFUL in 1m 27s
12 actionable tasks: 12 executed
```

| 项 | 值 |
|---|---|
| 产物路径 | `D:\HermesWorkspace\Vade-client-wt\g-clean\composeApp\build\compose\binaries\main\app\vade-ui` |
| `du -sk` | **158,457 KB（≈154.7 MB）** |
| 启动器 | `vade-ui.exe` **540,672 B**（g-pack 批次报 605,184 B，分支基点不同，两者都真实） |
| 文件数 | 241 |

注：g-pack 批次的 MSI 93,312,713 B 本批**未重建未复测**（任务书明确不重新打包，只验自包含）。

## 2. 静态扫依赖（原始命中与逐条判定）

方法：`tools/clean-check-scan.py`——对 app-image 全部 241 个文件做**字节级**搜索，并对全部
95 个 jar 的 **23,721 个 zip 条目解压后**再搜（普通 grep 看不到压缩条目内部）。共搜
**301,324,126 字节**。模式清单：`D:/Hermes`、`D:\Hermes`、`GRADLE_USER_HOME`、`C:/Users`、
`C:\Users`、`D:/Android`、`D:\Android`、`ANDROID_HOME`、`ANDROID_SDK_ROOT`、`/d/Hermes`
（MSYS 形式）、`D:\d`（双前缀构建事故）、`HermesWorkspace`、`hermes`（宽匹配）。

完整原始输出见同目录 `scan-report.txt`。命中汇总：

```
D:/Hermes                 : 2
D:\Hermes                 : 0
GRADLE_USER_HOME          : 0
C:/Users                  : 0
C:\Users                  : 0
D:/Android                : 0
D:\Android                : 0
ANDROID_HOME              : 0
ANDROID_SDK_ROOT          : 0
MSYS form /d/Hermes       : 0
double-prefix D:\d\       : 0
HermesWorkspace           : 2
'hermes' broad ci         : 4
```

命中明细与判定（去重后 4 个独立字符串位置）：

| # | 位置 | 命中内容 | 判定 |
|---|---|---|---|
| 1 | `app\composeApp-desktop-…jar!vade/ui/chat/ChatFixtures.class` | `{"cmd":"gradle --stop","cwd":"D:/HermesWorkspace/Vade-client"}` | **无害**：聊天假数据 fixture 里的示例工具调用文本，仅作 UI 演示渲染，不参与路径解析/进程启动。属「开发机路径出现在演示样例文本」的观感问题，建议后续换中性 cwd |
| 2 | `app\composeApp-desktop-…jar!vade/ui/skin/miuix/MiuixShowcaseKt.class` | `"cwd": "D:/HermesWorkspace/Vade-client-wt/u2-miuix"` | **无害**：Miuix 展示页示例 JSON 字面量，同上 |
| 3 | `runtime\lib\security\public_suffix_list.dat` | `hermes`（公共后缀域名条目 ×2） | **无害**：JDK 自带 PSL 数据（每个 JRE 都有），只是恰好包含该词，与开发机无关 |
| 4 | 以上字符串同时命中的宽匹配行 | — | 与 1/2/3 同源，非独立问题 |

**致命命中数：0**。没有 GRADLE_USER_HOME / 用户目录 / SDK 路径，没有 `D:\d\` 双前缀。

### 2.1 `app/vade-ui.cfg` classpath 检查

全部 100 条 `app.classpath` 均为 **`$APPDIR\<jar 名>` 相对宏路径**，`[JavaOptions]` 四条
java-options 也全部用 `$APPDIR`，无一条绝对路径（全文见 §2 产物内文件，原始片段）：

```
[Application]
app.classpath=$APPDIR\composeApp-desktop-125a91d413a158858e76dbb011937ede.jar
app.mainclass=vade.ui.MainKt
...
[JavaOptions]
java-options=-Djpackage.app-version=0.1.0
java-options=-Dcompose.application.resources.dir=$APPDIR\resources
java-options=-Dcompose.application.configure.swing.globals=true
java-options=-Dskiko.library.path=$APPDIR
```

### 2.2 `runtime/` jlink 完整性检查

实测（`ls` 原始输出）：

- `runtime/lib/modules` 存在，**53,487,531 B**
- `runtime/release` 存在：`JAVA_VERSION="21.0.13"`，`MODULES="java.base java.datatransfer java.xml java.prefs java.desktop java.logging jdk.crypto.ec"`
- `runtime/bin/`：68 个文件——`jli.dll`、`java.dll`、`awt.dll`、`fontmanager.dll`、`freetype.dll`、`net.dll`、`nio.dll`、`verify.dll`、`zip.dll`、`jimage.dll`、`jsound.dll`、`prefs.dll` 等 + `api-ms-win-*` CRT 转发 DLL
- `runtime/bin/server/jvm.dll` 存在，**14,003,320 B**（HotSpot）
- **`runtime/bin/java.exe` 不存在**——这是 jpackage app-image 的正常形态：启动器经
  `jli.dll` 直接加载 `jvm.dll` 起 JVM，不需要 java 启动器二进制；判「运行时完整」
  以 `modules` + `release` + `jli/jvm.dll` 为准，三者齐备
- MSVC 运行库已捆绑（`msvcp140.dll`、`vcruntime140.dll`、`vcruntime140_1.dll`、
  `ucrtbase.dll`）——干净机器无需另装 VC++ Redistributable

## 3. 环境隔离启动实验（两种方法都实测）

### 方法一：python `subprocess(env=白名单)`（`tools/clean-check-launch.py start`）

**完整环境变量白名单（启动器进程收到的全部环境）：**

```json
{
  "SystemRoot": "C:\\Windows",
  "windir": "C:\\Windows",
  "TEMP": "D:\\Hermes\\cache\\scratch",
  "TMP": "D:\\Hermes\\cache\\scratch",
  "VADERUNCHECK": "isolated-launch-probe"
}
```

即：**无 PATH、无 JAVA_HOME、无 GRADLE_USER_HOME、无 ANDROID_HOME、无 USERPROFILE/
APPDATA/LOCALAPPDATA**。启动前基线 `tasklist` vade-ui 行数 = 0。

15 秒存活原始输出：

```
baseline vade-ui.exe rows before launch: 0
launched vade-ui.exe pid=18736 mode=strict (SystemRoot/windir/TEMP/TMP only)
waiting 15s for the app to boot ...
tasklist vade-ui.exe rows after 15s: 2
   "vade-ui.exe","18736","Console","1","8,404 K"
   "vade-ui.exe","41516","Console","1","273,940 K"
survival=True
app output log so far: b''
```

（两个进程：jpackage 启动器 + 其 JVM 子进程；子进程 273.9 MB 工作集说明 JVM 与
Compose/Skia 已完成启动。stdout 为空 = GUI 应用无控制台输出。）

### 方法二：bash `env -i`（同一最小集，双证）

```bash
cd <app-image 目录>
env -i SystemRoot='C:\Windows' windir='C:\Windows' \
  TEMP='D:/Hermes/cache/scratch' TMP='D:/Hermes/cache/scratch' \
  ./vade-ui.exe &
# 15 秒后：
tasklist | grep -i vade-ui
```

原始输出：

```
vade-ui.exe                  30032 Console                    1      8,416 K
vade-ui.exe                   8008 Console                    1      274,848 K
```

两法结论一致：**PATH 与 JAVA_HOME 全空的情况下，打包产物照常起 JVM 并渲染完整 UI**。

### 3.1 结束进程（只打 vade-ui）

- 方法一的实例按**精确 PID** 结束（记录文件里只存本实验启动的 pid）：
  `taskkill /F /PID 18736 /PID 41516` → `taskkill exit: 0`，随后
  `vade-ui.exe rows after kill: 0`
- 方法二的实例按任务书授权的**映像名**结束（启动前已验证基线 0 行，故 /IM 只可能
  命中本实验自己的两个进程）：`taskkill /IM vade-ui.exe /F` →

```
成功: 已终止进程 "vade-ui.exe"，其 PID 为 30032。
成功: 已终止进程 "vade-ui.exe"，其 PID 为 8008。
=== after /IM kill ===
no vade-ui remaining
```

（控制台为 GBK，上方转码显示；exit code 0。全程未触碰任何其它 java/gradle 进程。）

## 4. 真窗口截图

`shots/g-clean/isolated-launch-window.png`（452x886，31,492 bytes，
md5 `E247B56E3E11F5CD1514959892B0C4DD`），由 pid 级 PrintWindow 脚本拍摄
（进程 pid 41516 的 SunAwtFrame，`PrintWindow=True`，中心像素 `A=255,R=247,G=247,B=248`
非透明）。**拍摄时机器上确实有多个 java 进程在跑**（本机多仓并行开发常态），因此
没有用旧的 `tools/capture-window.ps1` 全机方案，而是用只按本实验 pid 枚举窗口、
**不 kill 任何进程**的 `tools/clean-check-capture.ps1`（见 §8），对其它构建零影响。

截图内容（已人工打开核验）：标题栏「Vade · UI 骨架」，会话列表分组（置顶/今天/
昨天/7 天内）+ 底部四 tab 导航（会话/活动/能力/我），完整渲染非空白帧。
注：列表显示「假数据: 未连接 core」横幅——隔离环境无 core 服务可连，属预期行为，
恰好也证明无网络/服务依赖时 UI 正常起壳。

## 5. 体积构成（帮 alpha 判断能不能发）

顶层：`app/` 81,588 KB + `runtime/` 76,341 KB + `vade-ui.exe` 528 KB = **158,457 KB（du -sk）**。

最大 5 个单体（原始字节）：

| # | 文件 | 字节 |
|---|---|---|
| 1 | `runtime/lib/modules`（jlink 模块镜像） | 53,487,531 |
| 2 | `app/icons-tabler-outline-cmp-jvm-2.2.1-….jar`（Tabler 图标包） | 17,558,679 |
| 3 | `app/skiko-windows-x64.dll`（Compose Skia 原生库） | 14,119,424 |
| 4 | `runtime/bin/server/jvm.dll`（HotSpot） | 14,003,320 |
| 5 | `app/icudtl.dat`（ICU 数据，Skiko 附带） | 10,468,208 |

构成观察：JDK 运行时（#1+#4+runtime 其余）约 76 MB，占 48%；图标单包 17.6 MB 是
app 目录里最大依赖（后续可做按需子集化）；Skia 相关（#3+#5）约 24.6 MB。

## 6. 本次仍未证明的部分（诚实清单，继承 g-pack §5）

1. **真·干净机器仍未验证**：本机即开发机。本实验证明的是「环境变量层零依赖」——
   把 PATH/JAVA_HOME/SDK 全部拿走也能起；但注册表、Windows Defender/杀软、
   SmartScreen（产物未签名，`app/.jpackage.xml` 实测 `<signed>false</signed>`）、
  以及从未装过 JDK 的裸 Windows 仍需一次真机双击验证。
2. **MSI 安装路径未复测**：MSI 安装到 Program Files 后的快捷方式/卸载器行为本批没碰。
3. **无代码签名**：分发前需证书与签名流水线（g-pack 已列）。
4. **产物不入库/无发布通道**：同 g-pack §5.1。
5. **UI 与 core 的真实联调**不在本批范围（隔离启动里 core 缺席是预期）。

## 7. 给 alpha 的结论参考

- 静态层（字节 + jar 解压双搜）：无致命命中，0 条构建机路径泄漏进运行链路；
  仅 2 处演示样例文本含开发机 cwd（观感问题，发布前建议清理）。
- 运行层（最严环境白名单，两法交叉）：无 PATH/JAVA_HOME/SDK 下 15 秒存活 + 真窗口
  完整渲染，两种结束方式收尾干净。
- 剩余风险集中在「未签名 + 未经真实裸机验证」，不是「依赖开发机」。

## 8. 临时脚本（tools/clean-check-*，均只读/只杀自己）

| 脚本 | 作用 | 安全性 |
|---|---|---|
| `tools/clean-check-scan.py` | 字节级 + jar 解压扫描（§2） | 纯只读 |
| `tools/clean-check-launch.py start/stop` | 白名单启动/按记录 PID 结束 | start 前查基线，非 0 拒绝启动；stop 只 taskkill 记录文件里的 PID，绝不按名杀 |
| `tools/clean-check-capture.ps1` | 按 PID/按 vade-ui 映像枚举窗口并 PrintWindow 截图 | **不 kill 任何进程**；窗口枚举仅限 vade-ui.exe 进程 |

原始证据文件：`scan-report.txt`（§2 全文）、`createDistributable.log`（§1 全文）、
`isolated-launch-window.png`（§4）。
