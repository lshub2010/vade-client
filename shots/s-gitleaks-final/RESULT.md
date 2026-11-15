# s-gitleaks-final — gitleaks 定稿（ALPHA A6-3）

- 日期：2026-11-15；工具：gitleaks 8.30.1（`D:/Hermes/tools/gitleaks/gitleaks.exe`）
- 基点：主仓 worktree `D:/HermesWorkspace/Vade/Vade-wt/s-gitleaks-final` @ `7941b93`（origin/dev_gingshan）；客户端仓 @ `5e5067c`
- 交付：两仓**逐字节同份**的 `.gitleaks.toml`（定稿版，取代 09a2ac5 初稿）与 `tools/gitleaks.sh`；证据在本目录（客户端仓副本未提交，按任务书留给父代合并）
- 边界遵守：主仓只改 `.gitleaks.toml`（覆写 tracked 初稿）、`tools/gitleaks.sh`（新建）、`shots/s-gitleaks-final/**`（新建）；未动业务代码/schema/文档门禁；未 push、未合并；客户端仓未 commit

## 结论

**未通过（gitleaks 剩余命中：主仓 worktree 4 / 主树真实工作目录 5 / 客户端仓 7；私钥 grep 主仓 6+1159、客户端 0+0——全部为测试夹具、文档示例与旧证据文档自引，无真实凭据；但按定稿策略「禁止抹字符串」它们必须照报，故不计通过）。**

按 ALPHA A6-3，该项维持「未定稿 = 不得计为通过」。转通过需父代在 §5 三个选项中拍板。

## 1. 定稿内容

| 文件 | 主仓 | 客户端仓 | 内容 |
|---|---|---|---|
| `.gitleaks.toml` | 覆写（tracked 09a2ac5 初稿 → 定稿版） | 覆写（同份，未提交） | `[extend] useDefault = true` + **仅 4 条路径级排除**；**零** regex/stopword/行特征 allowlist |
| `tools/gitleaks.sh` | 新建 | 新建（未提交） | 一条命令：workdir + history + history-all + 私钥 grep；证据落 `shots/s-gitleaks-final/`；结尾打印可粘贴的 `AUTO-CONCLUSION.md` |

四条路径级排除——全部是「从不进入版本库」的树（.gitignore 覆盖），排除它们不可能掩盖真实提交过的凭据：

| 模式 | 内容 | 为何可排 |
|---|---|---|
| `.venv/**` | Python 虚拟环境：第三方 wheel / vendor 类型存根 | `.gitignore:3`；实测 .venv 直扫 2→0（§3.1） |
| `**/build/**` | 构建产物（Gradle/Cargo/webpack 输出） | 由源码与第三方拷贝生成，不入库 |
| `**/.gradle/**` | Gradle 本地缓存与状态 | 工具状态，不入库 |
| `**/node_modules/**` | npm 第三方依赖 | 不入库 |

**移除的旧机制**：09a2ac5 初稿用 7 条「行级 regex allowlist」把已知误报压成 0（对照组 `maintree-oldcfg.json` = `[]`）。那正是本次定稿禁止的「抹具体字符串」做法——移除后，夹具命中照报（§2）。

**工具内置降噪说明**：gitleaks 默认规则自带全局 allowlist（模板占位符/二进制后缀/node_modules 等路径）与规则级豁免（如 aws-access-token 的 `.+EXAMPLE$`，见 §4）——属官方默认行为，非本文件所加，不在「禁止抹字符串」约束内。

## 2. 命中数（全口径，报告均带 `--redact`，JSON 可直接入库）

主仓（Vade）：

| 口径 | 配置 | 命中 | 明细 |
|---|---|---|---|
| worktree `dir .` | 定稿 | **4** | generic-api-key ×2（`core/tests/admin/test_passwords.py:96` 假口令夹具、`core/tests/input/test_audit.py:14` SECRET 夹具）+ curl-auth-header ×1（`core/tests/loop/test_args_redaction.py:118` Bearer 夹具）+ aws-access-token ×1（`:119` AWS 夹具） |
| worktree `git .` | 定稿 | **5** | 同上夹具的历史行（行号随提交漂移；`test_passwords.py:94` 两个提交各一条），见 `history.json` |
| worktree `git . --log-opts=--all` | 定稿 | **5** | 同上（tmp/* refs 无增量） |
| 主树 `dir .`（真实工作目录，含 .venv/__pycache__/build） | 定稿 | **5** | 上 4 条 + `core/tests/loop/__pycache__/test_args_redaction.cpython-312-pytest-9.2.0.pyc:122` aws-access-token（夹具编译副本） |
| 主树 `dir .` | 旧配置（09a2ac5） | 0 | 初稿 allowlist 全压——对照组，证明 0→5 的差异来自策略变化而非工具 |
| 主树 `.venv` 直扫 | 无配置（目标目录无 .gitleaks.toml → 真默认规则） | **2** | generic-api-key @ `.venv/Lib/site-packages/cryptography/hazmat/bindings/_rust/openssl/hpke.pyi:77/:101` —— **复现任务书「已知 2 命中」**（vendor 类型存根 `private_key: <Type>PrivateKey` 形态的 PEP 484 注解） |
| 主树 `.venv` 直扫 | 定稿 | **0** | 同内容被 `.venv/**` 路径排除 |

私钥 grep（模式逐字按任务书：`BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY`）：

- 主仓 worktree：工作区 **6** 条 / 全历史 **1159** 条（原始输出 `grep-privkey-workdir.raw.gz` / `grep-privkey-history.raw.gz`，字节级保真；工作区 6 条 = 4 条测试夹具 PEM 头片段 + 2 条文档中「BEGIN PRIVATE KEY」字样提及；全历史 1159 条 = 同一批夹具/文档行在各 commit 的重复出现，`rev-list --all` 全 refs 口径）。
- 客户端仓：工作区 **0** / 全历史 **0**。

客户端仓（vade-client）：

| 口径 | 命中 | 明细 |
|---|---|---|
| `dir .` | **7** | generic-api-key ×7：`composeApp/src/desktopTest/kotlin/vade/ui/data/BlobUploadCapabilityTest.kt:132/215/276`（`put("token", <长串>)` 上传测试夹具 ×3）+ `docs/QUICKSTART.md:204`（Sec-WebSocket-Key 握手示例）+ `shots/g-gitleaks/RESULT.md:52/81/151`（上一批 gitleaks 证据文档自引命中形态） |
| `git .` | **7** | 同上（全部 tracked） |
| `git . --log-opts=--all` | **7** | 同上 |

注：客户端 7 条里 3 条是旧批次自己的 RESULT.md 引文——新策略连「证据文档引用旧命中」也照报，这正是「不抹字符串」的直接体现。

## 3. 金丝雀证明（路径级排除没有削弱扫描）

做法：在 scratch 建金丝雀 git 仓，5 份**同款饵**文件——`canary-root.txt`（未排除路径）+ `.venv/`、`build/`、`.gradle/`、`node_modules/` 各 1 份；用定稿 `.gitleaks.toml` 跑 `dir` 与 `git --log-opts=--all` 两道扫描，跑完即删。

饵料（值已掩码，形态如实记录）：`api_key = "<占位注入值>"`、合成 AKIA（base32 域）access key、`ghp_` + 36 位 PAT、夹具同款 AWS 行、完整 RSA PEM 块。

结果（`canary/canary-transcript.txt` + `canary-report-{git,dir}.masked.json`）：

- **断言 1 PASS**：`canary-root.txt` 命中 aws-access-token ×2 + github-pat ×1 + private-key ×1（dir 与 git 两道一致，各 4 条）——未排除路径的扫描强度与默认规则一致，扫描器与配置都活着。
- **断言 2 PASS**：四个排除树里的同款饵 **0 命中**——与根路径唯一差异是路径，证明排除按路径生效、且未扩大化（没有把根路径也排掉）。

### 3.1 hpke.pyi 专证（任务书「已知 2 命中」的复现与排除）

- 无配置直扫 `.../_rust/`：generic-api-key ×2 @ `hpke.pyi:77/:101` —— 已知事实逐字复现；
- 同内容以定稿配置直扫 `.venv` 根：**0 命中** —— `.venv/**` 排除真实承载（`probe-hpke-nocfg.json` / `probe-venv-newcfg.json`）。

## 4. 机制注记（gitleaks 8.30.1 默认规则源，v8.30.1 `config/gitleaks.toml`）

- **`private-key` 正则尾部只要求 `KEY(?: BLOCK)?-----`**：`-----BEGIN…` + ≥64 字符（`[\s\S]` 跨行）+ `KEY-----` 即命中——**相邻两行 PEM 头会互配**。因此私钥 grep 的明文归档会让下一轮扫描命中证据文件自己（实测发生过：workdir 4→6）。修法：grep 原始输出一律 **gzip 归档**（字节级保真 + 扫描中性），脚本头注明原因。
- **`aws-access-token`**：正则后缀字符集是 `[A-Z2-7]{16}`（base32 域，非 [A-Z0-9]）、entropy=3，且规则自带 `.+EXAMPLE$` 豁免——教科书示例 key 官方默认不报（金丝雀占位值行不报的原因；该占位值经哈希比对确为 `AKIA…EXAMPLE`）。
- **`generic-api-key`**：entropy=3.5，固定形状的饵不依赖它做断言。
- **`gitleaks dir <目标>` 会自动发现目标目录里的 `.gitleaks.toml`**（cwd 无配置时）——「无配置整树」扫描实际加载的是目标仓自己的配置。`tools/gitleaks.sh` 一律显式 `-c`，不受影响。
- **`dir` 扫描不跳过 gitignored 文件**（主树 `__pycache__/*.pyc` 被扫出）——路径级排除是真实承载的过滤层，不是摆设。

## 5. 转通过路径（父代拍板，三选一）

1. **修夹具**：把 key 形态的测试值改为非 key 形态（主仓 `core/tests` 4 处 + 客户端 `BlobUploadCapabilityTest.kt` 3 处 + `shots/g-gitleaks/RESULT.md` 3 行自引 + `docs/QUICKSTART.md` 1 处）——超出本棒文件边界，需另派棒。
2. **扩大路径级排除授权**：把 `core/tests/**`、`shots/**`、`docs/QUICKSTART.md` 纳入排除——需父代明确授权（超出任务书给定四条）。
3. **恢复旧配置**：与本次「禁止抹字符串」的定稿方向相悖，不推荐。

## 6. 复跑方式

```bash
cd <任一仓根> && bash tools/gitleaks.sh      # 全套：workdir + history + history-all + 私钥 grep
# 逐项等价：
gitleaks dir . -c .gitleaks.toml --no-banner --redact --report-format json --report-path out.json --exit-code 0
gitleaks git . --log-opts=--all -c .gitleaks.toml --no-banner --redact --report-format json --report-path out.json --exit-code 0
git grep -nE 'BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY'
```

## 7. 证据清单（本目录）

- `AUTO-CONCLUSION.md` — 可粘贴结论（脚本自动生成）
- `workdir|history|history-all.{json,log,count}` — 主仓 worktree 三道扫描（--redact）
- `grep-privkey-{workdir,history}.raw.gz` + `.count` — 私钥 grep 原始输出（gzip，字节级保真）
- `canary/canary-transcript.txt`、`canary/canary-report-{git,dir}.masked.json` — 金丝雀自证（值已掩码）
- `maintree-oldcfg.{json,log}` — 主树整树 × 旧配置（对照：0）
- `maintree-newcfg.{json,log}` — 主树整树 × 定稿配置（5）
- `probe-hpke-nocfg.json` / `probe-venv-newcfg.json` — hpke 直扫 2 / .venv 直扫 0（§3.1）

客户端仓同目录（`D:/HermesWorkspace/Vade-client/shots/s-gitleaks-final/`）有同一套证据（其三道扫描计数 7/7/7、grep 0/0）。
