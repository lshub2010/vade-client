package vade.ui.icon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The w2-icons acceptance page: every public [VadeIcons] entry in a labelled grid, one
 * screenshot-able surface that proves the icon pack renders as real vectors (docs/
 * visual-language.md §3 — icon shapes are checked per row against this page).
 *
 * Deliberately standalone: no `Skin`, no `material3` — the two colours arrive as parameters
 * so the same composable photographs honestly on the dark and light canvases. It is reached
 * through the desktop entry flag `--gallery` (see `Main.kt`); it is not wired into the
 * shell navigation.
 *
 * @param tint       colour for the icon glyphs (pass a canvas-appropriate token).
 * @param labelColor colour for the semantic-name captions.
 * @param background the canvas behind the grid (e.g. the §2 dark token `0xFF0E0E10`).
 */
@Composable
fun IconGallery(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    labelColor: Color = Color(0xFF9A9AA3),
    background: Color = Color(0xFF0E0E10),
) {
    val entries: List<Pair<String, ImageVector>> = listOf(
        "Send" to VadeIcons.Send,
        "Stop" to VadeIcons.Stop,
        "Attach" to VadeIcons.Attach,
        "More" to VadeIcons.More,
        "Back" to VadeIcons.Back,
        "Search" to VadeIcons.Search,
        "Close" to VadeIcons.Close,
        "ChevronDown" to VadeIcons.ChevronDown,
        "ChevronRight" to VadeIcons.ChevronRight,
        "Compose" to VadeIcons.Compose,
        "Check" to VadeIcons.Check,
        "Trash" to VadeIcons.Trash,
        "Refresh" to VadeIcons.Refresh,
        "Chat" to VadeIcons.Chat,
        "Activity" to VadeIcons.Activity,
        "Grid" to VadeIcons.Grid,
        "Person" to VadeIcons.Person,
        "Pin" to VadeIcons.Pin,
        "Folder" to VadeIcons.Folder,
        "Palette" to VadeIcons.Palette,
    )

    Column(modifier = modifier.fillMaxSize().background(background)) {
        BasicText(
            text = "VadeIcons · Tabler (${entries.size})",
            style = TextStyle(fontSize = 12.sp, color = labelColor),
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(entries, key = { it.first }) { (name, vector) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier.padding(6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        VadeIcon(
                            vector = vector,
                            contentDescription = name,
                            size = 28.dp,
                            tint = tint,
                        )
                    }
                    BasicText(
                        text = name,
                        style = TextStyle(fontSize = 10.sp, color = labelColor),
                    )
                }
            }
        }
    }
}
