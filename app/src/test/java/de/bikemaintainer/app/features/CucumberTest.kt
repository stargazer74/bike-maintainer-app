package de.bikemaintainer.app.features

import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.test.StandardTestDispatcher
import de.bikemaintainer.app.features.support.FailureCollector
import de.bikemaintainer.app.features.support.Scenarios
import de.bikemaintainer.app.features.support.World
import io.cucumber.core.cli.Main
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs every Gherkin scenario in `src/test/resources/features` as its own Robolectric test (no emulator): a fresh
 * Android environment and Compose rule per scenario, the real app (navigation, view models, repositories) against
 * [de.bikemaintainer.app.features.support.FakeBackend]. Cucumber's own JUnit runner cannot run inside Robolectric,
 * so each test hands its scenario to the Cucumber CLI and rethrows the first failing step.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
// A tall phone, so most screens fit without scrolling; lazy lists are scrolled by the steps.
@Config(qualifiers = "de-w411dp-h1400dp")
class CucumberTest(@Suppress("unused") private val name: String, private val location: String) {
    // Effects queued on the main thread as in the app: after the repositories' IO they resume there. The default
    // (unconfined) dispatcher would continue on the IO thread, where Navigation refuses to run.
    @get:Rule val compose = createComposeRule(StandardTestDispatcher())

    @Test
    fun scenario() {
        World.compose = compose
        FailureCollector.reset()
        val status = Main.run(
            arrayOf(
                "--glue", "de.bikemaintainer.app.features",
                "--plugin", FailureCollector::class.java.name,
                "--plugin", "pretty",
                "--monochrome",
                location,
            ),
            Thread.currentThread().contextClassLoader!!,
        )
        FailureCollector.failure?.let { throw it }
        check(status == 0.toByte()) { "Scenario failed: $location" }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun scenarios(): List<Array<Any>> = Scenarios.all().map { arrayOf(it.name, it.location) }
    }
}
