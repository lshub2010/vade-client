# U1-c 搜索 · RESULT

分支 `tmp/u1-c`。实现：手机全屏覆盖层 + 桌面 `⌘K` 命令面板；**数据源降级为本地缓存索引**（`schema/rpc.json` 无 `session/search`），降级不属于可选优化，是本期唯一可行路径。

## 实现点

| 文件 | 内容 |
|---|---|
| `search/SearchIndex.kt` (160 行) | `SearchScope.Cached("范围：已缓存")` / `CrossSession`；`SearchHit`（标题/摘要命中区间）；`LocalSessionIndex`（标题+摘要扫描、AND 多词、大小写不敏感、标题命中优先）；`SessionSearchSource` 接口 + `TODO(C-05 · session/search)` 占位于 **`SearchIndex.kt:45`** |
| `search/SearchState.kt` (169 行) | 仅客户端派生状态（D98）：query / results / selected / scroll；阈值 `SEARCH_MIN_CHARS=3`、`SEARCH_MAX_CHARS=200`、防抖 150ms；`close()` **不清空**，保证返回后词与滚动位置保留 |
| `search/SearchOverlay.kt` (413 行) | `<600dp` 全屏覆盖层；`≥600dp` 680dp 浮层命令面板（`↑↓` / `Enter` / `Esc`、遮罩点击关闭）；「范围：已缓存」常驻行（含「core 未提供 session/search」说明）；命中片段用 `skin.colors.primary` + SemiBold |
| `shell/AppShell.kt` | 根 `Box` 可聚焦 + `handleShellKeys`：`⌘K`/`Ctrl+K` 开关、`↑↓` 环绕、`Enter` 进入、`Esc` 关闭；列表顶栏「搜索」按钮 |
| `shell/UiState.kt` | 搜索状态与 `openSearch/closeSearch/toggleSearch/updateSearchQuery/openFromSearch` |
| `UiText.kt` | 新增 `AnnotatedString` 重载（**不改 C-09 组件签名**，`Skin.ListItem` 仍只吃 String，故结果行用 Box/Row 自绘） |
| `desktopMain/Main.kt` | `--search=<q>` / `--search` 启动参数，供无头截图造态 |
| `tools/capture-window.ps1` | 新增 `-Search` / `-SearchEmpty` |

**组件纪律**：只用 C-09 已冻结的 11 个组件（Card / TextField / Button / ListItem 等）+ 布局；未新增第 12 个组件。

## 验证（真实输出）

```
:composeApp:desktopTest        BUILD SUCCESSFUL in 2s   → 21 tests（19 新增 + 2 既有守卫），0 failed
:composeApp:assembleDebug      BUILD SUCCESSFUL in 4s   → 38 tasks
:composeApp:desktopMainClasses BUILD SUCCESSFUL         → compileKotlinDesktop 真实重编
```

- APK：`composeApp-debug.apk` **11,743,538 B**，sha256 `b1a613ab1f42908ade577b38be11c0a995565594c5d855cea6358559b6ffbd65`
- 测试覆盖：索引（AND 多词 / 大小写 / 标题优先 / 无结果 / 空白 / 超长 / 区间合并）、状态（开关保留 query+选中+滚动 / 换词重置 / 三字符阈值 / 结果收缩 clamp）、键路由（用真实 `handleShellKeys`：⌘K、Ctrl+K、裸 K 不吞、Esc、↑↓ 环绕、Enter 进入、无命中不消费）

## 截图（均在 `shots/u1c/`，逐张开图核过）

| 文件 | 尺寸 | 字节 | 核对结论 |
|---|---|---|---|
| `search-phone.png` | 466×893 | 16,097 | 全屏覆盖：返回箭头、输入框 `AGP`、3/200、范围药丸、2 条结果；选中行 `AGP` 蓝色加粗、其余黑字；未选中行摘要命中 `AGP` 蓝色；无底栏残留 |
| `search-empty.png` | 466×893 | 14,296 | 空查询态：占位符、0/200、范围药丸、空态说明「至少 3 个字符触发…」 |
| `search-desktop.png` | 1266×793 | 45,506 | 浮层非全屏：背后双栏可见；680dp 卡片；2 条结果 + 高亮；范围药丸；底部「↑↓ 选择 · Enter 进入 · Esc 关闭 · ⌘K / Ctrl+K 再按一次也关」+「2 个结果」 |

## 本期修的两处

1. **选中行高亮不可辨**（上一棒遗留）：选中行标题整行用 accent，导致行内命中片段与正文同色。改为标题恒用 `onSurface`，选中态靠行底 tint + 字重 + 头像底色区分 —— 修后截图可见 `AGP` 蓝色加粗、其余黑字。
2. `capture-window.ps1` 增加 `-Search` / `-SearchEmpty`，使无键鼠注入的会话也能截到真实覆盖层。

## 未做 / 已知

- **`session/search` 未接**：`ui_to_core` 目前只有 6 个方法（`ui/attach`、`ui/detach`、`ui/submit`、`ui/interrupt`、`ui/queue.cancel`、`approval/decide`），没有搜索。接口与 TODO 已留，接上后只需替换 `SessionSearchSource` 实现并把 scope 从 `Cached` 切到 `CrossSession`。
- **多词排序语义**：`titleMatched` 是「任一词命中标题」，因此 `AGP 矩阵` 下「矩阵在标题、AGP 在摘要」的条目会排在「两词都在标题」的前面。测试按实际行为断言并注释，未擅自改语义（留待产品定）。
- **Android / Wasm 运行时与截图仍未验证**（只验构建），属 U1-g 范围。
