#!/usr/bin/env python3
"""W1-proto smoke driver: start a REAL Vade core and mint one browser pair code.

The core process is the production entry point (`python -m vade --config core.toml`,
plain HTTP data plane, TLS deliberately not configured). The admin plane (which owns
pair-code minting via /admin/api/invites) is a SEPARATE listener that `python -m vade`
does not start, so this driver mints the browser pair code through the same
`PairingStore` the core process uses, against the same `data/pairing.sqlite3` — the
code is a real, consumable one-time code, produced by the core's own class.

Outputs one JSON line on stdout: {"pid", "host", "port", "pair_code", "core_pid"}.
Keep running until killed; the data lives under a temp dir you can inspect afterwards.
"""

from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parents[2]  # shots/w1-proto -> repo root (client worktree)
VADE_ROOT = Path(r"D:/HermesWorkspace/Vade/Vade")  # the main repo (contract + core source)
PYEXE = VADE_ROOT / ".venv/Scripts/python.exe"


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class _StubLLMHandler(BaseHTTPRequestHandler):
    """OpenAI-compatible /v1/chat/completions stub: two text deltas, stop, usage, [DONE].

    The CORE stays fully real — this only replaces the model behind the builtin
    provider so a turn can complete offline (no API key, no network).
    """

    def do_POST(self) -> None:  # noqa: N802
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        chunks = [
            {"choices": [{"index": 0, "delta": {"content": "你好，"}}]},
            {"choices": [{"index": 0, "delta": {"content": "我是 Vade 核心的冒烟回复。"}}]},
            {"choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]},
            {"choices": [], "usage": {"prompt_tokens": 12, "completion_tokens": 8, "total_tokens": 20}},
        ]
        for chunk in chunks:
            self.wfile.write(f"data: {json.dumps(chunk, ensure_ascii=False)}\n\n".encode("utf-8"))
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    def log_message(self, format: str, *args: object) -> None:
        pass


def start_stub_llm() -> tuple[int, Any]:
    server = ThreadingHTTPServer(("127.0.0.1", 0), _StubLLMHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server.server_address[1], server


def main() -> int:
    workdir = Path(tempfile.mkdtemp(prefix="vade-smoke-"))
    port = free_port()
    stub_port, stub_server = start_stub_llm()
    config_path = workdir / "core.toml"
    config_path.write_text(
        "\n".join(
            [
                "[core]",
                f'listen = "127.0.0.1:{port}"',
                'admin_host = "127.0.0.1:0"',
                f'data_dir = "{workdir.as_posix()}/data"',
                'log_level = "warning"',
                "",
                "[llm]",
                'provider = "openai"',
                f'base_url = "http://127.0.0.1:{stub_port}/v1"',
                'model = "stub-model"',
                "",
                "[server]",
                'ui_origins = []',
                "",
                "[dev]",
                "allow_all = true",
                "allow_missing_components = true",
                "",
            ]
        ),
        encoding="utf-8",
    )

    core_cmd = [str(PYEXE), "-m", "vade", "--config", str(config_path)]
    # `vade` is the package at <repo>/core/vade — `python -m vade` needs it on sys.path
    # (the repo's own launch recipe runs from core/ or with PYTHONPATH=core). The native
    # protocol binding (`vade_protocol`, identity) lives at <repo>/protocol/core/python.
    core_env = dict(
        os.environ,
        PYTHONPATH=os.pathsep.join([str(VADE_ROOT / "core"), str(VADE_ROOT / "protocol" / "core" / "python")]),
    )
    core = subprocess.Popen(
        core_cmd,
        cwd=str(VADE_ROOT),
        env=core_env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )

    # Wait for /healthz on the data plane.
    deadline = time.time() + 30
    health = None
    while time.time() < deadline:
        if core.poll() is not None:
            print(f"smoke: core exited early with {core.returncode}", file=sys.stderr)
            print(core.stdout.read() if core.stdout else "", file=sys.stderr)
            return 2
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/healthz", timeout=2) as r:
                health = json.loads(r.read().decode())
                break
        except Exception:
            time.sleep(0.2)
    if health is None:
        print("smoke: /healthz never came up", file=sys.stderr)
        core.kill()
        return 3

    # Mint a browser pair code through the core's own PairingStore (same sqlite file).
    sys.path.insert(0, str(VADE_ROOT / "core"))
    from vade.identity.pairing import PairingStore, Purpose  # noqa: E402

    store = PairingStore(str(workdir / "data" / "pairing.sqlite3"))
    pair_code = store.create(Purpose.BROWSER, ttl=3600)
    store._db.close()

    banner = {
        "pid": os.getpid(),
        "host": "127.0.0.1",
        "port": port,
        "pair_code": pair_code,
        "core_pid": core.pid,
        "core_cmd": core_cmd,
        "stub_llm_port": stub_port,
        "health": health,
        "workdir": str(workdir),
    }
    print(json.dumps(banner, ensure_ascii=False))
    sys.stdout.flush()

    try:
        core.wait()
        print(f"smoke: core exited with {core.returncode}", file=sys.stderr)
        return core.returncode or 0
    except KeyboardInterrupt:
        core.terminate()
        return 0


if __name__ == "__main__":
    sys.exit(main())
