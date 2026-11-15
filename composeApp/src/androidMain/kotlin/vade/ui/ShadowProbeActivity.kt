// g-shadow-android probe — dev scaffolding, NOT a product screen. No intent filter,
// launched only by `adb shell am start -n vade.ui/.ShadowProbeActivity`.
//
// probe-v3: why does the shelf paint in isolation but not under the real composer?
// Candidates under test — the app-side wrappers between ChatThread and the InputBar:
//   shelf-plain — the exact InputBar modifier chain (layeredShadow outsideOnly=true).
//   shelf-acs   — the same card inside Box(Modifier.animateContentSize()), the wrapper
//                 ChatThread puts around skin.InputBar for the multi-line height glide.
//   shelf-acs2  — animateContentSize with fillMaxWidth (the real wrapper also fills).

package vade.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import vade.ui.component.liquid.VadeShadows
import vade.ui.component.liquid.layeredShadow

class ShadowProbeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shape = RoundedCornerShape(28.dp)
        val canvas = Color(0xFFF7F7F7)
        val hairline = Color(0xFFE2E2E2)
        setContent {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(canvas)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                Text("shadow probe v3")
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                        .semantics { contentDescription = "shelf-plain" }
                        .layeredShadow(VadeShadows.float, shape, dark = false, outsideOnly = true)
                        .clip(shape)
                        .background(Color.White)
                        .border(1.dp, hairline, shape),
                )
                Spacer(Modifier.height(76.dp))
                Box(Modifier.animateContentSize()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(68.dp)
                            .semantics { contentDescription = "shelf-acs" }
                            .layeredShadow(VadeShadows.float, shape, dark = false, outsideOnly = true)
                            .clip(shape)
                            .background(Color.White)
                            .border(1.dp, hairline, shape),
                    )
                }
                Spacer(Modifier.height(76.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .animateContentSize(),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(68.dp)
                            .semantics { contentDescription = "shelf-acs2" }
                            .layeredShadow(VadeShadows.float, shape, dark = false, outsideOnly = true)
                            .clip(shape)
                            .background(Color.White)
                            .border(1.dp, hairline, shape),
                    )
                }
                Spacer(Modifier.height(76.dp))
            }
        }
    }
}
