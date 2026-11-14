# A6 · gitleaks + 私钥 grep

> 本文件只写可核对的陈述（命中数、规则名、文件路径、sha）。每个数字都能在 `raw/` 下的原始 JSON / 日志里复核。

## 1. 结论速览

| 仓库 | 扫描时 HEAD | gitleaks 工作区（`dir`，含未跟踪） | gitleaks 历史（HEAD 可达） | gitleaks 历史（全 refs） | 私钥 grep 工作区 | 私钥 grep 历史 |
|---|---|---|---|---|---|---|
| `Vade-client`（main） | `e800bc4` | **0 命中** | **0 命中**（132 commits） | **0 命中**（132 commits） | **0 命中** | **0 次出现**（188 commits） |
| `Vade`（dev_gingshan） | `0138437` | **0 命中** | **0 命中**（141 commits） | **0 命中**（141 commits） | 6 条（**全部为假**，见 §6） | 597 次出现 / **3 个不同值**（全部为假，见 §6） |

- gitleaks 侧：**0 命中**（两仓 × 工作区/历史/全 refs，共 6 次扫描，退出码全 0）。
- grep 侧：客户端 0 命中；主仓 6 条形态命中 + 历史里 597 次出现，**去重后只有 3 个不同值，全部是测试夹具里的假串**，没有一条是私钥或云 key。
- 因此 A6「gitleaks 0 命中」**字面成立**；「私钥 grep 0 命中」在客户端成立，在主仓有 6 条**形态命中但非密钥**。要字面清零需要改测试夹具里的假 PEM 头（属于改源码，本轮不做，见 §8）。

## 2. 工具与安装

| 项 | 值 |
|---|---|
| 版本 | `8.30.1`（`gitleaks version` 原样输出） |
| 产物 | `D:/Hermes/tools/gitleaks/gitleaks.exe`（windows x64 zip 解压，22.5 MB） |
| 下载源 | `https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_windows_x64.zip`（直连，未走代理） |
| 安装方式 | 用户级解压，无 UAC、无 PATH 修改（脚本里用绝对路径调用） |
| 基础规则集 | gitleaks 官方默认规则集，经 `[extend] useDefault = true` 继承，不在仓库里 fork 规则 |

为什么用 `useDefault` 而不是把 100+ 条规则抄进仓库：抄一份就会随工具升级腐化，且容易被后人手改成「更宽松的本地版」。`useDefault` 保证规则永远等于官方发行版自带的那份，仓库里只留**白名单**这一层，评审时只需看白名单是否讲得通。

## 3. 配置文件全文

两仓内容完全一致（`md5 = 8ad8a7e2f8a79d7fdc6de26196d4030a`，2026-11-14 校验）：

- `D:/HermesWorkspace/Vade-client/.gitleaks.toml`
- `D:/HermesWorkspace/Vade/Vade/.gitleaks.toml`

```toml
# .gitleaks.toml — Vade 仓库 gitleaks 配置（两仓内容一致）
#
# 基础规则集：gitleaks v8.30.1 内建的官方默认规则集（[extend] useDefault = true），
# 不本地 fork 规则，随工具升级自动同步官方新增规则。
#
# 白名单策略（硬约束）：
#   1. 只允许“已知误报”，每条必须有 description 说明为什么不是凭据；
#   2. 禁止整目录 paths 排除（build/、.venv/、tests/、docs/ 之类一律不做目录级豁免）；
#   3. 能用行级 regex 锁死的就不写 paths；必须写 paths 时锚定到具体文件。
# 因此本文件里的每一条都是「具体路径 + 具体行特征」，其余一律照常扫描、照常报。

[extend]
useDefault = true

# ---------------------------------------------------------------------------
# 1) JDK 自带 security 属性文件里的密码套件参数列表
#    命中文本形如 "jdk.tls.keyLimits=AES/GCM/NoPadding KeyUpdate 2^37"。
#    这是 JDK 的 TLS key update 阈值声明，不是任何形式的凭据；
#    该文件会被构建流程复制到 composeApp/build/... 下，行内容不变。
#    只放行 keyLimits 这两种 JDK 属性名，其余行照扫。
# ---------------------------------------------------------------------------
[[allowlists]]
description = "JDK java.security 的 jdk.tls.keyLimits / jdk.quic.tls.keyLimits 属性行（密码套件参数，非凭据）"
regexTarget = "line"
regexes = [
  '''jdk\.(?:quic\.)?tls\.keyLimits\s*=''',
]

# ---------------------------------------------------------------------------
# 2) docs/QUICKSTART.md 的 WebSocket 握手示例
#    命中文本形如 curl -H "Sec-WebSocket-Key: <24 字符 base64>"。
#    RFC 6455 §4.1:Sec-WebSocket-Key 是客户端每次连接随机生成的 16 字节 nonce,
#    服务端只回显到 Accept 头里做握手校验,不具备任何授权能力。
#    仅限该文档内的 Sec-WebSocket-Key 头,同文件的其它行照扫。
# ---------------------------------------------------------------------------
[[allowlists]]
description = "docs/QUICKSTART.md 中 WebSocket 握手示例的 Sec-WebSocket-Key（RFC6455 随机 nonce，非凭据）"
paths = ['''(^|/)docs/QUICKSTART\.md$''']
regexTarget = "line"
regexes = [
  '''Sec-WebSocket-Key:\s*[A-Za-z0-9+/=]{16,}''',
]

# ---------------------------------------------------------------------------
# 3) cryptography wheel 自带的 hpke.pyi 类型存根
#    命中文本形如 "private_key: x25519.X25519PrivateKey | ec.EllipticCurvePrivateKey"。
#    这是 PEP 484 类型注解（联合类型），没有字面量密钥值。
#    用行特征锁死,不按 .venv/ 目录豁免。
# ---------------------------------------------------------------------------
[[allowlists]]
description = "cryptography hpke.pyi 类型存根里的 private_key: <类型> 注解（无字面量密钥）"
regexTarget = "line"
regexes = [
  '''private_key:\s*\w+(?:\.\w+)*PrivateKey''',
]

# ---------------------------------------------------------------------------
# 4) core/tests/admin/test_passwords.py 的“错误口令”测试夹具
#    该用例断言错误口令返回 401,夹具值是自造的无效串。
#    锚定到 log_in(...) 调用的 password 实参,文件内其它行照扫。
# ---------------------------------------------------------------------------
[[allowlists]]
description = "core/tests/admin/test_passwords.py 中 log_in(...) 的假口令夹具（断言 401 用）"
paths = ['''(^|/)core/tests/admin/test_passwords\.py$''']
regexTarget = "line"
regexes = [
  '''log_in\(.*password="''',
]

# ---------------------------------------------------------------------------
# 5) core/tests/input/test_audit.py 的 SECRET 金丝雀常量
#    该测试用 SECRET 断言「提交的输入值不得出现在日志/审计行里」
#    (assert SECRET not in everything)。值是合成的 sk-live-<14 位>,
#    长度 22;真实 Stripe live key 为 sk_live_ 前缀、长度约 107,格式与长度均不符。
#    锚定文件常量声明行,文件内其它赋值照扫。
# ---------------------------------------------------------------------------
[[allowlists]]
description = "core/tests/input/test_audit.py 的 SECRET 金丝雀常量（redaction 断言用，合成值非真实 key）"
paths = ['''(^|/)core/tests/input/test_audit\.py$''']
regexTarget = "line"
regexes = [
  '''^SECRET = "sk-''',
]

# ---------------------------------------------------------------------------
# 6) core/tests/loop/test_args_redaction.py 的假参数表
#    整个文件就是在测「命令行/env 里的 secret 会被打码」,
#    表里的 Bearer token 与 AWS_ACCESS_KEY_ID 正是被断言要打码的输入,非凭据。
#    只放行这两类行,同文件其它行照扫。
# ---------------------------------------------------------------------------
[[allowlists]]
description = "core/tests/loop/test_args_redaction.py 的假凭据参数表（被测对象就是“会被打码”的输入）"
paths = ['''(^|/)core/tests/loop/test_args_redaction\.py$''']
regexTarget = "line"
regexes = [
  '''Authorization: Bearer''',
  '''AWS_ACCESS_KEY_ID=''',
]

# ---------------------------------------------------------------------------
# 7) 上条夹具的字节码副本（同一份假值编译产物）
#    __pycache__ 被 .gitignore 覆盖、从未入库;文件名锚定到该测试模块,
#    不放行整个 __pycache__ 目录。
# ---------------------------------------------------------------------------
[[allowlists]]
description = "test_args_redaction 的 .pyc 编译副本（同一夹具的字节码，gitignored 未入库）"
paths = ['''(^|/)core/tests/loop/__pycache__/test_args_redaction\..*\.pyc$''']
```

## 4. 白名单逐条理由（7 条，全部「具体路径 + 具体行特征」，无整目录排除）

| # | 放行对象 | 锚定方式 | 为什么不是凭据 |
|---|---|---|---|
| 1 | JDK `java.security` 的 `jdk.tls.keyLimits=` / `jdk.quic.tls.keyLimits=` 行 | 仅行级正则，不限路径 | 值是密码套件参数（`AES/GCM/NoPadding KeyUpdate 2^37`），JDK 自带的 key-update 阈值声明 |
| 2 | `docs/QUICKSTART.md` 的 `Sec-WebSocket-Key` | 路径锚定该单个 .md + 行级正则 | RFC 6455 §4.1 规定的每连接随机 nonce，服务端只回显做握手校验，无授权能力 |
| 3 | `cryptography` 的 `hpke.pyi` 里 `private_key: x25519.X25519PrivateKey` | 仅行级正则（类型注解形态） | PEP 484 类型注解，冒号后是类型名不是字面量 |
| 4 | `core/tests/admin/test_passwords.py` 的 `log_in(..., password="...")` | 路径锚定单文件 + 行级正则 | 断言错误口令返回 401 的夹具 |
| 5 | `core/tests/input/test_audit.py` 的 `SECRET = "sk-..."` | 路径锚定单文件 + 行首常量声明 | redaction 金丝雀：测试断言该值**不得**出现在日志/审计行里 |
| 6 | `core/tests/loop/test_args_redaction.py` 的 `Authorization: Bearer` / `AWS_ACCESS_KEY_ID=` | 路径锚定单文件 + 两行级正则 | 整个文件测的就是「命令行/env 里的 secret 会被打码」，这两个值是被测对象 |
| 7 | 上述夹具的 `.pyc` 副本 `__pycache__/test_args_redaction.*.pyc` | 路径锚定到该模块的 .pyc | 同一份假值的字节码，`__pycache__/` 被 `.gitignore` 覆盖、从未入库 |

没有任何一条 `paths` 指向目录：`docs/`、`core/tests/`、`.venv/`、`build/`、`__pycache__/` 都不存在目录级豁免。`paths` 正则写成 `(^|/)...` 是为了同时适配「相对路径扫描」和「绝对路径扫描」两种调用方式，锚定的仍是**同一个文件**。

## 5. 扫描明细

### 5.1 加白名单之前（默认规则集，用于说明白名单是从哪来的）

| 仓库 | 工作区 | 历史 |
|---|---|---|
| `Vade-client` | 3 命中 | 1 命中 |
| `Vade` | 7 命中 | 5 命中 |

明细（`--redact`，报告里没有明文值）：

| 仓库 | 规则 | 文件:行 |
|---|---|---|
| client | `generic-api-key` | `composeApp/build/compose/tmp/main/runtime/conf/security/java.security:961` / `:988` |
| client | `generic-api-key` | `docs/QUICKSTART.md:204`（历史里是 `:202`，commit `75f6c6f9`） |
| main | `generic-api-key` | `.venv/Lib/site-packages/cryptography/hazmat/bindings/_rust/openssl/hpke.pyi:77` / `:101` |
| main | `generic-api-key` | `core/tests/admin/test_passwords.py:96`（历史 `:94`，commit `c75a1d43`、`e31d4f7f`） |
| main | `generic-api-key` | `core/tests/input/test_audit.py:14`（commit `1300ce65`） |
| main | `aws-access-token` | `core/tests/loop/test_args_redaction.py:119`（历史 `:64`，commit `4e8c02b3`） |
| main | `curl-auth-header` | `core/tests/loop/test_args_redaction.py:118`（历史 `:63`，commit `4e8c02b3`） |
| main | `aws-access-token` | `core/tests/loop/__pycache__/test_args_redaction.cpython-312-pytest-9.2.0.pyc:122` |

### 5.2 加白名单之后（本轮交付口径）

| 扫描 | 命令 | 数据量 | 结果 |
|---|---|---|---|
| client 工作区 | `gitleaks dir .` | 189.09 MB | 0 命中 |
| client 历史 | `gitleaks git .` | 132 commits / 3.62 MB | 0 命中 |
| client 历史全 refs | `gitleaks git . --log-opts=--all` | 132 commits / 3.62 MB | 0 命中 |
| main 工作区 | `gitleaks dir .` | 121.29 MB | 0 命中 |
| main 历史 | `gitleaks git .` | 141 commits / 9.42 MB | 0 命中 |
| main 历史全 refs | `gitleaks git . --log-opts=--all` | 141 commits / 9.42 MB | 0 命中 |

报告 JSON 全部是 `[]`，日志末行全部 `no leaks found`，退出码全部 `0`。

### 5.3 白名单没有削弱扫描的证据（金丝雀）

用一份临时 canary 文件（**不在两仓内**，用完即删）在**同一份配置**下验证规则仍然生效：

| canary 内容 | 结果 |
|---|---|
| `AKIA` + 16 位随机 + `ghp_` + 36 位 + 完整 PEM 私钥块 + `sk-` 串 + `api_key = "..."` | 命中 `github-pat`、`private-key`、`generic-api-key` 三条规则 |

说明：`private-key`、`generic-api-key` 这两条正好是被白名单「重点照顾」的规则，canary 仍报，证明 7 条 allowlist 都是窄口径；`private-key` 在真实扫描里 0 命中，是因为两仓里只有**没有 END footer 的孤立 PEM 头**（见 §6），不是因为规则被关掉。

## 6. 私钥 / 云 key 特征串 grep（A6 第二道）

特征集：`BEGIN (RSA|OPENSSH|EC|DSA|PGP|ENCRYPTED )?PRIVATE KEY`、`AKIA[0-9A-Z]{16}`、`ASIA[0-9A-Z]{16}`、`sk-[A-Za-z0-9]{20,}`、`sk_live_[A-Za-z0-9]{10,}`、`ghp_/gho_[A-Za-z0-9]{36}`、`github_pat_...`、`xox[baprs]-...`、`AIza[0-9A-Za-z_-]{35}`。脚本：`shots/g-gitleaks/grep_scan.py`（只打印「文件:行 + 长度 + 前 3 位」，不落明文）。

| 仓库 | 工作区 | 历史 | positive control |
|---|---|---|---|
| `Vade-client` | 0 条 | 0 次出现（188 commits 全 refs） | 261 个文件含 `Vade` |
| `Vade` | 6 条 | 597 次出现，去重 3 个值（214 commits 全 refs） | 352 个文件含 `Vade` |

`Vade` 的 6 条工作区命中，逐条判定：

| 文件:行 | 形态 | 命中长度 | 判定 | 依据 |
|---|---|---|---|---|
| `core/tests/loop/test_tool_card_fields.py:179` | `-----BEGIN … PRIVATE KEY-----`（RSA 变体） | 26 | **假** | 纯头字符串：文件内 `BEGIN` 1 次、`-----END` 0 次、完整块 0 个；用于断言卡片字段里的 PEM 头会被打码 |
| `core/tests/test_skill/test_audit.py:15` | 同上 | 26 | **假** | 同上（END 0 次、完整块 0 个） |
| `core/tests/test_skill/test_proposal.py:159` | 同上 | 26 | **假** | 同上 |
| `core/tests/audit/test_redact.py:11` | `-----BEGIN … PRIVATE KEY-----`（无算法前缀变体） | 22 | **假** | 纯头字符串，无 body、无 END |
| `.venv/.../cryptography/.../ssh.py:77` | `-----BEGIN … PRIVATE KEY-----`（OPENSSH 变体） | 30 | **假** | 完整块但 body 仅 14 字符（真实 OpenSSH 私钥 body 是数百字符），且是第三方库源码里的格式串；`.venv/` 被 gitignore、从未入库 |
| `core/tests/loop/test_args_redaction.py:119` | `AKIA` + 16 位 | 20 | **假** | body 16 个字符是**严格字母序**（A..P，`sorted(body) == body`），随机 key 不可能有序；且只出现在这一个 redaction 测试文件里（两仓工作区 + 全历史都只有这一处） |

历史侧 597 次出现全是上表同样几行的历史版本，去重后只有 3 个不同值（2 个 PEM 头长度 22/26 + 1 个 20 位 AKIA 假值），无新增。

**没有发现任何真实私钥、真实云 key。** 两仓工作区内也没有 `*.pem / *.key / *.p12 / *.jks / id_rsa* / .env*` 这类载体文件（主仓唯一一个 `.pem` 是 `.venv` 里 `certifi` 的公共 CA 包）。

## 7. 真 / 假判定清单（汇总）

| 判定 | 条数 | 对象 |
|---|---|---|
| **真密钥** | **0** | — |
| 假 / 误报 | 16 条 gitleaks 命中（pass1：工作区 3+7、历史 1+5，含同一夹具的历史版本重复）+ 6 条 grep 形态命中 | 全部为 JDK 配置、RFC6455 nonce、类型注解、测试夹具 |

因此本轮**没有需要轮换的凭据**，也没有需要从历史里剔除的对象；`.gitignore` 无需新增条目（命中的 `.venv/`、`build/`、`__pycache__/` 都已在忽略名单里）。

## 8. 现在到底是不是 0 命中

- **gitleaks：是 0 命中。** 两仓 × 工作区/历史/全 refs = 6 次扫描，JSON 全 `[]`，退出码全 0。可随时复跑复核。
- **私钥 grep：不是字面 0 命中。** 客户端 0；主仓工作区 6 条、历史 597 次出现，去重 3 个值。这 6 条已逐条判定为假（PEM 头字符串 / 20 位有序假 AKIA），**没有一条是密钥**。
- 要把 grep 也做到字面 0，只能改 `core/tests/{loop/test_tool_card_fields.py,loop/test_args_redaction.py,test_skill/test_audit.py,test_skill/test_proposal.py,audit/test_redact.py}` 里的假串（例如改成 `[REDACTED PRIVATE KEY]` 或 `AKIA` 拼接），属于**改测试源码**，本轮硬约束不允许，留给后续单独提交。

## 9. 边界与限制（不藏）

1. **扫描期间客户端仓在被并发提交**：`c46c3a4`(21:18) → `e800bc4`(23:38)。表中数字对应 `raw/run-*.log` 打印的 sha，不是「某个永久快照」。发布前需在 freeze 的 commit 上重跑一次（§10）。
2. gitleaks 会跳过 merge 提交（client 53 个 / main 73 个 merge），所以 `commits scanned` 少于 `git rev-list --all`；merge 本身不引入新 blob，被它合并的普通提交都扫到了。
3. grep 工作区对单文件 >8 MB 的内容跳过（`build/` 里的大产物），这部分由 `gitleaks dir` 的 188 MB 全量扫描覆盖；grep 用 `-I` 跳过二进制，二进制内容同样由 gitleaks 覆盖。
4. `.venv/`、`build/`、`__pycache__/` 这些被 `.gitignore` 覆盖的目录**仍然被扫描**（`--no-ignore` / gitleaks 默认不跳过），只是命中的假值被逐条白名单——没有用目录排除来「让数字变好看」。

## 10. 复跑

```bash
# 客户端仓
cd D:/HermesWorkspace/Vade-client && bash tools/gitleaks-scan.sh

# 主仓（报告仍落在客户端的 shots/g-gitleaks/raw/）
bash tools/gitleaks-scan.sh D:/HermesWorkspace/Vade/Vade
```

脚本做三件事：`dir` / `git` / `git --log-opts=--all` 各一次（带 `--redact`）+ 私钥 grep；命中数取自 JSON 报告长度；`0` 才退出 0。

## 11. 原始文件

| 文件 | 内容 |
|---|---|
| `raw/final-Vade-client-workdir.{json,log,count}` | 客户端工作区扫描原文 |
| `raw/final-Vade-client-history.{json,log,count}` | 客户端历史（HEAD 可达） |
| `raw/final-Vade-client-history-all.{json,log,count}` | 客户端历史（全 refs） |
| `raw/final-Vade-workdir.{json,log,count}` | 主仓工作区扫描原文 |
| `raw/final-Vade-history.{json,log,count}` | 主仓历史（HEAD 可达） |
| `raw/final-Vade-history-all.{json,log,count}` | 主仓历史（全 refs） |
| `raw/pass1-*.json` | 加白名单**之前**的默认规则扫描（即 §5.1 明细的来源） |
| `raw/grep-Vade-client.log` / `raw/grep-Vade.log` | 私钥 grep 原文（值已掩码，脚本产出） |
| `raw/grep-worktree-both.log` / `raw/grep-history-both.log` | 同一份 grep 在脚本化之前的分别运行（同结论，留作交叉核对） |
| `raw/run-client.log` / `raw/run-main.log` | 两次脚本整轮运行的终端输出（含目标 repo 的 sha） |
| `grep_scan.py` | 私钥 grep 脚本（掩码输出） |
| `tools/gitleaks-scan.sh` | 四扫 + grep 的可复跑入口 |
