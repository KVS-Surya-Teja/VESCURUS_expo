package com.example.vescurus.ui.previews

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.vescurus.model.BUILT_IN_RECIPES
import com.example.vescurus.ui.ChatMessage
import com.example.vescurus.ui.CookChatBubble
import com.example.vescurus.ui.ObservationPanel
import com.example.vescurus.ui.RecipeSelectionCard
import com.example.vescurus.ui.SessionCard
import com.example.vescurus.ui.theme.VESCURUSTheme

// Component previews use production composables and explicit fixtures.
// They do not recreate a Cook screen or start a ViewModel, camera or network session.
@Composable
private fun CookComponentPreview(content: @Composable ColumnScope.() -> Unit) {
    VESCURUSTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}

@Preview(name = "Recipe card · offline guide", group = "Cook components", widthDp = 360)
@Preview(name = "Recipe card · narrow / larger text", group = "Cook components", widthDp = 320, fontScale = 1.3f)
@Composable
fun PreviewCookRecipeCard() {
    CookComponentPreview {
        RecipeSelectionCard(
            recipe = BUILT_IN_RECIPES.first(),
            isSelected = true,
            onClick = {}
        )
    }
}

@Preview(name = "Chat bubbles · sample conversation", group = "Cook components", widthDp = 360)
@Composable
fun PreviewCookChatBubbles() {
    CookComponentPreview {
        CookChatBubble(ChatMessage("User", "How should I prepare the vegetables?"))
        CookChatBubble(ChatMessage("AI suggestion", "Wash and chop the tomato and onion before starting your recipe."))
        CookChatBubble(ChatMessage("System", "Assistant unavailable. Your saved recipe is still accessible."))
    }
}

@Preview(name = "Analyzed frame · empty state", group = "Cook components", widthDp = 360)
@Composable
fun PreviewCookAnalyzedFrameEmpty() {
    CookComponentPreview {
        ObservationPanel(
            observation = null,
            modifier = Modifier.fillMaxWidth().height(190.dp)
        )
    }
}

@Preview(name = "Session card · recipe content", group = "Cook components", widthDp = 360)
@Composable
fun PreviewCookSessionCard() {
    val recipe = BUILT_IN_RECIPES.first()
    CookComponentPreview {
        SessionCard(title = recipe.name, subtitle = "Offline guide · component preview") {
            Text(
                text = recipe.steps.first().instruction,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
