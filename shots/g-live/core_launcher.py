"""g-live · W4 实跑手册的起核脚本：启动一个**真的** Vade core（uvicorn + 真会话库）。

用法（可整段复制，数据目录换成你自己的临时目录）:
  D:/HermesWorkspace/Vade/Vade/.venv/Scripts/python.exe shots/g-live/core_launcher.py --data-dir <你的临时目录> [--port 18744]

stdout 协议（一行一个 JSON，flush）:
  {"stage": "boot", "data_dir": "..."}                 第 1 行
  {"stage": "ready", "port": N, "pair_code": "..."}    第 2 行（core 就绪；pair code 只在 stdout，绝不写进任何文件）
  {"stage": "fatal", "error": "..."}                   任何启动失败
stderr 透传 uvicorn / traceback 噪音。

行为:
  - 会话库用 <data-dir>/sessions.db（真 SQLite），不会碰任何真实数据目录;
  - LLM 走脚本化 MockLLMProvider（core 自己的 e2e 同款注入），绝不调外部 API;
  - 默认种两个演示会话（--no-seed 关掉），让 `session/list` 一打开就有真数据;
  - 铸一枚一次性浏览器配对码（Purpose.BROWSER, ttl=600），打印在 READY 行。
"""
import argparse, asyncio, json, sys, threading, time, traceback
from pathlib import Path

CORE_DIR = r"D:/HermesWorkspace/Vade/Vade/core"
PROTOCOL_DIR = r"D:/HermesWorkspace/Vade/Vade/protocol/core/python"
sys.path.insert(0, PROTOCOL_DIR)
sys.path.insert(0, CORE_DIR)

SEED_TITLES = ["W4 实跑·真核心会话甲", "W4 实跑·真核心会话乙"]


def emit(obj):
    print(json.dumps(obj, ensure_ascii=False), flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", required=True)
    ap.add_argument("--port", type=int, default=18744)
    ap.add_argument("--no-seed", action="store_true", help="do not pre-create demo sessions")
    args = ap.parse_args()
    emit({"stage": "boot", "data_dir": args.data_dir})

    import vade.session as session_pkg
    from vade.llm.base import Delta
    from vade.llm.mock import MockLLMProvider

    def _build_llm(cfg, **kwargs):
        # core 测试同款 provider 注入：脚本化 Mock，绝不调外部 API。
        return MockLLMProvider([[
            Delta(text="W4 实跑：脚本化 provider 回复。"),
            Delta(text=" -- done.", usage={"prompt_tokens": 9, "completion_tokens": 12, "total_tokens": 21}, finish_reason="stop"),
        ]])

    session_pkg._build_llm = _build_llm

    import uvicorn
    from vade import __main__ as vade_main
    from vade.app import App, install_components, make_rpc_handler
    from vade.config import load_config
    from vade.identity.pairing import Purpose
    from vade.transport.server import CoreApp

    config = load_config(cli={
        "core.data_dir": args.data_dir,
        "core.listen": "127.0.0.1:" + str(args.port),
        "dev.allow_all": True,
    })
    app = App(config)
    asyncio.run(app.startup())
    installed = install_components(app, strict=False)
    if "session" not in installed:
        raise RuntimeError("session component did not install: " + str(sorted(installed)))
    if not args.no_seed:
        for title in SEED_TITLES:
            app.session.create_session(title=title)
    pair_code = app.pairing.create(Purpose.BROWSER, ttl=600)

    core_app = CoreApp(
        config=config,
        identity=app.identity,
        registry=app.registry,
        pairing=app.pairing,
        whitelist=app.whitelist,
        make_crypto=vade_main.build_module_crypto(app),
        on_rpc=make_rpc_handler(app),
        policy_mode="allow_all",
    )
    server = uvicorn.Server(uvicorn.Config(
        core_app, host="127.0.0.1", port=args.port, log_level="warning",
        lifespan="off", proxy_headers=False,
    ))
    thread_ = threading.Thread(target=server.run, daemon=True)
    thread_.start()
    deadline = time.time() + 20
    while not server.started:
        if time.time() > deadline or not thread_.is_alive():
            raise RuntimeError("uvicorn did not start within 20s")
        time.sleep(0.02)
    port = server.servers[0].sockets[0].getsockname()[1]
    emit({"stage": "ready", "port": port, "pair_code": pair_code})
    while thread_.is_alive():
        time.sleep(0.2)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SystemExit:
        raise
    except BaseException:
        emit({"stage": "fatal", "error": traceback.format_exc(limit=8)})
        sys.exit(1)
