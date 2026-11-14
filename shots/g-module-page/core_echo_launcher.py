"""g-module-page · 真 core + 真 echo 模块的启动器（供 `RealCoreModulePageE2ETest` 与人工复跑共用）。

它只做三件事，然后把 READY / MODULE 两行 JSON 打到 stdout：

  1. 用真 `vade.app.App` 起一个真 core（uvicorn，`dev.allow_all`，随机端口，临时数据目录）；
  2. 造一把模块密钥、**带外**写进 core 的 whitelist（core 的 listener 只建 IK responder，
     没有模块配对流程 —— 见 `sdk/PYTHON_SDK.md` §5），把 `CORE_PUBLIC_KEY` 交给模块；
  3. 起 `python -m echo_module --core ws://127.0.0.1:<port>` 子进程，等它在 registry 里
     上线，然后一直活着直到被杀。

输出协议（每行一个 JSON）：

    {"stage":"boot", "data_dir": ...}
    {"stage":"ready", "port": 12345, "pair_code": "..."}     # core 已监听，可 POST /ui/session
    {"stage":"module", "module_id": "com.example.echo", "instance_id": "main", "online": true,
     "pages": 1, "page_ids": ["echo"]}                        # 模块已注册（registry 里有它）
    {"stage":"fatal", "error": "..."}

用法（工作目录随意，路径都要显式给）：

    python shots/g-module-page/core_echo_launcher.py \
      --data-dir <tmp>/data \
      --core-dir D:/HermesWorkspace/Vade/Vade/core \
      --protocol-dir D:/HermesWorkspace/Vade/Vade/protocol/core/python \
      --sdk-dir D:/HermesWorkspace/Vade/Vade/sdk/python \
      --module-src D:/HermesWorkspace/Vade/Vade-module-echo/src \
      --port 0

为什么 `pages` 要单独打一行：`ui/modules` 的入口来自 `manifest.ui.pages[]`，而
「模块没有 ui 块 → 0 页」与「渲染器坏了」在客户端画面上长得一样。启动器先把
**core 侧的事实**（注册了几个模块、声明了几页、页 id 是什么）打出来，Kotlin 侧才有
可对照的锚点，不必从画面上猜。
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import subprocess
import sys
import threading
import time
import traceback
from pathlib import Path


def emit(obj: dict) -> None:
    print(json.dumps(obj, ensure_ascii=False), flush=True)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", required=True)
    ap.add_argument("--core-dir", required=True)
    ap.add_argument("--protocol-dir", required=True)
    ap.add_argument("--sdk-dir", required=True)
    ap.add_argument("--module-src", required=True)
    ap.add_argument("--port", type=int, default=0)
    ap.add_argument("--module-module", default="com.example.echo")
    ap.add_argument("--module-instance", default="main")
    ap.add_argument("--module-wait-s", type=float, default=60.0)
    args = ap.parse_args()

    data_dir = Path(args.data_dir)
    data_dir.mkdir(parents=True, exist_ok=True)
    emit({"stage": "boot", "data_dir": str(data_dir)})

    for p in (args.protocol_dir, args.core_dir, args.sdk_dir, args.module_src):
        sys.path.insert(0, p)

    from vade import __main__ as vade_main
    from vade.app import App, install_components, make_rpc_handler
    from vade.config import load_config
    from vade.identity.pairing import Purpose
    from vade.transport.server import CoreApp
    import uvicorn

    from vade_sdk.transport import fingerprint_of, load_or_create_key

    config = load_config(
        cli={
            "core.data_dir": str(data_dir),
            "core.listen": "127.0.0.1:" + str(args.port),
            "dev.allow_all": True,
        }
    )
    app = App(config)
    asyncio.run(app.startup())
    installed = install_components(app, strict=False)
    emit({"stage": "components", "installed": sorted(installed)})

    # 模块密钥带外信任：fingerprint 必须 = SHA-256(pub)[:16]，由 whitelist.add 自己校验。
    module_key_path = Path(args.data_dir).parent / "module.key"
    _seed_hex, module_public = load_or_create_key(module_key_path)
    app.whitelist.add(
        args.module_module,
        args.module_instance,
        fingerprint_of(module_public),
        public_key=module_public,
    )

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
    server = uvicorn.Server(
        uvicorn.Config(
            core_app,
            host="127.0.0.1",
            port=args.port,
            log_level="warning",
            lifespan="off",
            proxy_headers=False,
        )
    )
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.time() + 30
    while not server.started:
        if time.time() > deadline or not thread.is_alive():
            raise RuntimeError("uvicorn did not start within 30s")
        time.sleep(0.02)
    port = server.servers[0].sockets[0].getsockname()[1]

    module_data = Path(args.data_dir).parent / "module-data"
    module_data.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ)
    # 环境里可能残留 `VADE_PAIR_CODE`/`VADE_CORE_URL`（本机 shell 就有，实测踩过）：
    # 模块的 `run()` 会 `args.pair_code or os.environ["VADE_PAIR_CODE"]`，残留值会让它走
    # 「配对」分支直接 exit 2（"pairing is not available in vade-sdk 0.1"），而看起来像
    # 「模块连不上」。IK 路径只认 `CORE_PUBLIC_KEY`，所以这两个键必须清掉。
    env.pop("VADE_PAIR_CODE", None)
    env.pop("VADE_CORE_URL", None)
    env.update(
        {
            "PYTHONPATH": os.pathsep.join(
                [args.sdk_dir, args.module_src, args.protocol_dir, env.get("PYTHONPATH", "")]
            ),
            "VADE_PROTOCOL_PY": args.protocol_dir,
            "MODULE_KEY_PATH": str(module_key_path),
            "VADE_DATA_DIR": str(module_data),
            "CORE_PUBLIC_KEY": app.identity.public_key.hex(),
        }
    )
    log_path = Path(args.data_dir).parent / "echo.log"
    log_file = open(log_path, "w", encoding="utf-8")
    module = subprocess.Popen(
        [sys.executable, "-m", "echo_module", "--core", f"ws://127.0.0.1:{port}"],
        cwd=args.module_src,
        env=env,
        stdout=log_file,
        stderr=subprocess.STDOUT,
        text=True,
    )
    emit({"stage": "ready", "port": port, "pair_code": pair_code, "echo_log": str(log_path)})

    # 等模块真的出现在 registry 里：Kotlin 侧的断言要对照的是 core 的事实，不是一厢情愿。
    deadline = time.time() + args.module_wait_s
    announced = False
    while time.time() < deadline:
        if module.poll() is not None:
            tail = log_path.read_text(encoding="utf-8", errors="replace")[-2000:]
            raise RuntimeError(f"echo module exited {module.returncode}:\n{tail}")
        records = app.registry.snapshot()
        hit = [r for r in records if r.module_id == args.module_module]
        if hit:
            record = hit[0]
            manifest = getattr(record, "manifest", None)
            pages = []
            warnings: list[str] = []
            if manifest is not None:
                # core 的字段是 `Manifest.ui_pages`（tuple[UIPage]），页 id 叫 `page_id`；
                # `ui_warnings` 说的是「哪一页被校验剔掉了」——0 页必须能分辨是没声明还是被拒。
                pages = [p.page_id for p in (getattr(manifest, "ui_pages", None) or [])]
                warnings = list(getattr(manifest, "ui_warnings", None) or [])
            health = getattr(record, "health", None)
            online = str(getattr(health, "value", health)).lower().endswith("online")
            if not announced:
                emit(
                    {
                        "stage": "module",
                        "module_id": record.module_id,
                        "instance_id": record.instance_id,
                        "online": online,
                        "display_name": getattr(manifest, "display_name", None),
                        "pages": len(pages),
                        "page_ids": pages,
                        "ui_warnings": warnings,
                    }
                )
                announced = True
            if online:
                break
        time.sleep(0.1)
    else:
        tail = log_path.read_text(encoding="utf-8", errors="replace")[-2000:]
        raise RuntimeError(f"echo module never registered within {args.module_wait_s}s:\n{tail}")

    while thread.is_alive():
        time.sleep(0.2)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SystemExit:
        raise
    except BaseException:
        emit({"stage": "fatal", "error": traceback.format_exc(limit=10)})
        sys.exit(1)
