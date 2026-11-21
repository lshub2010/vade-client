package vade.ui.data

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Desktop actual of [platformPairingStore]: **一个 0600 的文件**。
 *
 * 位置：`$VADE_HOME/pairing.json`，否则 `$XDG_CONFIG_HOME/vade/pairing.json`，否则
 * `~/.config/vade/pairing.json`。
 *
 * 权限是安全边界的一部分：文件里是 `__Host-ac_ui`（本机对这台 core 的完整 UI 权限，含
 * `approval/decide`）。写入走「同目录临时文件 → 设 0600 → 原子 move」，于是：
 *  - 内容不会以临时文件的默认权限（0644）留在磁盘上被别的用户读到；
 *  - 中途崩溃不会留下半份 JSON（解码严格，半份等于没有凭据 → 回配对卡，而不是带着坏配置连）。
 *
 * 非 POSIX 文件系统（Windows 等）上 `setPosixFilePermissions` 会抛
 * `UnsupportedOperationException` —— 那里退化成操作系统默认 ACL，不能让写入直接失败。
 */
actual fun platformPairingStore(): PairingStore {
    val file = desktopPairingPath()
    return object : StringPairingStore(
        read = { readPrivately(file) },
        write = { text -> writePrivately(file, text) },
        erase = { runCatching { Files.deleteIfExists(file) } },
    ) {}
}

/** 凭据文件的位置（测试与文档引用同一个函数，不手写第二份路径规则）。 */
internal fun desktopPairingPath(): Path {
    val home = System.getenv("VADE_HOME")?.trim()?.takeIf { it.isNotEmpty() }
    if (home != null) return Paths.get(home, "pairing.json")
    val configHome = System.getenv("XDG_CONFIG_HOME")?.trim()?.takeIf { it.isNotEmpty() }
        ?: Paths.get(System.getProperty("user.home") ?: ".", ".config").toString()
    return Paths.get(configHome, "vade", "pairing.json")
}

private fun readPrivately(file: Path): String? =
    if (!Files.isRegularFile(file)) null else runCatching { Files.readString(file) }.getOrNull()

/** 「临时文件 → 0600 → 原子替换」。文件系统不支持原子 move 时退化成普通替换。 */
private fun writePrivately(file: Path, text: String) {
    val dir = file.toAbsolutePath().parent
    runCatching { Files.createDirectories(dir) }
    val tmp = Files.createTempFile(dir, "pairing", ".json.tmp")
    try {
        Files.writeString(tmp, text)
        runCatching {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"))
        }
        runCatching { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            .getOrElse {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
    } finally {
        runCatching { Files.deleteIfExists(tmp) }
    }
}
