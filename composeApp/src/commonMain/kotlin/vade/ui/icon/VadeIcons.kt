package vade.ui.icon

import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.tabler.Tabler
import com.composables.icons.tabler.filled.PlayerStop
import com.composables.icons.tabler.outline.Archive
import com.composables.icons.tabler.outline.ArchiveOff
import com.composables.icons.tabler.outline.Book
import com.composables.icons.tabler.outline.Bookmark
import com.composables.icons.tabler.outline.Brain
import com.composables.icons.tabler.outline.Briefcase
import com.composables.icons.tabler.outline.Bulb
import com.composables.icons.tabler.outline.Calendar
import com.composables.icons.tabler.outline.ChartBar
import com.composables.icons.tabler.outline.Clock
import com.composables.icons.tabler.outline.Cloud
import com.composables.icons.tabler.outline.Code
import com.composables.icons.tabler.outline.Database
import com.composables.icons.tabler.outline.File
import com.composables.icons.tabler.outline.FileText
import com.composables.icons.tabler.outline.Flag
import com.composables.icons.tabler.outline.FolderPlus
import com.composables.icons.tabler.outline.Heart
import com.composables.icons.tabler.outline.Home
import com.composables.icons.tabler.outline.List
import com.composables.icons.tabler.outline.Lock
import com.composables.icons.tabler.outline.Mail
import com.composables.icons.tabler.outline.Map
import com.composables.icons.tabler.outline.Music
import com.composables.icons.tabler.outline.Note
import com.composables.icons.tabler.outline.Pencil
import com.composables.icons.tabler.outline.Phone
import com.composables.icons.tabler.outline.Photo
import com.composables.icons.tabler.outline.PinnedOff
import com.composables.icons.tabler.outline.Plus
import com.composables.icons.tabler.outline.Robot
import com.composables.icons.tabler.outline.Rocket
import com.composables.icons.tabler.outline.Settings
import com.composables.icons.tabler.outline.Star
import com.composables.icons.tabler.outline.Tag
import com.composables.icons.tabler.outline.Terminal2
import com.composables.icons.tabler.outline.Video
import com.composables.icons.tabler.outline.Wallet
import com.composables.icons.tabler.outline.World
import com.composables.icons.tabler.outline.Activity
import com.composables.icons.tabler.outline.ArrowUp
import com.composables.icons.tabler.outline.Check
import com.composables.icons.tabler.outline.ChevronDown
import com.composables.icons.tabler.outline.ChevronLeft
import com.composables.icons.tabler.outline.ChevronRight
import com.composables.icons.tabler.outline.Dots
import com.composables.icons.tabler.outline.Folder
import com.composables.icons.tabler.outline.Ban
import com.composables.icons.tabler.outline.Hourglass
import com.composables.icons.tabler.outline.Shield
import com.composables.icons.tabler.outline.Inbox
import com.composables.icons.tabler.outline.LayoutGrid
import com.composables.icons.tabler.outline.MessageCircle
import com.composables.icons.tabler.outline.Palette
import com.composables.icons.tabler.outline.Paperclip
import com.composables.icons.tabler.outline.Pin
import com.composables.icons.tabler.outline.PlayerPlay
import com.composables.icons.tabler.outline.Refresh
import com.composables.icons.tabler.outline.Rocket
import com.composables.icons.tabler.outline.Search
import com.composables.icons.tabler.outline.Trash
import com.composables.icons.tabler.outline.User
import com.composables.icons.tabler.outline.X

/**
 * The app's icon vocabulary (w2-icons, docs/visual-language.md §3): **semantic names** over
 * Tabler Icons vectors. Business code imports `VadeIcons.Send`, never
 * `com.composables.icons.tabler.*` — that way an icon-pack swap stays a one-file change and
 * the visual-language stroke/fill rules are enforced in exactly one place.
 *
 * Naming contract (read with the icon, not the Tabler id):
 *  - [Send] / [Stop]   — the composer's send sphere: filled-arrow state vs stopped state.
 *    [Stop] is the **only filled vector** in the set (v2 §3: fill expresses selection /
 *    the streaming stop; everything else is 2dp stroke).
 *  - [Back] / [Close]  — navigation gestures (detail back, sheet/search dismiss).
 *  - [ChevronDown] / [ChevronRight] — expand/collapse affordances (tool cards, groups).
 *  - [Chat] / [Activity] / [Grid] / [Person] — the four destinations, in FR-8.77 order.
 *
 * Every name was checked against the actual `icons-tabler-*-cmp` 2.2.1 jars
 * (`javap` on the extension getters), not written from memory:
 * outline icons live on `Tabler.Outline`, the one fill icon on `Tabler.Filled`.
 */
object VadeIcons {

    /** Composer send (outline arrow-up; the sphere itself carries the accent state). */
    val Send: ImageVector = Tabler.Outline.ArrowUp

    /** Composer stop while streaming — the single filled exception (v2 §3). */
    val Stop: ImageVector = Tabler.Filled.PlayerStop

    /** Attach a file to the composer. */
    val Attach: ImageVector = Tabler.Outline.Paperclip

    /** Overflow / "more" row actions. */
    val More: ImageVector = Tabler.Outline.Dots

    /** Navigate back from a pushed detail. */
    val Back: ImageVector = Tabler.Outline.ChevronLeft

    /** Search entry (top bar / palette trigger). */
    val Search: ImageVector = Tabler.Outline.Search

    /** Dismiss (sheet, search, right panel). */
    val Close: ImageVector = Tabler.Outline.X

    /** Expand a collapsed section (tool card, group). */
    val ChevronDown: ImageVector = Tabler.Outline.ChevronDown

    /** Collapse / drill into a row. */
    val ChevronRight: ImageVector = Tabler.Outline.ChevronRight

    /** Compose a new session. */
    val Compose: ImageVector = Tabler.Outline.Pencil

    /** Confirm / approve. */
    val Check: ImageVector = Tabler.Outline.Check

    /** Destructive delete (paired with a confirm step). */
    val Trash: ImageVector = Tabler.Outline.Trash

    /** Retry / regenerate. */
    val Refresh: ImageVector = Tabler.Outline.Refresh

    /** Destination 1: 会话. */
    val Chat: ImageVector = Tabler.Outline.MessageCircle

    /** Destination 2: 活动. */
    val Activity: ImageVector = Tabler.Outline.Activity

    /** Destination 3: 能力. */
    val Grid: ImageVector = Tabler.Outline.LayoutGrid

    /** Destination 4: 我的. */
    val Person: ImageVector = Tabler.Outline.User

    /** Pin a session to the top of its group. */
    val Pin: ImageVector = Tabler.Outline.Pin

    /** Grouped / project sessions. */
    val Folder: ImageVector = Tabler.Outline.Folder

    /** Appearance / skin settings. */
    val Palette: ImageVector = Tabler.Outline.Palette

    /** Empty state (nothing here yet) — replaces the `○` character placeholder. */
    val Empty: ImageVector = Tabler.Outline.Inbox

    /** Waiting / pending (queued work, awaiting protocol). */
    val Waiting: ImageVector = Tabler.Outline.Hourglass

    /** Pending approval (nothing blocked yet). */
    val Pending: ImageVector = Tabler.Outline.Shield

    /** Failure / blocked (nothing failed yet). */
    val Failed: ImageVector = Tabler.Outline.Ban

    /** Create something new (新建分组). */
    val Plus: ImageVector = Tabler.Outline.Plus

    /** Archive a session (归档). */
    val Archive: ImageVector = Tabler.Outline.Archive

    /** Un-archive a session (取消归档). */
    val ArchiveOff: ImageVector = Tabler.Outline.ArchiveOff

    /** Un-pin a session (取消置顶). */
    val PinOff: ImageVector = Tabler.Outline.PinnedOff

    /** Create a new group (新建分组). */
    val FolderPlus: ImageVector = Tabler.Outline.FolderPlus

    // --- C-Activity (2026-11-14) · sub-agent state badges (ActivityScreen §子代理) ---
    /** Sub-agent just spawned — created, its loop has not produced a step yet. */
    val Spawned: ImageVector = Tabler.Outline.Rocket
    /** Sub-agent actively running its LLM loop. */
    val Running: ImageVector = Tabler.Outline.PlayerPlay
}

/**
 * RFC-0010 会话/分组图标白名单 → 矢量映射（w-c-meta 新增，追加条目不改旧条目）。
 *
 * 39 个键**逐字拷贝自服务端** `core/vade/session/manager.py` L77–85 的 `ICON_NAMES`
 * frozenset（Tabler 白名单）；每个 getter 都在 `icons-tabler-outline-cmp-jvm-2.2.1.jar`
 * 里用 `unzip`/`javap` 核实过存在（ChartBar/FileText/Terminal2/MessageCircle 来自
 * `Chart_barKt`/`File_textKt`/`Terminal_2Kt`/`Message_circleKt`）。业务代码从这里取
 * `MetaIcons["star"]`，不得直接 import Tabler（IconGuardTest 在守）。
 *
 * 服务端校验 `ICON_PATTERN + value in ICON_NAMES`，所以这里的键集合必须与白名单
 * **完全相等**——`MetaIconWhitelistTest` 把 39 硬编码钉死，服务端扩名单时此测试先红。
 */
object MetaIcons {

    /** 白名单名字（服务端拼写原样，含 `file-text` / `terminal-2` 连字符）。 */
    val names: List<String> = listOf(
        "archive", "book", "bookmark", "brain", "briefcase", "bulb", "calendar", "chart-bar",
        "chat", "clock", "cloud", "code", "database", "file", "file-text", "flag", "folder",
        "heart", "home", "list", "lock", "mail", "map", "music", "note", "pencil", "phone",
        "photo", "robot", "rocket", "search", "settings", "star", "tag", "terminal-2", "trash",
        "video", "wallet", "world",
    )

    /** name → vector；只含 [names] 里的 39 个键，一个不多。 */
    val byName: Map<String, ImageVector> = mapOf(
        "archive" to Tabler.Outline.Archive,
        "book" to Tabler.Outline.Book,
        "bookmark" to Tabler.Outline.Bookmark,
        "brain" to Tabler.Outline.Brain,
        "briefcase" to Tabler.Outline.Briefcase,
        "bulb" to Tabler.Outline.Bulb,
        "calendar" to Tabler.Outline.Calendar,
        "chart-bar" to Tabler.Outline.ChartBar,
        "chat" to Tabler.Outline.MessageCircle,
        "clock" to Tabler.Outline.Clock,
        "cloud" to Tabler.Outline.Cloud,
        "code" to Tabler.Outline.Code,
        "database" to Tabler.Outline.Database,
        "file" to Tabler.Outline.File,
        "file-text" to Tabler.Outline.FileText,
        "flag" to Tabler.Outline.Flag,
        "folder" to Tabler.Outline.Folder,
        "heart" to Tabler.Outline.Heart,
        "home" to Tabler.Outline.Home,
        "list" to Tabler.Outline.List,
        "lock" to Tabler.Outline.Lock,
        "mail" to Tabler.Outline.Mail,
        "map" to Tabler.Outline.Map,
        "music" to Tabler.Outline.Music,
        "note" to Tabler.Outline.Note,
        "pencil" to Tabler.Outline.Pencil,
        "phone" to Tabler.Outline.Phone,
        "photo" to Tabler.Outline.Photo,
        "robot" to Tabler.Outline.Robot,
        "rocket" to Tabler.Outline.Rocket,
        "search" to Tabler.Outline.Search,
        "settings" to Tabler.Outline.Settings,
        "star" to Tabler.Outline.Star,
        "tag" to Tabler.Outline.Tag,
        "terminal-2" to Tabler.Outline.Terminal2,
        "trash" to Tabler.Outline.Trash,
        "video" to Tabler.Outline.Video,
        "wallet" to Tabler.Outline.Wallet,
        "world" to Tabler.Outline.World,
    )

    /** 未知名字返回 null（调用方落回 [VadeIcons.Folder] 默认图标）。 */
    operator fun get(name: String): ImageVector? = byName[name]
}
