package be.on5gs.eme.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Lijsten met het nieuwste bericht bovenaan (#13, bevinding 2). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w411dp-h891dp")
class NewestOnTopTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val items = mutableStateOf((10 downTo 1).toList())
    private lateinit var state: LazyListState

    private fun show() = compose.setContent {
        val list = items.value
        state = rememberNewestOnTopListState(list.firstOrNull())
        LazyColumn(state = state, modifier = Modifier.height(200.dp)) {
            items(list, key = { it }) { Text("message $it", Modifier.height(50.dp)) }
        }
    }

    /** Wie bovenaan leest, ziet een nieuw bericht meteen. */
    @Test
    fun atTopNewMessageIsShown() {
        show()
        items.value = listOf(11) + items.value
        compose.waitForIdle()
        assertEquals(0, state.firstVisibleItemIndex)
        assertEquals(0, state.firstVisibleItemScrollOffset)
    }

    /** Wie naar oudere berichten gescrold is, blijft op hetzelfde bericht staan. */
    @Test
    fun scrolledDownStaysOnTheSameMessage() {
        show()
        compose.runOnIdle { runBlocking { state.scrollToItem(3) } }   // bovenaan zichtbaar: message 7
        items.value = listOf(11) + items.value
        compose.waitForIdle()
        assertEquals(4, state.firstVisibleItemIndex)                  // nog altijd message 7
    }
}
