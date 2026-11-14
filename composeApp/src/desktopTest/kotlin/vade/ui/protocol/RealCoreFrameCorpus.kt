package vade.ui.protocol

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
 * Coverage (honest): 22 RPC replies and 14 event frames. Event topics present:
 * queue.updated, session.attached, session.updated, turn.completed, turn.delta, turn.queued, turn.started.
 * Topics the one-turn capture cannot produce (turn.tool_call, turn.tool_result,
 * turn.approval_*, turn.input_*, task.*, subagent.*, module.status, session.closed,
 * turn.superseded, message.edited, usage.budget, turn.skill_catalog, turn.tool_discovered)
 * remain covered by the hand-written contract literals in VadeContractFramesTest.
 */
internal data class RpcFrame(val method: String, val json: String)

internal data class EventFrame(val type: String, val seq: Long, val json: String)

internal val REAL_CORE_RPC_FRAMES: List<RpcFrame> = listOf(
        RpcFrame("ui/attach", """{"session_id":"85a5334e86e44eeb84495b97d21515f2","from_seq":0,"snapshot":{"from_seq":0,"session":{"id":"85a5334e86e44eeb84495b97d21515f2","created_at":1794671726987,"title":null,"state":"open","seq_counter":0,"model":{"model_id":null,"source":"none"}},"messages":[],"turns":[],"queue":{"running":null,"pending":[]},"pending_approvals":[],"pending_inputs":[],"tasks":[],"tasks_truncated":false}}"""),
        RpcFrame("ui/submit", """{"turn_id":"cac1a2991dd9448db4e028d9f80d1234","queue_pos":1}"""),
        RpcFrame("session/usage.get", """{"session_id":"85a5334e86e44eeb84495b97d21515f2","chat":{"prompt_tokens":9,"completion_tokens":12,"total_tokens":21,"turns":1,"partial_turns":0},"aux":{"total_tokens":0,"calls":0},"total_tokens":21,"by_model":[{"model_id":null,"total_tokens":21,"turns":1}],"day":{"day":"2026-11-14","core_total_tokens":21,"core_limit":0,"session_tokens":21,"state":"off"},"budget":{"scope":"session","limit":0,"used":21,"ratio":null,"state":"off","enforced":false}}"""),
        RpcFrame("session/usage.get", """{"session_id":"85a5334e86e44eeb84495b97d21515f2","chat":{"prompt_tokens":9,"completion_tokens":12,"total_tokens":21,"turns":1,"partial_turns":0},"aux":{"total_tokens":0,"calls":0},"total_tokens":21,"by_model":[{"model_id":null,"total_tokens":21,"turns":1}],"day":{"day":"2026-11-14","core_total_tokens":21,"core_limit":0,"session_tokens":21,"state":"off"},"budget":{"scope":"session","limit":0,"used":21,"ratio":null,"state":"off","enforced":false}}"""),
        RpcFrame("session/list", """{"sessions":[{"session_id":"85a5334e86e44eeb84495b97d21515f2","created_at":1794671726987,"updated_at":1794671727628,"title":"cap title","icon":null,"color":null,"group_id":null,"pinned_at":null,"archived_at":null,"unread":0,"state":"open"}],"next_cursor":null}"""),
        RpcFrame("session/list", """{"sessions":[{"session_id":"85a5334e86e44eeb84495b97d21515f2","created_at":1794671726987,"updated_at":1794671727628,"title":null,"icon":null,"color":null,"group_id":null,"pinned_at":null,"archived_at":null,"unread":0,"state":"open"}],"next_cursor":null}"""),
        RpcFrame("session/group.list", """{"groups":[{"group_id":"fc72b872c8c9411cbc9e6b17e43b077d","name":"cap-group","icon":null,"color":"#336699","sort_order":0,"created_at":1794671742936,"session_count":0}]}"""),
        RpcFrame("session/group.create", """{"group":{"group_id":"fc72b872c8c9411cbc9e6b17e43b077d","name":"cap-group","icon":null,"color":"#336699","sort_order":0,"created_at":1794671742936}}"""),
        RpcFrame("session/group.update", """{"group":{"group_id":"fc72b872c8c9411cbc9e6b17e43b077d","name":"cap-group-2","icon":"star","color":null,"sort_order":0,"created_at":1794671742936}}"""),
        RpcFrame("session/group.assign", """{"ok":true,"group_id":"fc72b872c8c9411cbc9e6b17e43b077d"}"""),
        RpcFrame("session/group.delete", """{"ok":true,"unassigned":1}"""),
        RpcFrame("session/rename", """{"ok":true,"session":{"session_id":"85a5334e86e44eeb84495b97d21515f2","created_at":1794671726987,"updated_at":1794671727628,"title":"cap title","icon":null,"color":null,"group_id":null,"pinned_at":null,"archived_at":null,"unread":0,"state":"open"}}"""),
        RpcFrame("session/pin", """{"ok":true,"pinned_at":1794671743800}"""),
        RpcFrame("session/pin", """{"ok":true,"pinned_at":null}"""),
        RpcFrame("session/archive", """{"ok":true,"archived_at":1794671744672}"""),
        RpcFrame("session/archive", """{"ok":true,"archived_at":null}"""),
        RpcFrame("session/search", """{"results":[{"session_id":"85a5334e86e44eeb84495b97d21515f2","title":"cap title","updated_at":1794671727628,"matched_in":"body","message_id":"df3e64aaa17f4cb5940dd2d2eab4956f","snippet":"capture hello","snippet_offset":8,"archived":false}],"total":1}"""),
        RpcFrame("model/list", """{"revision":1,"not_modified":false,"models":[],"default":{"model_id":null,"source":"none","resolved":false}}"""),
        RpcFrame("session/model.get", """{"model_id":null,"source":"none","resolved":false,"effective":null,"global_default":null,"reason":"no_model_configured"}"""),
        RpcFrame("session/model.set", """{"model_id":null,"source":"none","effective_at":"next_turn"}"""),
        RpcFrame("task/list", """{"tasks":[],"next_cursor":null,"running_count":0,"limits":{"max_concurrent":8,"max_depth":4}}"""),
        RpcFrame("subagent/list", """{"runs":[],"next_cursor":null}"""),
)

internal val REAL_CORE_EVENT_FRAMES: List<EventFrame> = listOf(
        EventFrame("session.attached", 1L, """{"session_id":"85a5334e86e44eeb84495b97d21515f2","from_seq":0,"state":"open","snapshot":true}"""),
        EventFrame("turn.queued", 2L, """{"turn_id":"cac1a2991dd9448db4e028d9f80d1234","state":"queued","client_msg_id":"cap-db0c816310aa4f1997e2a1e645d989ba","root_turn_id":"cac1a2991dd9448db4e028d9f80d1234","version":1,"parent_turn_id":null,"user_message_id":"df3e64aaa17f4cb5940dd2d2eab4956f","reason":"submit"}"""),
        EventFrame("queue.updated", 3L, """{"running":null,"running_state":null,"pending":[{"turn_id":"cac1a2991dd9448db4e028d9f80d1234","state":"queued","client_msg_id":"cap-db0c816310aa4f1997e2a1e645d989ba","enqueued_at":1794671727626}],"session_id":"85a5334e86e44eeb84495b97d21515f2"}"""),
        EventFrame("turn.started", 4L, """{"turn_id":"cac1a2991dd9448db4e028d9f80d1234","state":"running"}"""),
        EventFrame("queue.updated", 5L, """{"running":"cac1a2991dd9448db4e028d9f80d1234","running_state":"running","pending":[],"session_id":"85a5334e86e44eeb84495b97d21515f2"}"""),
        EventFrame("turn.delta", 6L, """{"turn_id":"cac1a2991dd9448db4e028d9f80d1234","text":"capture echo: scripted reply.","part_index":0,"kind":"text"}"""),
        EventFrame("turn.delta", 7L, """{"turn_id":"cac1a2991dd9448db4e028d9f80d1234","text":" -- done.","part_index":0,"kind":"text"}"""),
        EventFrame("turn.completed", 8L, """{"turn_id":"cac1a2991dd9448db4e028d9f80d1234","usage":{"prompt_tokens":9,"completion_tokens":12,"total_tokens":21,"model_id":null,"provider":null,"source":"provider","steps":1,"cached_tokens":0,"reasoning_tokens":0},"state":"completed","stopped_reason":"stop","skills":[]}"""),
        EventFrame("queue.updated", 9L, """{"running":null,"running_state":null,"pending":[],"session_id":"85a5334e86e44eeb84495b97d21515f2"}"""),
        EventFrame("session.updated", 10L, """{"session_id":"85a5334e86e44eeb84495b97d21515f2","created_at":1794671726987,"updated_at":1794671727628,"title":"cap title","icon":null,"color":null,"group_id":null,"pinned_at":null,"archived_at":null,"unread":0,"state":"open","by":"ui-be3ea5c58b60f733"}"""),
        EventFrame("session.updated", 11L, """{"session_id":"85a5334e86e44eeb84495b97d21515f2","created_at":1794671726987,"updated_at":1794671727628,"title":"cap title","icon":null,"color":null,"group_id":null,"pinned_at":1794671743800,"archived_at":null,"unread":0,"state":"open","by":"ui-be3ea5c58b60f733"}"""),
        EventFrame("session.updated", 12L, """{"session_id":"85a5334e86e44eeb84495b97d21515f2","created_at":1794671726987,"updated_at":1794671727628,"title":"cap title","icon":null,"color":null,"group_id":null,"pinned_at":null,"archived_at":null,"unread":0,"state":"open","by":"ui-be3ea5c58b60f733"}"""),
        EventFrame("session.updated", 13L, """{"session_id":"85a5334e86e44eeb84495b97d21515f2","created_at":1794671726987,"updated_at":1794671727628,"title":"cap title","icon":null,"color":null,"group_id":null,"pinned_at":null,"archived_at":1794671744672,"unread":0,"state":"open","by":"ui-be3ea5c58b60f733"}"""),
        EventFrame("session.updated", 14L, """{"session_id":"85a5334e86e44eeb84495b97d21515f2","created_at":1794671726987,"updated_at":1794671727628,"title":"cap title","icon":null,"color":null,"group_id":null,"pinned_at":null,"archived_at":null,"unread":0,"state":"open","by":"ui-be3ea5c58b60f733"}"""),
)
