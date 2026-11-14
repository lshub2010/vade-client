"""Capture REAL core wire frames (RPC results + events) into a JSON corpus.

Boots the real Python core in-process (same construction as core/tests/transport/
test_live_server.py), injects the scripted MockLLMProvider so one real turn runs,
mints a browser pair code, then connects over a real WS with the session cookie and
records the raw JSON-RPC reply text verbatim.

The connection is re-opened whenever core closes it, so one hostile/mistyped call
cannot cost the rest of the corpus.

  D:/HermesWorkspace/Vade/Vade/.venv/Scripts/python.exe capture_real_core.py capture.json
"""
from __future__ import annotations

import asyncio
import json
import sys
import tempfile
import threading
import time
import uuid
from pathlib import Path

VADE = Path(r"D:/HermesWorkspace/Vade/Vade")
sys.path.insert(0, str(VADE / "protocol" / "core" / "python"))
sys.path.insert(0, str(VADE / "core"))

import httpx  # noqa: E402
import uvicorn  # noqa: E402

from vade import __main__ as vade_main  # noqa: E402
from vade.app import App, install_components, make_rpc_handler  # noqa: E402
from vade.config import load_config  # noqa: E402
from vade.identity.pairing import Purpose  # noqa: E402
from vade.transport.server import CoreApp  # noqa: E402

ECHO_1 = "capture echo: scripted reply."
ECHO_2 = " -- done."


def boot():
    data_dir = tempfile.mkdtemp(prefix="vade-cap-")
    config = load_config(cli={
        "core.data_dir": data_dir,
        "core.listen": "127.0.0.1:0",
        "dev.allow_all": True,
    })

    # Scripted provider so a real turn completes deterministically. Patched BEFORE
    # install_components: the session component captures `_build_llm` at wiring time.
    import vade.session as session_pkg
    from vade.llm.base import Delta
    from vade.llm.mock import MockLLMProvider

    def _build_llm(cfg, **kwargs):
        return MockLLMProvider([[
            Delta(text=ECHO_1),
            Delta(text=ECHO_2, usage={"prompt_tokens": 9, "completion_tokens": 12,
                                      "total_tokens": 21}, finish_reason="stop"),
        ]])

    session_pkg._build_llm = _build_llm

    app = App(config)
    asyncio.run(app.startup())
    installed = install_components(app, strict=False)
    print("installed:", sorted(installed), file=sys.stderr)

    core_app = CoreApp(
        config=config, identity=app.identity, registry=app.registry, pairing=app.pairing,
        whitelist=app.whitelist, make_crypto=vade_main.build_module_crypto(app),
        on_rpc=make_rpc_handler(app), policy_mode="allow_all",
    )
    server = uvicorn.Server(uvicorn.Config(core_app, host="127.0.0.1", port=0,
                                           log_level="error", lifespan="off",
                                           proxy_headers=False))
    t = threading.Thread(target=server.run, daemon=True)
    t.start()
    deadline = time.time() + 20
    while not server.started:
        if time.time() > deadline:
            raise RuntimeError("uvicorn did not start")
        time.sleep(0.02)
    port = server.servers[0].sockets[0].getsockname()[1]
    return app, server, t, port, data_dir


class Conn:
    def __init__(self, port, cookie):
        self.url = f"ws://127.0.0.1:{port}/ws"
        self.cookie = cookie
        self.ws = None
        self.rid = 0
        self.events = []

    async def ensure(self):
        if self.ws is not None:
            return True
        import websockets
        try:
            self.ws = await websockets.connect(self.url,
                                               additional_headers={"Cookie": self.cookie},
                                               max_size=16 * 1024 * 1024)
            return True
        except Exception as e:  # noqa: BLE001
            print("connect failed:", e, file=sys.stderr)
            self.ws = None
            return False

    async def drop(self):
        if self.ws is not None:
            try:
                await self.ws.close()
            except Exception:  # noqa: BLE001
                pass
        self.ws = None

    async def _pump(self, want_id, timeout):
        end = time.time() + timeout
        while time.time() < end:
            try:
                raw = await asyncio.wait_for(self.ws.recv(), timeout=end - time.time())
            except Exception as e:  # noqa: BLE001
                await self.drop()
                return {"exception": repr(e)}
            msg = json.loads(raw)
            if want_id is not None and msg.get("id") == want_id:
                return msg
            if "method" in msg:
                self.events.append(msg)
        if want_id is not None:
            return {"exception": "timeout"}
        return None

    async def call(self, method, params=None, timeout=20.0):
        if not await self.ensure():
            return {"exception": "no connection"}
        self.rid += 1
        req = {"jsonrpc": "2.0", "id": self.rid, "method": method}
        if params is not None:
            req["params"] = params
        try:
            await self.ws.send(json.dumps(req, ensure_ascii=False))
        except Exception as e:  # noqa: BLE001
            await self.drop()
            return {"exception": repr(e)}
        return await self._pump(self.rid, timeout)

    async def drain(self, seconds):
        if not await self.ensure():
            return
        end = time.time() + seconds
        while time.time() < end:
            try:
                raw = await asyncio.wait_for(self.ws.recv(), timeout=max(0.01, end - time.time()))
            except Exception:  # noqa: BLE001
                return
            msg = json.loads(raw)
            if "method" in msg:
                self.events.append(msg)


async def drive(app, port, out):
    base = f"http://127.0.0.1:{port}"
    code = app.pairing.create(Purpose.BROWSER, ttl=600)
    r = httpx.post(f"{base}/ui/session", json={"pair_code": code, "client_name": "cap"}, timeout=10)
    r.raise_for_status()
    jar = {}
    for c in r.headers.get_list("set-cookie"):
        k, _, v = c.split(";")[0].partition("=")
        jar[k.strip()] = v
    cookie = "; ".join(f"{k}={v}" for k, v in jar.items())
    out["pair_session_response"] = r.json()

    conn = Conn(port, cookie)
    results = {}

    async def cap(name, method, params=None, timeout=20.0, drain=0.05):
        results[name] = await conn.call(method, params, timeout=timeout)
        if drain:
            await conn.drain(drain)
        got = results[name]
        mark = "OK " if "result" in got else ("ERR" if "error" in got else "???")
        print(f"  {mark} {name}", file=sys.stderr)

    try:
        await cap("ui/attach", "ui/attach", {"session_id": None, "last_seq": None}, drain=0.6)
        sid = ((results["ui/attach"].get("result") or {}).get("session_id"))

        # ---- the real turn FIRST, on the clean attendee connection: this is the
        #      only way to capture the full turn.* event stream (a reconnect below
        #      re-subscribes nothing, so events after it would be lost).
        await cap("ui/submit", "ui/submit", {
            "session_id": sid, "client_msg_id": f"cap-{uuid.uuid4().hex}",
            "content": [{"type": "text", "text": "capture hello"}],
        }, timeout=30.0, drain=15.0)
        await cap("session/usage.get#2", "session/usage.get", {"session_id": sid})
        await cap("session/list#2", "session/list", {})
        await cap("session/search#2", "session/search", {"query": "capture", "limit": 5})

        # ---- metadata writes + version-chain writes, still on the attached
        #      connection: rename/pin/archive emit session.updated, regenerate
        #      emits turn.superseded, edit_resubmit emits message.edited.
        await cap("session/group.create", "session/group.create",
                  {"name": "cap-group", "icon": None, "color": "#336699"}, drain=0.4)
        await cap("session/rename", "session/rename", {"session_id": sid, "title": "cap title"}, drain=0.4)
        await cap("session/pin", "session/pin", {"session_id": sid, "pinned": True}, drain=0.4)
        await cap("session/pin#off", "session/pin", {"session_id": sid, "pinned": False}, drain=0.4)
        await cap("session/archive", "session/archive", {"session_id": sid, "archived": True}, drain=0.4)
        await cap("session/archive#off", "session/archive", {"session_id": sid, "archived": False}, drain=0.4)
        turn_id = ((results["ui/submit"].get("result") or {}).get("turn_id"))
        msg_id = ((results["ui/submit"].get("result") or {}).get("turn_id"))
        if turn_id:
            await cap("turn/regenerate", "turn/regenerate",
                      {"session_id": sid, "turn_id": turn_id}, timeout=30.0, drain=12.0)
        await cap("message/edit_resubmit", "message/edit_resubmit",
                  {"session_id": sid, "message_id": msg_id or "nope",
                   "content": [{"type": "text", "text": "edited capture hello"}]},
                  timeout=30.0, drain=12.0)

        for name, method, params in [
            ("model/list", "model/list", {}),
            ("session/model.get", "session/model.get", {"session_id": sid}),
            ("session/usage.get", "session/usage.get", {"session_id": sid}),
            ("session/list", "session/list", {}),
            ("session/group.list", "session/group.list", {}),
            ("session/search", "session/search", {"query": "hello", "limit": 10}),
            ("task/list", "task/list", {}),
            ("subagent/list", "subagent/list", {"session_id": sid}),
            ("ui/state.get", "ui/state.get", {"session_id": sid, "keys": []}),
            ("ui/page.get", "ui/page.get", {"session_id": sid, "page_id": "nope"}),
            ("task/log_tail", "task/log_tail", {"task_id": "nope"}),
            ("task/cancel", "task/cancel", {"task_id": "nope"}),
            ("subagent/get", "subagent/get", {"session_id": sid, "run_id": "nope"}),
            ("approval/decide", "approval/decide",
             {"request_id": "nope", "decision": "deny", "scope": "once"}),
            ("turn/input", "turn/input", {"session_id": sid, "input_id": "nope", "value": "x"}),
            ("turn/input.cancel", "turn/input.cancel", {"session_id": sid, "input_id": "nope"}),
            ("ui/tool.call", "ui/tool.call", {"session_id": sid, "tool": "nope", "args": {}}),
            ("interrupt", "ui/interrupt", {"session_id": sid, "turn_id": "nope"}),
        ]:
            await cap(name, method, params)

        gid = ((results.get("session/group.create", {}).get("result") or {}).get("group") or {}).get("group_id")
        if gid:
            await cap("session/group.update", "session/group.update",
                      {"group_id": gid, "name": "cap-group-2", "icon": "star", "color": None}, drain=0.3)
            await cap("session/group.assign", "session/group.assign",
                      {"session_id": sid, "group_id": gid}, drain=0.3)

        await cap("session/group.delete", "session/group.delete", {"group_id": gid or "nope"}, drain=0.3)
        await cap("session/model.set", "session/model.set", {"session_id": sid, "model_id": None}, drain=0.3)
        await cap("task/list#2", "task/list", {})
        await cap("ui/detach", "ui/detach", {"session_id": sid}, drain=0.3)
    finally:
        await conn.drop()

    out["rpc"] = results
    out["events"] = conn.events


def main():
    out_path = Path(sys.argv[1] if len(sys.argv) > 1 else "capture.json")
    app, server, t, port, data_dir = boot()
    out = {"data_dir": data_dir}
    try:
        import vade
        out["core_version"] = getattr(vade, "__version__", "?")
    except Exception:  # noqa: BLE001
        pass
    try:
        asyncio.run(drive(app, port, out))
    finally:
        server.should_exit = True
        t.join(timeout=10)

        async def shut():
            s = getattr(app, "session", None)
            if s is not None:
                await s.close()
            await app.shutdown()
        try:
            asyncio.run(shut())
        except Exception as e:  # noqa: BLE001
            print("teardown:", e, file=sys.stderr)
    out_path.write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    print("wrote", out_path, file=sys.stderr)
    print("rpc:", len(out["rpc"]), "events:", len(out["events"]), file=sys.stderr)


if __name__ == "__main__":
    main()
