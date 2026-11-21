package de.bikemaintainer.app.features.support

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import io.cucumber.java.After
import io.cucumber.java.Before
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals

/**
 * What one scenario works with. [CucumberTest][de.bikemaintainer.app.features.CucumberTest] sets [compose] before it
 * hands the scenario to Cucumber; the hooks give every scenario a fresh backend, app storage and app.
 */
object World {
    lateinit var compose: ComposeContentTestRule
    lateinit var backend: FakeBackend
    lateinit var app: TestApp
    lateinit var dir: File

    /** The app was started (content set); a scenario starts it once. */
    var started = false
}

class Hooks {
    @Before(order = 0)
    fun freshWorld() {
        World.dir = Files.createTempDirectory("scenario").toFile()
        World.backend = FakeBackend()
        World.app = TestApp(World.dir)
        World.started = false
    }

    @After(order = 0)
    fun cleanUp() {
        World.backend.close()
        World.app.close()
        releaseLineBreaks()
        World.dir.deleteRecursively()
    }

    /** Runs before [cleanUp] (higher order first): the scenario fails if the app called what the fake lacks. */
    @After(order = 10)
    fun noUnknownEndpoints() {
        assertEquals("Endpoints the fake backend does not implement", emptyList<String>(), World.backend.unknownRequests.toList())
    }
}

/**
 * Robolectric keeps every line break result of text layout in a static registry and never frees it. All scenarios
 * share one sandbox, so after a few dozen of them the heap is full; done with a scenario, its layouts are not needed.
 */
private fun releaseLineBreaks() {
    val registry = Class.forName("org.robolectric.shadows.ShadowLineBreaker").getDeclaredField("nativeObjectRegistry")
        .apply { isAccessible = true }
        .get(null)
    registry.javaClass.getMethod("clear").invoke(registry)
}
