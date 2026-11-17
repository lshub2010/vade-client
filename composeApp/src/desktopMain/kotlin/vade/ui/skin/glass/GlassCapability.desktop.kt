package vade.ui.skin.glass

/**
 * Desktop actual — 真实背景模糊，**没有折射**（D22：Desktop/Web 不做折射）。
 *
 * 模糊本身是探针验过的：`tmp/haze-probe` 的桌面截图证明 `hazeBlur` 在这条 `jvmApiElements`
 * 变体上真的出高斯模糊（不是只把 tint 画上去）。所以 Desktop 的能力档位是
 * [GlassCapability.Blur]，不是 `TintStroke`。
 *
 * 折射在这里**不是"还没写"，是"不许写"**：D22 明确把折射限定在 Android 13+ 与 iOS 系统
 * 控件上。`GlassCapability.Refraction` 在 Desktop 上永远不会被返回，即使有人给
 * `GlassSurface` 传了折射参数 —— 阶梯是唯一开关，组件层没有第二条路。
 *
 * 模糊半径按 dp 传入（`SkinGlass.blurRadiusDp`），haze 在内部用 draw-time density 换算，
 * 所以窗口缩放 / HiDPI 不会让半径漂移（任务书 §T3「Desktop」那一条）。
 */
actual fun platformGlassCapability(): GlassCapability = GlassCapability.Blur
