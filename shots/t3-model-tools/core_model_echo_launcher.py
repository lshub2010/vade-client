"""T3 客户端半 · 真 core + 真 `com.example.model` + 真 `com.example.echo` 启动器。

供 `RealCoreToolCardsE2ETest`（desktopTest）与人工复跑共用。它起的是**生产同构**的链路，
只有两处替身，且两处都在网络边界之外：

  1. 真 `vade.app.App`（真 identity / registry / session / policy / 真会话库，uvicorn 127.0.0.1:0）；
  2. 真 `com.example.model` 模块子进程，`[llm] provider = "module"`、`fallback_to_builtin = false`
     —— 模型**不是** core 内置的 MockLLMProvider，走的是真模块 RPC；
  3. 真 `com.example.echo` 模块子进程（echo / add / sleep / boom 四个工具）；
  4. 唯一的假东西：模型模块背后的 **OpenAI 兼容上游**（stdlib `http.server`）。第一轮在一个
     assistant 消息里回四个 `tool_calls`，名字按 core 广告的工具名后缀解析（`*__echo` …），
     第二轮回 final 文本。

`boom` 在 echo 的 manifest 里申报 `low`，本启动器把它抬到 `high`，于是 core 在真正执行它之前
发 `turn.approval_required`（risk=`high`），回合停在 `waiting_approval`。客户端据此把**那张**
工具卡升级成审批卡（`ChatState.bindableToolCardId` 的绑定语义）。**浏览器不是合法 approver**
（`APPROVER_INVALID`），所以这里只验「卡存在 + 绑到 boom」，从不点批准。

## `[policy.tool_risk]` 的两条路（如实记录）

设计文档写的是 `[policy.tool_risk]` 嵌套表：

    [policy.tool_risk]
    "com.example.echo" = { boom = "high" }

本启动器**先原样写这份 TOML**（`policy.tool_risk.config = "loaded"` 时说明 core 接受了它）。
当前这份 core 的 `config._deep_merge` 会把 `policy.tool_risk.<module>` 判成 `UNKNOWN_CONFIG_KEY`
（表是 free-form，但 merge 只在已知键上递归），于是启动器退回**同一语义的运行期两口**：

* `app.policy.set_override("com.example.echo/boom", "high")` —— 审批判定用的 PolicyEngine
  （`[policy.tool_risk]` 的读侧就是它）；
* `app.registry.set_risk_override("com.example.echo", "boom", "high")` —— 工具绑定/快照的
  effective_risk（管理面 `module/capabilities.update` 走的是同一个方法）。

两条都是 core 自己的、只升不降的真实现，不是测试里另造的策略。`{"stage":"policy"}` 行把
`source` 与两侧的实测 risk 一起打出来，谁生效一眼可辨。

## 输出协议（stdout，一行一个 JSON）

    {"stage":"boot", "data_dir": ...}
    {"stage":"core", "port": N, "llm": "module", "llm_module": "com.example.model",
     "fallback_to_builtin": false, "policy_mode": "default"}
    {"stage":"upstream", "url": "http://127.0.0.1:PORT/v1"}
    {"stage":"policy", "tool_risk": {"com.example.echo/boom": "high"},
     "source": "config" | "runtime-set_override", "config_table": "loaded" | "rejected: ...",
     "engine": "high", "registry": "high"}
    {"stage":"module", "module_id": "com.example.model", "instance_id": "main",
     "online": true, "tools": ["chat"]}
    {"stage":"module", "module_id": "com.example.echo", "instance_id": "main",
     "online": true, "tools": ["echo","add","sleep","boom"]}
    {"stage":"ready", "port": N, "pair_code": "...", "approval_timeout_s": N,
     "upstream": "...", "modules": ["com.example.model","com.example.echo"]}
    {"stage":"fatal", "error": "..."}

用法（路径全部显式给，工作目录随意）：

    python shots/t3-model-tools/core_model_echo_launcher.py \
      --data-dir <tmp>/data \
      --core-dir D:/HermesWorkspace/Vade/Vade/core \
      --protocol-dir D:/HermesWorkspace/Vade/Vade/protocol/core/python \
      --sdk-dir D:/HermesWorkspace/Vade/Vade/sdk/python \
      --model-src D:/HermesWorkspace/Vade/Vade-module-model/src \
      --echo-src D:/HermesWorkspace/Vade/Vade-module-echo/src \
      --approval-timeout 15

**不要开 `dev.allow_all`**：它让 CoreApp 按 `AllowAll` 策略跳过审批，`turn.approval_required`
一帧都不会出现 —— 那正是本测试要验的东西。临时目录由调用方（Kotlin 测试）创建与清理；
本进程退出时只负责杀掉两个模块子进程。
"""

from __future__ import annotations

import argparse
import json
import os
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

HOST = "127.0.0.1"

MODEL_MODULE_ID = "com.example.model"
ECHO_MODULE_ID = "com.example.echo"

ECHO_TEXT = "hello-from-t3"
BOOM_MESSAGE = "t3-boom"
FINAL_TEXT = "T3-FINAL: four calls were issued; boom waited for approval as designed"

#: (工具名后缀, tool_call id, 参数) —— 顺序 = 上游在一个 assistant 消息里返回的顺序。
#: 真名不写死：由 core 广告的工具名按 `__<后缀>` 解析（`echo__boom` 之类的模块前缀归 core）。
CALL_SPECS: tuple[tuple[str, str, dict[str, Any]], ...] = (
    ("echo", "call_t3_echo_1", {"text": ECHO_TEXT}),
    ("add", "call_t3_echo_2", {"a": 2, "b": 3}),
    ("sleep", "call_t3_echo_3", {"ms": 1}),
    ("boom", "call_t3_echo_4", {"message": BOOM_MESSAGE}),
)

TOOL_SUFFIXES = tuple(suffix for suffix, _, _ in CALL_SPECS)

#: 抬到 high 的那一对：`(module_id, tool)`。两个键分开，registry 要 (module, tool)，
#: PolicyEngine 要 tool_id（`module/tool`）。
RISK_MODULE = ECHO_MODULE_ID
RISK_TOOL = "boom"
RISK_LEVEL = "high"


def emit(obj: dict[str, Any]) -> None:
    print(json.dumps(obj, ensure_ascii=False), flush=True)


# --------------------------------------------------------------------- upstream
class UpstreamState:
    """假上游看到的东西（handler 线程写，主线程读）。"""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.requests: list[dict[str, Any]] = []
        self.tools: dict[str, str] = {}
        self.problem: str | None = None

    def record(self, body: dict[str, Any]) -> int:
        with self.lock:
            self.requests.append(body)
            return len(self.requests)

    def set_tools(self, mapping: dict[str, str]) -> None:
        with self.lock:
            self.tools = dict(mapping)

    def fail(self, problem: str) -> None:
        with self.lock:
            self.problem = problem

    def snapshot(self) -> tuple[list[dict[str, Any]], dict[str, str], str | None]:
        with self.lock:
            return list(self.requests), dict(self.tools), self.problem


class FakeOpenAIHandler(BaseHTTPRequestHandler):
    """一个回合两步：四个 tool_calls（一个 assistant 消息）→ final 文本。"""

    protocol_version = "HTTP/1.1"
    server_version = "T3ToolCardsUpstream/1.0"

    state: UpstreamState

    def log_message(self, fmt: str, *args: Any) -> None:  # noqa: D401 - stdlib hook
        return  # stdout 是协议行，别让请求日志掺进去

    def _send(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self) -> None:  # noqa: N802 - stdlib hook
        if self.path.rstrip("/") not in ("/v1/chat/completions", "/chat/completions"):
            self._send(404, {"error": {"type": "not_found", "message": self.path}})
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(length) or b"{}")
        except (ValueError, TypeError, OSError) as exc:
            self.state.fail(f"upstream could not parse the request body: {exc}")
            self._send(400, {"error": {"type": "bad_request", "message": str(exc)}})
            return

        self.state.record(body)
        messages = body.get("messages") or []
        tool_messages = [
            m for m in messages if isinstance(m, dict) and m.get("role") == "tool"
        ]

        if tool_messages:
            # 第二轮：core 已经把（能跑完的）工具结果送回来。
            self._send(
                200,
                {
                    "id": "chatcmpl-t3-2",
                    "object": "chat.completion",
                    "model": body.get("model") or "fake-t3",
                    "choices": [
                        {
                            "index": 0,
                            "message": {
                                "role": "assistant",
                                "content": FINAL_TEXT,
                                "tool_calls": [],
                            },
                            "finish_reason": "stop",
                        }
                    ],
                    "usage": {"prompt_tokens": 42, "completion_tokens": 11},
                },
            )
            return

        # 第一轮：从 core 广告的工具名按后缀解析真名（硬编码 `echo__echo` 会在 core
        # 广告的前缀变化时静默通过，等于没验「工具表真的到上游了」）。
        names = []
        for spec in body.get("tools") or []:
            function = (spec or {}).get("function") or {}
            name = function.get("name")
            if isinstance(name, str) and name:
                names.append(name)
        chosen: dict[str, str] = {}
        for suffix in TOOL_SUFFIXES:
            matches = [name for name in names if name.endswith("__" + suffix)]
            if len(matches) != 1:
                problem = (
                    f"expected exactly one advertised tool ending in '__{suffix}', "
                    f"got {matches!r}; tools={names!r}"
                )
                self.state.fail(problem)
                self._send(
                    400, {"error": {"type": "t3_tool_mismatch", "message": problem}}
                )
                return
            chosen[suffix] = matches[0]
        self.state.set_tools(chosen)

        tool_calls = [
            {
                "id": call_id,
                "type": "function",
                "function": {
                    "name": chosen[suffix],
                    "arguments": json.dumps(args, ensure_ascii=False),
                },
            }
            for suffix, call_id, args in CALL_SPECS
        ]
        self._send(
            200,
            {
                "id": "chatcmpl-t3-1",
                "object": "chat.completion",
                "model": body.get("model") or "fake-t3",
                "choices": [
                    {
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": None,
                            "tool_calls": tool_calls,
                        },
                        "finish_reason": "tool_calls",
                    }
                ],
                "usage": {"prompt_tokens": 31, "completion_tokens": 7},
            },
        )


def start_upstream() -> tuple[ThreadingHTTPServer, UpstreamState, str]:
    state = UpstreamState()
    handler = type("T3ToolCardsHandler", (FakeOpenAIHandler,), {"state": state})
    server = ThreadingHTTPServer((HOST, 0), handler)
    server.daemon_threads = True
    thread = threading.Thread(
        target=server.serve_forever, name="t3-toolcards-upstream", daemon=True
    )
    thread.start()
    port = int(server.server_address[1])
    return server, state, f"http://{HOST}:{port}/v1"


# ----------------------------------------------------------------------- config
def _config_text(
    *,
    data_dir: Path,
    port: int,
    approval_timeout: int,
    with_tool_risk: bool,
) -> str:
    lines = [
        "[core]",
        f'listen = "{HOST}:{port}"',
        f'data_dir = "{data_dir}"',
        'log_level = "warning"',
        "",
        "[policy]",
        f"approval_timeout_s = {approval_timeout}",
        "",
        # 生产同构：模型来自模块，模型模块不在就失败，绝不悄悄退回内置 provider。
        "[llm]",
        'provider = "module"',
        f'model_module = "{MODEL_MODULE_ID}"',
        "fallback_to_builtin = false",
        "",
        # 允许缺失组件（本进程只要数据面）；**不写 allow_all** —— 那会跳过审批。
        "[dev]",
        "allow_missing_components = true",
        "",
    ]
    if with_tool_risk:
        lines += [
            "[policy.tool_risk]",
            f'"{RISK_MODULE}" = {{ {RISK_TOOL} = "{RISK_LEVEL}" }}',
            "",
        ]
    return "\n".join(lines)


def load_core_config(
    *, work: Path, data_dir: Path, port: int, approval_timeout: int
):
    """先按设计文档写 `[policy.tool_risk]`；core 拒绝时退回无表配置并注明。"""
    from vade.config import load_config

    with_table = work / "core.toml"
    plain = work / "core.plain.toml"
    with_table.write_text(
        _config_text(
            data_dir=data_dir,
            port=port,
            approval_timeout=approval_timeout,
            with_tool_risk=True,
        ),
        encoding="utf-8",
    )
    plain.write_text(
        _config_text(
            data_dir=data_dir,
            port=port,
            approval_timeout=approval_timeout,
            with_tool_risk=False,
        ),
        encoding="utf-8",
    )
    try:
        return load_config(toml_path=str(with_table)), "loaded", None
    except Exception as exc:  # ConfigError：表被 merge 判成未知键
        first = (str(exc).splitlines() or [type(exc).__name__])[0][:300]
        config = load_config(toml_path=str(plain))
        return config, f"rejected: {type(exc).__name__}: {first}", exc


# ---------------------------------------------------------------------- modules
def _module_env(
    base: dict[str, str],
    *,
    sdk_dir: str,
    protocol_dir: str,
    module_src: Path,
    key_path: Path,
    data_dir: Path,
    core_public_key: str,
) -> dict[str, str]:
    env = dict(base)
    env["PYTHONPATH"] = os.pathsep.join(
        [sdk_dir, str(module_src), protocol_dir, env.get("PYTHONPATH", "")]
    )
    env["VADE_PROTOCOL_PY"] = protocol_dir
    env["MODULE_KEY_PATH"] = str(key_path)
    env["VADE_DATA_DIR"] = str(data_dir)
    env["CORE_PUBLIC_KEY"] = core_public_key
    # 本机 shell 里可能残留配对变量：模块的 run() 见 VADE_PAIR_CODE 就走「配对」分支
    # 直接 exit 2，看起来像「模块连不上」—— IK 路径只认 CORE_PUBLIC_KEY。
    env.pop("VADE_PAIR_CODE", None)
    env.pop("VADE_CORE_URL", None)
    return env


def _spawn(
    module_dir: Path, module: str, env: dict[str, str], log: Any, port: int
) -> subprocess.Popen:
    return subprocess.Popen(
        [sys.executable, "-m", module, "--core", f"ws://{HOST}:{port}"],
        cwd=str(module_dir),
        env=env,
        stdout=log,
        stderr=subprocess.STDOUT,
        text=True,
    )


def _stop(proc: subprocess.Popen | None) -> None:
    if proc is None or proc.poll() is not None:
        return
    proc.send_signal(signal.SIGTERM)
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            pass


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind((HOST, 0))
        return int(sock.getsockname()[1])


def _online(record: Any) -> bool:
    health = getattr(record, "health", None)
    return str(getattr(health, "value", health)).lower().endswith("online")


# ------------------------------------------------------------------------- main
def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--data-dir", required=True)
    ap.add_argument("--core-dir", required=True)
    ap.add_argument("--protocol-dir", required=True)
    ap.add_argument("--sdk-dir", required=True)
    ap.add_argument("--model-src", required=True, help="Vade-module-model/src")
    ap.add_argument("--echo-src", required=True, help="Vade-module-echo/src")
    ap.add_argument(
        "--approval-timeout",
        type=int,
        default=15,
        help="policy.approval_timeout_s（秒，5..3600；默认 15）",
    )
    ap.add_argument("--port", type=int, default=0, help="core 监听端口（0 = 随机）")
    ap.add_argument("--pair-ttl", type=int, default=600)
    ap.add_argument("--module-wait-s", type=float, default=90.0)
    args = ap.parse_args()

    data_dir = Path(args.data_dir).expanduser().resolve()
    data_dir.mkdir(parents=True, exist_ok=True)
    work = data_dir.parent
    emit({"stage": "boot", "data_dir": str(data_dir)})

    for path in (args.protocol_dir, args.core_dir, args.sdk_dir):
        sys.path.insert(0, path)

    from vade import __main__ as vade_main
    from vade.app import App, install_components, make_rpc_handler
    from vade.identity.pairing import Purpose
    from vade.transport.server import CoreApp
    import uvicorn

    from vade_sdk.transport import fingerprint_of, load_or_create_key

    if args.approval_timeout < 5 or args.approval_timeout > 3600:
        raise SystemExit("--approval-timeout must be within 5..3600 (core schema)")

    config, config_table, config_error = load_core_config(
        work=work,
        data_dir=data_dir,
        port=args.port,
        approval_timeout=args.approval_timeout,
    )
    if config_table != "loaded":
        emit({"stage": "note", "policy_tool_risk": config_table, "fallback": "runtime"})

    model_src = Path(args.model_src).expanduser().resolve()
    echo_src = Path(args.echo_src).expanduser().resolve()
    for label, src, package in (
        ("model", model_src, "model_module"),
        ("echo", echo_src, "echo_module"),
    ):
        if not (src / package).is_dir():
            raise SystemExit(f"{label} module not found under {src} (expects {package}/)")

    app = App(config)
    import asyncio

    asyncio.run(app.startup())
    installed = install_components(app, strict=False)
    emit({"stage": "components", "installed": sorted(installed)})

    if not config_table.startswith("loaded"):
        # 运行期抬 risk：#1 审批判定（`[policy.tool_risk]` 的读侧），#2 工具绑定的
        # effective_risk（管理面同一方法）。两条都只升不降，core 自己会拒绝降级。
        app.policy.set_override(f"{RISK_MODULE}/{RISK_TOOL}", RISK_LEVEL)

    # 模块密钥带外信任：core 的 listener 只建 IK responder，没有模块配对流程。
    model_key_path = work / "model.key"
    echo_key_path = work / "echo.key"
    _seed, model_public = load_or_create_key(model_key_path)
    _seed, echo_public = load_or_create_key(echo_key_path)
    app.whitelist.add(
        MODEL_MODULE_ID, "main", fingerprint_of(model_public), public_key=model_public
    )
    app.whitelist.add(
        ECHO_MODULE_ID, "main", fingerprint_of(echo_public), public_key=echo_public
    )

    pair_code = app.pairing.create(Purpose.BROWSER, ttl=args.pair_ttl)

    core_app = CoreApp(
        config=config,
        identity=app.identity,
        registry=app.registry,
        pairing=app.pairing,
        whitelist=app.whitelist,
        make_crypto=vade_main.build_module_crypto(app),
        on_rpc=make_rpc_handler(app),
        # 生产口径：没有 allow_all 就是 "default"（approval 真的会等）
        policy_mode="default",
        data_dir=data_dir,
    )
    server = uvicorn.Server(
        uvicorn.Config(
            core_app,
            host=HOST,
            port=args.port,
            log_level="warning",
            lifespan="off",
            proxy_headers=False,
        )
    )
    server_thread = threading.Thread(target=server.run, name="t3-toolcards-core", daemon=True)
    server_thread.start()
    deadline = time.time() + 30
    while not server.started:
        if time.time() > deadline or not server_thread.is_alive():
            raise RuntimeError("uvicorn did not start within 30s")
        time.sleep(0.02)
    port = int(server.servers[0].sockets[0].getsockname()[1])
    emit(
        {
            "stage": "core",
            "port": port,
            "llm": str(config.get("llm.provider")),
            "llm_module": str(config.get("llm.model_module")),
            "fallback_to_builtin": bool(config.get("llm.fallback_to_builtin")),
            "policy_mode": "default",
            "dev_allow_all": bool(config.get("dev.allow_all")),
        }
    )

    upstream, upstream_state, upstream_url = start_upstream()
    emit({"stage": "upstream", "url": upstream_url})

    base_env = dict(os.environ)
    model_env = _module_env(
        base_env,
        sdk_dir=args.sdk_dir,
        protocol_dir=args.protocol_dir,
        module_src=model_src,
        key_path=model_key_path,
        data_dir=work / "model-data",
        core_public_key=app.identity.public_key.hex(),
    )
    model_env.update(
        {
            "MODEL_NAME": "fake-t3",
            "MODEL_MAX_RETRIES": "0",
            "MODEL_RETRY_BASE_S": "0",
            "MODEL_BASE_URL": upstream_url,
        }
    )
    echo_env = _module_env(
        base_env,
        sdk_dir=args.sdk_dir,
        protocol_dir=args.protocol_dir,
        module_src=echo_src,
        key_path=echo_key_path,
        data_dir=work / "echo-data",
        core_public_key=app.identity.public_key.hex(),
    )

    model_log_path = work / "model.log"
    echo_log_path = work / "echo.log"
    model_log = model_log_path.open("w", encoding="utf-8")
    echo_log = echo_log_path.open("w", encoding="utf-8")
    model_proc: subprocess.Popen | None = None
    echo_proc: subprocess.Popen | None = None

    try:
        model_proc = _spawn(model_src, "model_module", model_env, model_log, port)
        echo_proc = _spawn(echo_src, "echo_module", echo_env, echo_log, port)

        expected = {MODEL_MODULE_ID: "chat", ECHO_MODULE_ID: "echo"}
        seen: dict[str, Any] = {}
        deadline = time.time() + args.module_wait_s
        while time.time() < deadline and len(seen) < len(expected):
            for record in app.registry.snapshot():
                manifest = getattr(record, "manifest", None)
                if manifest is None or not getattr(record, "connection_id", None):
                    continue
                wanted = expected.get(manifest.module_id)
                if wanted and any(spec.name == wanted for spec in manifest.tools):
                    seen[manifest.module_id] = record
            if len(seen) == len(expected):
                break
            for label, proc, log_path in (
                ("model", model_proc, model_log_path),
                ("echo", echo_proc, echo_log_path),
            ):
                if proc is not None and proc.poll() is not None:
                    tail = log_path.read_text(encoding="utf-8", errors="replace")[-2000:]
                    raise RuntimeError(
                        f"{label} module exited {proc.returncode}:\n{tail}"
                    )
            time.sleep(0.1)
        if set(seen) != set(expected):
            tails = {
                "model": model_log_path.read_text(encoding="utf-8", errors="replace")[-1500:],
                "echo": echo_log_path.read_text(encoding="utf-8", errors="replace")[-1500:],
            }
            raise RuntimeError(
                f"modules never registered within {args.module_wait_s}s: "
                f"online={sorted(seen)} expected={sorted(expected)}\n{tails}"
            )
        for module_id in (MODEL_MODULE_ID, ECHO_MODULE_ID):
            record = seen[module_id]
            manifest = record.manifest
            emit(
                {
                    "stage": "module",
                    "module_id": module_id,
                    "instance_id": record.instance_id,
                    "online": _online(record),
                    "tools": [spec.name for spec in manifest.tools],
                }
            )

        # 工具绑定侧的 risk：与 [policy.tool_risk] 同一语义（只升不降）。必须在模块
        # 注册之后（方法按 (module, tool) 找已注册的绑定），早于 ready。
        registry_risk: str | None = None
        try:
            app.registry.set_risk_override(RISK_MODULE, RISK_TOOL, RISK_LEVEL)
        except Exception as exc:  # 已经 high / 未注册 —— 都要如实打出来
            registry_risk = f"error: {type(exc).__name__}: {exc}"
        bindings = app.registry.tools()
        binding = bindings.get(f"{RISK_MODULE}/{RISK_TOOL}@main")
        engine_risk = app.policy.risk_of(f"{RISK_MODULE}/{RISK_TOOL}", "low")
        emit(
            {
                "stage": "policy",
                "tool_risk": {f"{RISK_MODULE}/{RISK_TOOL}": RISK_LEVEL},
                "source": "config" if config_table == "loaded" else "runtime-set_override",
                "config_table": config_table,
                "engine": engine_risk,
                "registry": (
                    getattr(binding, "effective_risk", None) if binding else registry_risk
                ),
            }
        )

        emit(
            {
                "stage": "ready",
                "port": port,
                "pair_code": pair_code,
                "approval_timeout_s": args.approval_timeout,
                "upstream": upstream_url,
                "modules": sorted(seen),
                "echo_text": ECHO_TEXT,
                "boom_message": BOOM_MESSAGE,
                "call_ids": [call_id for _, call_id, _ in CALL_SPECS],
            }
        )

        # 常驻：core 线程或任一模块死了就报出来（Kotlin 侧只负责在对的位置杀进程）。
        #
        # SIGTERM 必须走 SystemExit（而不是默认的「立刻终止」），否则 `finally` 不执行、
        # 两个模块子进程会变成孤儿（实测见过：`ps` 里挂着 `-m model_module`）。Kotlin 侧
        # 先 `destroy()`（= SIGTERM）再 `destroyForcibly()`，就是为了给这段收尾留时间窗。
        signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
        while server_thread.is_alive():
            for label, proc, log_path in (
                ("model", model_proc, model_log_path),
                ("echo", echo_proc, echo_log_path),
            ):
                if proc is not None and proc.poll() is not None:
                    tail = log_path.read_text(encoding="utf-8", errors="replace")[-2000:]
                    emit(
                        {
                            "stage": "fatal",
                            "error": f"{label} module exited {proc.returncode} while serving:\n{tail}",
                        }
                    )
                    return 1
            time.sleep(0.2)
        emit({"stage": "fatal", "error": "core server stopped"})
        return 1
    finally:
        _stop(model_proc)
        _stop(echo_proc)
        for log in (model_log, echo_log):
            try:
                log.close()
            except Exception:
                pass
        upstream.shutdown()
        upstream.server_close()
        print("\n--- model module log (tail) ---", flush=True)
        print(
            "\n".join(
                model_log_path.read_text(encoding="utf-8", errors="replace").splitlines()[-20:]
            ),
            flush=True,
        )
        print("\n--- echo module log (tail) ---", flush=True)
        print(
            "\n".join(
                echo_log_path.read_text(encoding="utf-8", errors="replace").splitlines()[-20:]
            ),
            flush=True,
        )
        requests, advertised, problem = upstream_state.snapshot()
        print(
            "\n--- fake upstream ---\n"
            + json.dumps(
                {
                    "requests": len(requests),
                    "tools": advertised,
                    "problem": problem,
                },
                ensure_ascii=False,
            ),
            flush=True,
        )


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SystemExit:
        raise
    except BaseException:
        emit({"stage": "fatal", "error": traceback.format_exc(limit=10)})
        sys.exit(1)
