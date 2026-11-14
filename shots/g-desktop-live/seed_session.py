#!/usr/bin/env python3
"""g-desktop-live · seed a REAL core session with a unique title (evidence fixture).

Not product code: this is the acceptance driver for `shots/g-desktop-live/RESULT.md`.
It speaks the documented browser-pairing path only:

  1. POST /ui/session  {pair_code, client_name}   -> Set-Cookie __Host-ac_ui
  2. WS /ws with that cookie
  3. ui/attach {session_id: null}                 -> core mints a session id
  4. session/rename {session_id, title}           -> the unique title
  5. ui/submit  {session_id, content: [text], client_msg_id}
  6. session/list                                 -> print the rows core now serves

Usage:
  python seed_session.py --port 18755 --pair-code "<code>" --title "E2E-XXXXXXXX"
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import urllib.error
import urllib.request

import websockets


def post_pair(host: str, port: int, pair_code: str, client_name: str) -> tuple[str, dict]:
    body = json.dumps({"pair_code": pair_code, "client_name": client_name}).encode("utf-8")
    req = urllib.request.Request(
        f"http://{host}:{port}/ui/session",
        data=body,
        headers={"content-type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            status = resp.status
            raw_cookies = resp.headers.get_all("Set-Cookie") or []
            payload = json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as exc:
        print(f"POST /ui/session -> HTTP {exc.code}: {exc.read().decode('utf-8', 'replace')}")
        raise SystemExit(3)
    print(f"POST /ui/session -> HTTP {status}")
    print("Set-Cookie names: " + ", ".join(c.split(";", 1)[0].split("=", 1)[0] for c in raw_cookies))
    print("body keys: " + ", ".join(sorted(payload)))
    cookie = ""
    for c in raw_cookies:
        pair = c.split(";", 1)[0]
        if pair.startswith("__Host-ac_ui="):
            cookie = pair
    if not cookie:
        print("no __Host-ac_ui cookie in response")
        raise SystemExit(4)
    return cookie, payload


async def rpc(ws, request_id: int, method: str, params: dict) -> dict:
    await ws.send(json.dumps({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}))
    while True:
        raw = await ws.recv()
        msg = json.loads(raw if isinstance(raw, str) else raw.decode("utf-8"))
        if msg.get("id") == request_id:
            return msg


async def dump_attach(args) -> int:
    """Attach to an existing session and print the snapshot the CORE serves."""
    cookie, _ = post_pair(args.host, args.port, args.pair_code, "g-desktop-live-dumper")
    url = f"ws://{args.host}:{args.port}/ws"
    async with websockets.connect(url, additional_headers={"Cookie": cookie}, max_size=None) as ws:
        att = await rpc(ws, 1, "ui/attach", {"session_id": args.attach, "last_seq": None})
        if "error" in att:
            print("ui/attach error: " + json.dumps(att["error"], ensure_ascii=False))
            return 5
        result = att["result"]
        print(f"ui/attach({args.attach}) -> from_seq={result['from_seq']}")
        msgs = result["snapshot"].get("messages") or []
        print(f"snapshot.messages count = {len(msgs)}")
        for m in msgs:
            parts = m.get("content") or []
            text = " | ".join(
                p.get("text", "") if isinstance(p, dict) else str(p) for p in parts
            )
            print(f"  msg id={m.get('id')} role={m.get('role')} text={text[:200]!r}")
        print("snapshot.session = " + json.dumps(result["snapshot"].get("session"), ensure_ascii=False)[:400])
    return 0


async def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=18755)
    ap.add_argument("--pair-code", required=True)
    ap.add_argument("--title", default="")
    ap.add_argument("--no-submit", action="store_true")
    # Evidence mode: attach to an existing session and dump what the CORE returns.
    ap.add_argument("--attach", default=None, help="existing session id: attach and dump messages")
    args = ap.parse_args()

    if args.attach:
        return await dump_attach(args)

    cookie, payload = post_pair(args.host, args.port, args.pair_code, "g-desktop-live-seeder")

    url = f"ws://{args.host}:{args.port}/ws"
    async with websockets.connect(url, additional_headers={"Cookie": cookie}, max_size=None) as ws:
        print(f"WS {url} -> open")

        attached = await rpc(ws, 1, "ui/attach", {"session_id": None, "last_seq": None})
        if "error" in attached:
            print("ui/attach error: " + json.dumps(attached["error"], ensure_ascii=False))
            return 5
        session_id = attached["result"]["session_id"]
        print(f"ui/attach -> session_id={session_id} from_seq={attached['result']['from_seq']}")
        print(f"  snapshot.messages count = {len(attached['result']['snapshot'].get('messages') or [])}")

        renamed = await rpc(ws, 2, "session/rename", {"session_id": session_id, "title": args.title})
        print("session/rename -> " + json.dumps(renamed.get("result", renamed.get("error")), ensure_ascii=False))

        if not args.no_submit:
            submitted = await rpc(
                ws,
                3,
                "ui/submit",
                {
                    "session_id": session_id,
                    "content": [{"type": "text", "text": "E2E 消息：这条消息由 g-desktop-live 验收脚本写入。"}],
                    "client_msg_id": "e2e-" + session_id[:8],
                },
            )
            print("ui/submit -> " + json.dumps(submitted.get("result", submitted.get("error")), ensure_ascii=False))
            # Drain whatever the core pushes for a moment: turn events + the notice text.
            seen = 0
            try:
                while seen < 25:
                    raw = await asyncio.wait_for(ws.recv(), timeout=1.5)
                    msg = json.loads(raw if isinstance(raw, str) else raw.decode("utf-8"))
                    if msg.get("method") == "event":
                        ev = msg.get("params", {})
                        seen += 1
                        print(f"  event[{seen}] " + json.dumps(ev, ensure_ascii=False)[:240])
            except asyncio.TimeoutError:
                print(f"  (no more events after {seen})")

        listed = await rpc(ws, 9, "session/list", {"limit": 20})
        rows = listed.get("result", {}).get("sessions", [])
        print(f"session/list -> {len(rows)} row(s)")
        for row in rows:
            print(
                "  row: id={id} title={title!r} updated_at={updated_at}".format(
                    id=row.get("id"), title=row.get("title"), updated_at=row.get("updated_at")
                )
            )
    print("DONE")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
