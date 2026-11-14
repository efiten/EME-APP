package be.on5gs.eme.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

/**
 * Lijststatus voor een lijst met het nieuwste bericht bovenaan.
 *
 * Een LazyColumn met keys houdt het bovenste zichtbare item op zijn plaats als er items boven
 * bijkomen, zodat wie bovenaan leest een nieuw bericht niet ziet. Stond de lijst helemaal bovenaan,
 * dan blijft ze daar wanneer [firstKey] (de key van het bovenste item) verandert. Wie naar oudere
 * berichten gescrold is, blijft op hetzelfde bericht staan.
 */
@Composable
fun rememberNewestOnTopListState(firstKey: Any?): LazyListState {
    val state = rememberLazyListState()
    val atTop by remember {
        derivedStateOf { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 }
    }
    // De compositie loopt vóór de lijst met de nieuwe items gemeten wordt: atTop is nog de oude positie
    remember(firstKey) {
        if (atTop) state.requestScrollToItem(0)
    }
    return state
}
