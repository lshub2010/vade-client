package vade.ui.skin.miuix

/**
 * `true` only on Desktop (JVM). Android and Web draw at the platform's own density, which
 * Miuix is already tuned for; the Desktop 1.18× compensation exists because a 1280×800
 * desktop window renders Miuix's phone metrics too small (see [MiuixTokens.DESKTOP_DENSITY_SCALE]).
 */
internal actual val MiuixPlatform_isDesktop: Boolean = true
