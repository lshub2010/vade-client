package vade.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import vade.ui.skin.ICON_PRESS_SCALE
import vade.ui.skin.REDUCE_MOTION_MAX_MS
import vade.ui.skin.SkinMotion
import vade.ui.skin.SkinOrb
import vade.ui.skin.material.MaterialSkin
import vade.ui.skin.miuix.MiuixSkin
import vade.ui.skin.reducedDurationMs
import vade.ui.skin.reducedTravelPx

/**
 * K1 / K2 · 动效降级与按压缩放的数值面（`skin/Skin.kt` + [ChatIcons] 的 KDoc 都点名本类）。
 *
 * 这些是纯函数，所以断言可以精确到数字，不需要真的跑一次动画：
 *
 *  - **K1 时长**：reduced-motion 是**封顶**不是归零（[REDUCE_MOTION_MAX_MS]）——归零会让
 *    「我点到了」的因果感消失；已经比封顶短的时长不许被反过来拉长。
 *  - **K1 位移**：位移归零（改纯淡入），这是前庭敏感读者的要求，不是「顺便也减一半」。
 *  - **K2 按压**：按下且有启用 → 三个数一起变；未按下 / 未启用 → 逐项静止（1 / 1 / 0），
 *    不许出现「缩了但没染色」这种半截反馈。
 *  - **同一个按压比**：图标按钮读的数必须等于发送球（[SkinOrb.pressScale]），一屏两种按压比
 *    就是 §2/§7 禁止的那件事。
 */
class ChatMotionTest {

    // ---------------------------------------------------------------- K1 · 时长

    @Test
    fun reduceMotionCapsLongDurationsAtTheDocumentedCeiling() {
        assertEquals(260, reducedDurationMs(260, reduceMotion = false), "不降级 = 原值，不许偷偷缩短")
        assertEquals(REDUCE_MOTION_MAX_MS, reducedDurationMs(260, reduceMotion = true))
        assertEquals(100, reducedDurationMs(260, reduceMotion = true), "封顶值就是 100ms（写死防漂移）")
    }

    @Test
    fun reduceMotionDoesNotInflateAlreadyShortDurations() {
        // coerceAtMost，不是「一律改成 100」：60ms 的按压反馈不该被拉长到 100ms。
        assertEquals(60, reducedDurationMs(60, reduceMotion = true))
        assertEquals(REDUCE_MOTION_MAX_MS, reducedDurationMs(REDUCE_MOTION_MAX_MS, reduceMotion = true))
        assertEquals(0, reducedDurationMs(0, reduceMotion = true), "0 就是 0（立即完成）")
    }

    // ---------------------------------------------------------------- K1 · 位移

    @Test
    fun reduceMotionZeroesTravelButLeavesItAloneOtherwise() {
        assertEquals(24, reducedTravelPx(24, reduceMotion = false))
        assertEquals(0, reducedTravelPx(24, reduceMotion = true), "位移类动画降级成纯淡入")
    }

    // ------------------------------------------- K1 · SkinMotion 走同一条规则

    @Test
    fun skinMotionRoutesThroughTheSameRuleNotASecondCopy() {
        val motion = SkinMotion()
        assertEquals(motion.standardMs, motion.durationMs(motion.standardMs, reduceMotion = false))
        assertEquals(REDUCE_MOTION_MAX_MS, motion.durationMs(motion.emphasizedMs, reduceMotion = true))
        assertEquals(24, motion.travelPx(24, reduceMotion = false))
        assertEquals(0, motion.travelPx(24, reduceMotion = true))
    }

    // ---------------------------------------------------------------- K2 · 按压

    @Test
    fun pressedAndEnabledMovesAllThreeNumbersTogether() {
        val spec = iconPressSpec(pressed = true, enabled = true)
        assertEquals(ICON_PRESS_SCALE, spec.scale, "缩放读统一常量")
        assertEquals(ICON_PRESSED_TINT_ALPHA, spec.tintAlpha, "图标色一起变淡")
        assertEquals(ICON_PRESSED_DISC_ALPHA, spec.discAlpha, "圆形底一起出现")
    }

    @Test
    fun idleIsExactlyStill() {
        val idle = IconPressSpec(scale = 1f, tintAlpha = 1f, discAlpha = 0f)
        assertEquals(idle, iconPressSpec(pressed = false, enabled = true), "没按下 = 完全静止")
    }

    @Test
    fun disabledButtonsNeverPress() {
        val idle = IconPressSpec(scale = 1f, tintAlpha = 1f, discAlpha = 0f)
        assertEquals(idle, iconPressSpec(pressed = true, enabled = false), "禁用态连按下的形都不给")
    }

    @Test
    fun callerSuppliedPressScaleIsHonoured() {
        assertEquals(0.8f, iconPressSpec(pressed = true, enabled = true, pressScale = 0.8f).scale)
    }

    @Test
    fun iconPressRatioIsTheSendOrbRatioOnEveryInstalledSkin() {
        assertEquals(SkinOrb().pressScale, ICON_PRESS_SCALE, "图标按钮与发送球必须是同一个按压比")
        assertEquals(MaterialSkin().orb.pressScale, ICON_PRESS_SCALE, "material 的球也是 0.92")
        assertEquals(MiuixSkin().orb.pressScale, ICON_PRESS_SCALE, "miuix 的球也是 0.92")
        assertTrue(ICON_PRESS_SCALE < 1f && ICON_PRESS_SCALE > 0.9f, "按压比要能看出「按下去」又不塌")
        assertFalse(ICON_PRESSED_TINT_ALPHA > 1f, "tint 只能压暗，不能提亮")
    }
}
