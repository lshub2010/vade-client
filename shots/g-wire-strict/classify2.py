import json, re, collections

ROOT = r"D:/HermesWorkspace/Vade-client-wt/g-wire-strict"
S = ROOT + "/contracts/vade/schema/"
PROTO = ROOT + "/composeApp/src/commonMain/kotlin/vade/ui/protocol/"

rpc = json.load(open(S + "rpc.json", encoding="utf-8"))
ev = json.load(open(S + "events.json", encoding="utf-8"))

nodes = {}
def walk(node, path):
    if isinstance(node, dict):
        nodes.setdefault(path, {})
        for k, v in node.items():
            if isinstance(v, bool):
                nodes[path][k] = "True" if v else "False"
            elif isinstance(v, str):
                nodes[path][k] = v
            elif isinstance(v, list):
                nodes[path][k] = "<array>"
                for item in v:
                    walk(item, path + "." + k + "[]")
            elif isinstance(v, dict):
                nodes[path][k] = "<object>"
                walk(v, path + "." + k)
for m, body in rpc["directions"]["ui_to_core"].items():
    if "result" in body:
        walk(body["result"], "RPC:" + m)
for e, body in ev["events"].items():
    if isinstance(body, dict) and "payload" in body:
        walk(body["payload"], "EV:" + e)

# Contract shapes that rpc.json/events.json only REFERENCE by name, resolved from
# the RFC the reference points at (citation kept so the audit stays checkable).
VIRTUAL = {
    # RFC-0005 4.2 条目字段
    "RPC:model/list.models[]": {
        "id": "string", "provider": "string", "model": "string", "display_name": "string",
        "input_modalities": "<array|null>", "max_image_bytes": "int|null",
        "max_image_px": "int|null", "max_images_per_turn": "int|null",
        "context_window": "int|null", "supports_tools": "bool|null",
        "priority": "int", "available": "bool",
    },
    # RFC-0007 5.1 Task 记录 + log_available (RFC-0007 6.1)
    "RPC:task/list.tasks": {
        "task_id": "string", "kind": "subagent|job", "parent_id": "string|null",
        "depth": "int", "session_id": "string", "turn_id": "string|null",
        "child_session_id": "string|null", "child_turn_id": "string|null",
        "name": "string", "state": "task.state", "action": "string", "pct": "int|null",
        "started_at": "int", "ended_at": "int|null", "elapsed_ms": "int",
        "summary": "string", "retryable": "bool", "log_available": "bool",
    },
    # RFC-0005 5 (snapshot 注记) + RFC-0007 6.1 (tasks / tasks_truncated)
    "RPC:ui/attach.snapshot": {
        "from_seq": "int", "session": "<object>", "messages": "<array>",
        "turns": "<array>", "queue": "<object>", "pending_approvals": "<array>",
        "pending_inputs": "<array>", "tasks": "<array>", "tasks_truncated": "bool",
    },
    "RPC:ui/attach.snapshot.session": {
        "id": "string", "created_at": "int", "title": "string|null", "state": "open|closed",
        "seq_counter": "int", "model": "<object>",
    },
    "RPC:ui/attach.snapshot.session.model": {"model_id": "string|null", "source": "session|global|priority|none"},
    "RPC:ui/attach.snapshot.queue": {
        "running": "string|null", "running_state": "turn.state|null", "pending": "<array>",
    },
    # RFC-0005 6.1 turn.completed.usage 的兼容扩
    "EV:turn.completed.usage": {
        "prompt_tokens": "int", "completion_tokens": "int", "total_tokens": "int",
        "model_id": "string|null", "provider": "string|null", "source": "provider|none",
        "steps": "int", "cached_tokens": "int", "reasoning_tokens": "int",
    },
    # RFC-0005 5.2 示例 + core ModelEntry.effective()
    "RPC:session/model.get.effective": {
        "id": "string", "provider": "string", "model": "string", "available": "bool",
    },
    "RPC:session/rename.session": {
        "session_id": "string", "created_at": "int", "updated_at": "int",
        "title": "string|null", "icon": "string|null", "color": "string|null",
        "group_id": "string|null", "pinned_at": "int|null", "archived_at": "int|null",
        "unread": "int", "state": "open|closed",
    },
    # RFC-0007 group_row = group.list items minus session_count
    "RPC:session/group.create.group": {
        "group_id": "string", "name": "string", "icon": "string|null",
        "color": "string|null", "sort_order": "int", "created_at": "int",
    },
    # rpc.json ui/submit.params.content.items.oneOf (inline JSON-Schema)
    "RPC:ui/submit.params.content.items": {
        "type": "string", "text": "string", "file_id": "string", "mime": "string",
        "name": "string", "size": "integer", "width": "integer", "height": "integer",
        "sha256": "string",
    },
    # rpc.json task/log_tail.lines: "array of {ts:int, stream:stdout|stderr|core, text:string}"
    "RPC:task/log_tail.lines[]": {"ts": "int", "stream": "stdout|stderr|core", "text": "string"},
    # rpc.json turn.input_required.options / .source
    "EV:turn.input_required.options[]": {"id": "string", "label": "string", "description": "string|null"},
    "EV:turn.input_required.source": {"tool": "string", "module_id": "string|null", "depth": "int"},
}
for k, v in VIRTUAL.items():
    nodes.setdefault(k, {}).update(v)

CLS2NODE = {
    "AttachResult": "RPC:ui/attach", "VadeSnapshot": "RPC:ui/attach.snapshot",
    "SnapshotSession": "RPC:ui/attach.snapshot.session",
    "SnapshotModel": "RPC:ui/attach.snapshot.session.model",
    "SnapshotQueue": "RPC:ui/attach.snapshot.queue",
    "SubmitResult": "RPC:ui/submit", "OkResult": "RPC:ui/detach",
    "ModelListResult": "RPC:model/list", "ModelEntry": "RPC:model/list.models[]",
    "DefaultModelInfo": "RPC:model/list.default",
    "SessionModelGetResult": "RPC:session/model.get",
    "EffectiveModel": "RPC:session/model.get.effective",
    "SessionModelSetResult": "RPC:session/model.set",
    "SessionUsageGetResult": "RPC:session/usage.get",
    "ChatUsage": "RPC:session/usage.get.chat", "AuxUsage": "RPC:session/usage.get.aux",
    "ModelUsage": "RPC:session/usage.get.by_model[]", "DayUsage": "RPC:session/usage.get.day",
    "BudgetUsage": "RPC:session/usage.get.budget", "VadeUsage": "EV:turn.completed.usage",
    "TurnInputResult": "RPC:turn/input", "TurnInputCancelResult": "RPC:turn/input.cancel",
    "TurnRegenerateResult": "RPC:turn/regenerate", "EditResubmitResult": "RPC:message/edit_resubmit",
    "TaskListResult": "RPC:task/list", "TaskView": "RPC:task/list.tasks",
    "TaskLimits": "RPC:task/list.limits", "TaskLogTailResult": "RPC:task/log_tail",
    "LogLine": "RPC:task/log_tail.lines[]", "TaskCancelResult": "RPC:task/cancel",
    "SubagentMessageResult": "RPC:subagent/message", "PageGetResult": "RPC:ui/page.get",
    "StateGetResult": "RPC:ui/state.get", "ToolCallResult": "RPC:ui/tool.call",
    "ApprovalOffer": "RPC:ui/tool.call.approval", "ToolCallError": "RPC:ui/tool.call.error",
    "SubagentListResult": "RPC:subagent/list", "SubagentRunView": "RPC:subagent/get",
    "SessionListResult": "RPC:session/list", "SessionRow": "RPC:session/list.sessions[]",
    "SessionRenameResult": "RPC:session/rename", "SessionPinResult": "RPC:session/pin",
    "SessionArchiveResult": "RPC:session/archive", "SessionDeleteResult": "RPC:session/delete",
    "GroupRow": "RPC:session/group.list.groups[]", "GroupListEntry": "RPC:session/group.list.groups[]",
    "SessionGroupListResult": "RPC:session/group.list",
    "SessionGroupWriteResult": "RPC:session/group.create",
    "SessionGroupDeleteResult": "RPC:session/group.delete",
    "SessionGroupAssignResult": "RPC:session/group.assign",
    "SessionSearchHit": "RPC:session/search.results[]", "SessionSearchResult": "RPC:session/search",
    "ContentPart": "RPC:ui/submit.params.content.items",
    "InputOption": "EV:turn.input_required.options[]", "InputSource": "EV:turn.input_required.source",
    "SessionAttachedPayload": "EV:session.attached", "TurnQueuedPayload": "EV:turn.queued",
    "TurnStartedPayload": "EV:turn.started", "TurnDeltaPayload": "EV:turn.delta",
    "TurnToolCallPayload": "EV:turn.tool_call", "TurnToolResultPayload": "EV:turn.tool_result",
    "TurnApprovalRequiredPayload": "EV:turn.approval_required",
    "TurnApprovalResolvedPayload": "EV:turn.approval_resolved",
    "TurnInputRequiredPayload": "EV:turn.input_required",
    "TurnInputResolvedPayload": "EV:turn.input_resolved",
    "TurnCompletedPayload": "EV:turn.completed", "TurnFailedPayload": "EV:turn.failed",
    "TurnInterruptedPayload": "EV:turn.interrupted", "QueuePending": "EV:queue.updated.pending[]",
    "QueueUpdatedPayload": "EV:queue.updated", "ModuleStatusPayload": "EV:module.status",
    "TaskStartedPayload": "EV:task.started", "TaskStatusPayload": "EV:task.status",
    "TaskProgressPayload": "EV:task.progress", "TaskFinishedPayload": "EV:task.finished",
    "ToolCallBrief": "EV:session.turn.tool_calls[]", "SessionTurnPayload": "EV:session.turn",
    "ClosedTurn": "EV:session.closed.turns[]", "TailTurn": "EV:session.closed.tail[]",
    "SessionClosedPayload": "EV:session.closed", "SkillProposedPayload": "EV:skill.proposed",
    "UsageBudgetPayload": "EV:usage.budget", "TurnSupersededPayload": "EV:turn.superseded",
    "MessageEditedPayload": "EV:message.edited", "SessionUpdatedPayload": "EV:session.updated",
    "SubagentStatusPayload": "EV:subagent.status", "SubagentResultPayload": "EV:subagent.result",
    "TurnSkillCatalogPayload": "EV:turn.skill_catalog",
    "TurnToolDiscoveredPayload": "EV:turn.tool_discovered",
}

SCALARS = {"string", "bool", "int", "number"}

def canonical(t):
    """contract type phrase -> JSON scalar kind, or None when the contract is not fixed."""
    if t is None:
        return None
    s = t.strip()
    if s in ("True", "False"):
        return "bool"
    if s.startswith("<"):
        return None
    # keep only the leading clause: cut at ", " / " (" / ";" / " — "
    head = re.split(r",| \(|;| — | \| ", s)[0].strip()
    if head.endswith("|null"):
        head = head[:-5].strip()
    # a union of two identifiers (string[] / bool) is NOT a fixed scalar
    if "|" in head and not re.match(r"^[a-z_]+(\|[a-z_]+)+$", head):
        return None
    if head in ("string", "boolean"):
        return "string" if head == "string" else "bool"
    if head == "integer":
        return "int"
    if head == "int":
        return "int"
    if head == "number":
        return "number"
    if head == "bool":
        return "bool"
    if head.startswith("string"):
        return "string"
    if head.startswith("int "):
        return "int"
    if head.startswith("number "):
        return "number"
    if re.match(r"^[a-z_]+(\|[a-z_]+)+$", head):
        return "string"
    if re.match(r"^[a-z_][a-z_0-9]*(\.[a-z_0-9]+)*$", head) and head not in ("object", "array", "any", "null"):
        return "string"
    if re.match(r"^[a-z_][a-z_0-9]*$", head) and head not in ("object", "array", "any", "null"):
        return "string"
    if head.startswith("sha256") or head.startswith("msg:") or head.startswith("C-04") or head.startswith("bat_"):
        return "string"
    return None

HELPERS = ["mustStrOrNull", "mustLngOrNull", "mustStr", "mustLng", "mustInt", "mustBool",
           "mustObj", "mustArr", "mustKey", "strStrict", "strOrNull", "str", "lng", "int",
           "bool", "dbl", "obj", "arr", "el"]
HELPERS.sort(key=len, reverse=True)
pat = re.compile(r"\.(" + "|".join(HELPERS) + r")\(")
declpat = re.compile(r"^(?:(?:private|internal|public|sealed|abstract|open|data|value|inline) )*(?:class|object|interface)\s+([A-Za-z0-9_]+)")

rows = []
for fn in ["VadeModels.kt", "VadeEvents.kt", "VadeContent.kt"]:
    lines = open(PROTO + fn, encoding="utf-8").read().split("\n")
    cur = "-"
    for i, line in enumerate(lines, 1):
        d = declpat.match(line)
        if d:
            cur = d.group(1)
        for mm in pat.finditer(line):
            helper = mm.group(1)
            rest = line[mm.end():]
            argm = re.match(r'\s*"([^"]*)"', rest)
            if not argm:
                continue
            key = argm.group(1)
            node = CLS2NODE.get(cur)
            declared = nodes.get(node, {}).get(key) if node else "<MISSING>"
            rows.append({"file": fn, "cls": cur, "node": node, "key": key, "helper": helper,
                         "declared": declared, "canon": canonical(declared) if declared != "<MISSING>" else None,
                         "line": i, "src": line.strip()})

print("total keyed decode sites:", len(rows))
missing = sorted({(r["cls"], r["key"]) for r in rows if r["declared"] == "<MISSING>"})
print("keys the contract node does not declare:", missing)
print("classes with no node:", sorted({r["cls"] for r in rows if not r["node"]}))

def verdict(r):
    h, c = r["helper"], r["canon"]
    if h == "str" and c == "string":
        return "STRICT:strStrict"
    if h == "strOrNull" and c == "string":
        return "STRICT:strStrict"
    if h == "int" and c == "int":
        return "STRICT:intStrict"
    if h == "lng" and c == "int":
        return "STRICT:lngStrict"
    if h == "bool" and c == "bool":
        return "STRICT:boolStrict"
    if h == "dbl" and c == "number":
        return "STRICT:numStrict"
    return "LEAVE"

for r in rows:
    r["verdict"] = verdict(r)

print("\nVERDICTS:", dict(collections.Counter(r["verdict"] for r in rows)))

print("\n=== LEAVE, grouped ===")
g = collections.defaultdict(list)
for r in rows:
    if r["verdict"] == "LEAVE":
        reason = ("already-throws" if r["helper"].startswith("must") else
                  "container" if r["helper"] in ("obj", "arr", "el") else
                  "union/undefined" if r["canon"] is None else
                  "helper/type mismatch")
        g[(reason, r["helper"], r["declared"])].append(f'{r["cls"]}.{r["key"]}')
for (reason, h, d), lst in sorted(g.items(), key=lambda x: (x[0][0], -len(x[1]))):
    print(f"  [{reason}] .{h}( declared={d!r} n={len(lst)}")
    print(f"      {lst}")

json.dump(rows, open(ROOT + "/shots/g-wire-strict/verdicts.json", "w", encoding="utf-8"),
          ensure_ascii=False, indent=1)
