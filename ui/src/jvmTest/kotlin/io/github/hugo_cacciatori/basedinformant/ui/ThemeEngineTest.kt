package io.github.hugo_cacciatori.basedinformant.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.hugo_cacciatori.basedinformant.core.theme.Background
import io.github.hugo_cacciatori.basedinformant.core.theme.BackgroundEngine
import io.github.hugo_cacciatori.basedinformant.core.theme.BuiltInThemes
import io.github.hugo_cacciatori.basedinformant.core.theme.ThemePack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalTestApi::class)
class ThemeEngineTest {
    // The background's pause while the app is not visible runs on the main dispatcher.
    @BeforeTest
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun resetDispatcher() = Dispatchers.resetMain()

    private val plain = BuiltInThemes.pack("plain")
    private val starfield = plain.copy(background = Background(BackgroundEngine.STARFIELD, speed = 1f, density = 200))
    private val drift = plain.copy(
        dark = plain.dark.copy(sky = listOf("#101820", "#3A6EA5", "#A53A6E")),
        background = Background(BackgroundEngine.GRADIENT, speed = 1f),
    )

    @Test
    fun thePaletteBecomesMaterialRolesWithSolidCards() {
        val scheme = plain.dark.colorScheme(dark = true)
        assertEquals(color(plain.dark.primary), scheme.primary)
        assertEquals(color(plain.dark.surface), scheme.surface)
        assertNotEquals(1f, scheme.surface.alpha)
        assertEquals(scheme.surface.copy(alpha = 1f), scheme.surfaceContainer)
        assertEquals(scheme.onSurface, scheme.onSurfaceVariant)
    }

    @Test
    fun theThemeReachesTheScreens() = runComposeUiTest {
        var seen = Color.Unspecified
        setContent {
            AppTheme(plain, dark = true) {
                seen = MaterialTheme.colorScheme.primary
                Text("hello")
            }
        }
        waitForIdle()
        assertEquals(color(plain.dark.primary), seen)
    }

    @Test
    fun aStillBackgroundIsTheSkyGradient() = runComposeUiTest {
        setContent { AppTheme(plain, dark = true) { Spacer() } }
        val pixels = onNodeWithTag("theme-background").captureToImage().toPixelMap()
        assertEquals(color(plain.dark.sky.first()), pixels[0, 0])
    }

    @Test
    fun movingBackgroundsChangeOverTimeAndStopWhenAnimationsAreOff() {
        for (pack in listOf(starfield, drift)) {
            assertNotEquals(frame(pack, animations = true, seconds = 0), frame(pack, animations = true, seconds = 2), pack.background.engine.name)
            assertEquals(frame(pack, animations = false, seconds = 0), frame(pack, animations = false, seconds = 2), pack.background.engine.name)
        }
    }

    /** The background's pixels after [seconds] of the test clock. */
    private fun frame(pack: ThemePack, animations: Boolean, seconds: Int): List<Color> {
        var pixels: List<Color> = emptyList()
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent { AppTheme(pack, animations, dark = true) { Spacer() } }
            mainClock.advanceTimeBy(seconds * 1000L + 100)
            val map = onNodeWithTag("theme-background").captureToImage().toPixelMap()
            pixels = (0 until map.width step 7).flatMap { x -> (0 until map.height step 7).map { y -> map[x, y] } }
        }
        return pixels
    }

    @androidx.compose.runtime.Composable
    private fun Spacer() = androidx.compose.foundation.layout.Spacer(Modifier.size(200.dp))
}
