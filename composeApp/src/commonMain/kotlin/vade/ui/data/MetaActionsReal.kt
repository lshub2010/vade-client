package vade.ui.data

import vade.ui.meta.MetaGroup
import vade.ui.meta.SessionMetaActions
import vade.ui.protocol.GroupRow
import vade.ui.protocol.Optional
import vade.ui.protocol.SessionArchiveParams
import vade.ui.protocol.SessionDeleteParams
import vade.ui.protocol.SessionGroupAssignParams
import vade.ui.protocol.SessionGroupCreateParams
import vade.ui.protocol.SessionGroupDeleteParams
import vade.ui.protocol.SessionGroupListParams
import vade.ui.protocol.SessionGroupUpdateParams
import vade.ui.protocol.SessionPinParams
import vade.ui.protocol.SessionRenameParams
import vade.ui.protocol.VadeClient
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException

/**
 * The real [SessionMetaActions]: every one of the nine edit actions is one typed RPC
 * against [VadeClient] (RFC-0010 §5). This class is a pure adapter — it owns NO
 * connection and NO state; the caller injects the shared [VadeClient] (the same one
 * [RealSessionSource] drives) and passes the instance to `SessionMetaState`.
 *
 * ## Frame discipline (contract 1.5.0)
 * Required params are validated HERE first — a blank id/name never produces a frame
 * (the transport is untouched, see the zero-frame tests). Result required fields decode
 * through the protocol layer's `must*` accessors, and a `group` row missing the icon or
 * color that [MetaGroup] requires throws PROTOCOL_MISMATCH — no zero-value fallback.
 *
 * ## Errors
 * [VadeException]s propagate with their C-04 `code` intact; classify with
 * [VadeErrorMapping.categoryOf]. A wire result with `ok=false` is treated as a
 * protocol violation (errors travel as error frames, never as ok:false results).
 */
class MetaActionsReal(private val client: VadeClient) : SessionMetaActions {

    // --- session 类（会话侧的五个写方法） ---------------------------------------------

    override suspend fun renameSession(sessionId: String, title: String) {
        requireNonBlank(sessionId, "session_id")
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            throw invalidArgument("params.title is required and must be non-blank after trimming")
        }
        if (trimmed.length > 200) {
            throw invalidArgument("params.title must be <=200 chars after trimming, got ${trimmed.length}")
        }
        val result = client.sessionRename(SessionRenameParams(sessionId, title))
        requireOk(result.ok, "session/rename")
    }

    override suspend fun setSessionPinned(sessionId: String, pinned: Boolean) {
        requireNonBlank(sessionId, "session_id")
        val result = client.sessionPin(SessionPinParams(sessionId, pinned))
        requireOk(result.ok, "session/pin")
    }

    override suspend fun setSessionArchived(sessionId: String, archived: Boolean) {
        requireNonBlank(sessionId, "session_id")
        val result = client.sessionArchive(SessionArchiveParams(sessionId, archived))
        requireOk(result.ok, "session/archive")
    }

    override suspend fun deleteSession(sessionId: String) {
        requireNonBlank(sessionId, "session_id")
        val result = client.sessionDelete(SessionDeleteParams(sessionId))
        requireOk(result.ok, "session/delete")
    }

    override suspend fun assignSessionToGroup(sessionId: String, groupId: String?) {
        requireNonBlank(sessionId, "session_id")
        // Three states on the wire (session/group.assign): a value assigns, JSON null
        // moves the session out of any group — but a BLANK string is a caller bug, not
        // "ungrouped", and is refused before any frame is sent.
        val wire = when {
            groupId == null -> Optional.Null
            groupId.isBlank() -> throw invalidArgument(
                "params.group_id must be null (ungroup) or a non-blank id, got: \"$groupId\"",
            )
            else -> Optional.Value(groupId)
        }
        val result = client.sessionGroupAssign(SessionGroupAssignParams(sessionId, wire))
        requireOk(result.ok, "session/group.assign")
    }

    // --- session/group.* ----------------------------------------------------------

    override suspend fun listGroups(): List<MetaGroup> {
        val result = client.sessionGroupList(SessionGroupListParams())
        // `groups` is a required array: a missing one already fails in fromJson.
        return result.groups.map { it.group.toMetaGroup("session/group.list") }
    }

    override suspend fun createGroup(name: String, iconName: String, colorHex: String): MetaGroup {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            throw invalidArgument("params.name is required and must be non-blank after trimming")
        }
        if (trimmedName.length > 64) {
            throw invalidArgument("params.name must be <=64 chars after trimming, got ${trimmedName.length}")
        }
        requireNonBlank(iconName, "icon")
        requireColorHex(colorHex)
        val result = client.sessionGroupCreate(SessionGroupCreateParams(name = trimmedName, icon = iconName, color = colorHex))
        return result.group.toMetaGroup("session/group.create")
    }

    override suspend fun updateGroup(groupId: String, name: String?, iconName: String?, colorHex: String?) {
        requireNonBlank(groupId, "group_id")
        val trimmedName = name?.trim()
        if (trimmedName != null && trimmedName.isEmpty()) {
            throw invalidArgument("params.name cannot be blank (a name cannot be cleared)")
        }
        if (name == null && iconName == null && colorHex == null) {
            throw invalidArgument("session/group.update requires at least one of name/icon/color")
        }
        // The UI-facing signature is two-state (null = keep); the wire field is
        // three-state (absent = keep). Null therefore maps to ABSENT, never to a
        // clearing JSON null — clearing an icon is not expressible by this interface.
        val result = client.sessionGroupUpdate(
            SessionGroupUpdateParams(
                groupId = groupId,
                name = trimmedName,
                icon = iconName?.let { Optional.Value(it) } ?: Optional.Absent,
                color = colorHex?.let { Optional.Value(it) } ?: Optional.Absent,
            ),
        )
        result.group.toMetaGroup("session/group.update")
    }

    override suspend fun deleteGroup(groupId: String) {
        requireNonBlank(groupId, "group_id")
        // Server-side semantics (RFC-0010 §8.1): the folder goes away, member sessions
        // move back to ungrouped (counted in `unassigned`) — sessions are NEVER deleted.
        val result = client.sessionGroupDelete(SessionGroupDeleteParams(groupId))
        requireOk(result.ok, "session/group.delete")
    }

    // --- internals ----------------------------------------------------------------

    /** Contract group row → UI [MetaGroup]; [MetaGroup.iconName]/[colorHex] are REQUIRED. */
    private fun GroupRow.toMetaGroup(via: String): MetaGroup = MetaGroup(
        id = groupId,
        name = name,
        iconName = icon ?: throw VadeException(
            VadeErrorCodes.PROTOCOL_MISMATCH,
            "result of $via has group '$groupId' without required 'icon' (MetaGroup needs a whitelist name)",
        ),
        colorHex = color ?: throw VadeException(
            VadeErrorCodes.PROTOCOL_MISMATCH,
            "result of $via has group '$groupId' without required 'color' (#RRGGBB)",
        ),
    )

    private fun requireOk(ok: Boolean, method: String) {
        if (!ok) {
            throw VadeException(VadeErrorCodes.PROTOCOL_MISMATCH, "result of $method carried ok=false")
        }
    }

    private companion object {
        fun requireNonBlank(value: String, name: String): String {
            if (value.isBlank()) {
                throw invalidArgument("params.$name is required and must be non-blank")
            }
            return value
        }

        fun requireColorHex(colorHex: String) {
            if (!COLOR_PATTERN.matches(colorHex)) {
                throw invalidArgument("params.color must be #RRGGBB, got: \"$colorHex\"")
            }
        }

        fun invalidArgument(message: String): VadeException =
            VadeException(VadeErrorCodes.INVALID_ARGUMENT, message)

        /** `COLOR_PATTERN` on the core (MetaModels doc: `^#[0-9a-fA-F]{6}$`). */
        val COLOR_PATTERN = Regex("^#[0-9a-fA-F]{6}$")
    }
}
