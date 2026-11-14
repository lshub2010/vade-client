import json

ROOT = r"D:/HermesWorkspace/Vade-client-wt/g-wire-strict"
CAP = ROOT + "/shots/g-wire-strict/capture.json"
OUT = ROOT + "/composeApp/src/desktopTest/kotlin/vade/ui/protocol/RealCoreFrameCorpus.kt"

d = json.load(open(CAP, encoding="utf-8"))

def compact(o):
    return json.dumps(o, ensure_ascii=False, separators=(",", ":"))

def kstr(s):
    assert '"""' not in s and "\n" not in s
    return '"""' + s.replace("$", "${'$'}") + '"""'

RPC_ORDER = [
    "ui/attach", "ui/submit",
    "session/usage.get", "session/usage.get#2",
    "session/list", "session/list#2",
    "session/group.list", "session/group.create", "session/group.update",
    "session/group.assign", "session/group.delete",
    "session/rename", "session/pin", "session/pin#off",
    "session/archive", "session/archive#off",
    "session/search", "model/list", "session/model.get", "session/model.set",
    "task/list", "subagent/list",
]
METHOD_OF = {
    "ui/attach": "ui/attach", "ui/submit": "ui/submit",
    "session/usage.get": "session/usage.get", "session/usage.get#2": "session/usage.get",
    "session/list": "session/list", "session/list#2": "session/list",
    "session/group.list": "session/group.list", "session/group.create": "session/group.create",
    "session/group.update": "session/group.update", "session/group.assign": "session/group.assign",
    "session/group.delete": "session/group.delete", "session/rename": "session/rename",
    "session/pin": "session/pin", "session/pin#off": "session/pin",
    "session/archive": "session/archive", "session/archive#off": "session/archive",
    "session/search": "session/search", "model/list": "model/list",
    "session/model.get": "session/model.get", "session/model.set": "session/model.set",
    "task/list": "task/list", "subagent/list": "subagent/list",
}

rpc_entries = []
for capname in RPC_ORDER:
    reply = d["rpc"].get(capname)
    if not reply or "result" not in reply:
        continue
    rpc_entries.append('        RpcFrame("%s", %s)' % (METHOD_OF[capname], kstr(compact(reply["result"]))))

ev_entries = []
for e in d["events"]:
    p = e["params"]
    ev_entries.append('        EventFrame("%s", %dL, %s)' % (p["type"], int(p["seq"]), kstr(compact(p["payload"]))))

topics = sorted({e["params"]["type"] for e in d["events"]})

src = '''package vade.ui.protocol

/**
 * Frames captured from a REAL python core — not hand-written contract literals.
 *
 * Provenance: `shots/g-wire-strict/capture2.py` boots `core/vade` in-process the same way
 * `core/tests/transport/test_live_server.py` does (uvicorn on an ephemeral port, a real
 * `POST /ui/session` pair-code cookie, a real WS to `/ws`), injects the scripted
 * `MockLLMProvider`, then records every `ui_to_core` reply and every `event` frame as raw
 * JSON. The strings below are those frames verbatim (only re-serialised compactly).
 *
 * WHY THIS FILE EXISTS: tightening a decoder from "wrong shape -> null" to
 * "wrong shape -> malformed" is only safe if the frames core ACTUALLY sends still decode.
 * This corpus is the evidence for that claim and
 * `VadeWireStrictTest.realCoreFramesStillDecode` is the assertion. Never edit a string here
 * to make a test pass — if a real frame stops decoding, the decoder became too strict and
 * the decoder is what has to change.
 *
 * Coverage (honest): %d RPC replies and %d event frames. Event topics present:
 * %s.
 * Topics the one-turn capture cannot produce (turn.tool_call, turn.tool_result,
 * turn.approval_*, turn.input_*, task.*, subagent.*, module.status, session.closed,
 * turn.superseded, message.edited, usage.budget, turn.skill_catalog, turn.tool_discovered)
 * remain covered by the hand-written contract literals in VadeContractFramesTest.
 */
internal data class RpcFrame(val method: String, val json: String)

internal data class EventFrame(val type: String, val seq: Long, val json: String)

internal val REAL_CORE_RPC_FRAMES: List<RpcFrame> = listOf(
%s,
)

internal val REAL_CORE_EVENT_FRAMES: List<EventFrame> = listOf(
%s,
)
''' % (len(rpc_entries), len(ev_entries), ", ".join(topics),
       ",\n".join(rpc_entries), ",\n".join(ev_entries))

open(OUT, "w", encoding="utf-8").write(src)
print("wrote", OUT, len(src), "bytes,", len(rpc_entries), "rpc frames,", len(ev_entries), "event frames")
