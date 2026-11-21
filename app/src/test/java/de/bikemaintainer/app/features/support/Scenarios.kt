package de.bikemaintainer.app.features.support

import java.io.File

/**
 * The scenarios of the feature files on the test classpath, one test each. Scenario outlines are not used. A scenario
 * tagged `@bug` describes behaviour that is still broken (with its ticket as second tag, e.g. `@bug @app-90`): it is
 * not run until the fix removes the tag. `@wip` marks a scenario the test harness cannot drive yet (see README); it is
 * skipped the same way.
 */
object Scenarios {
    data class Entry(val name: String, val location: String)

    private val SKIPPED = setOf("@bug", "@wip")

    private val SCENARIO = Regex("^\\s*Scenario:\\s*(.+)$")

    fun all(): List<Entry> {
        val url = Scenarios::class.java.classLoader!!.getResource("features") ?: error("No features on the classpath")
        val dir = File(url.toURI())
        return dir.listFiles { f -> f.name.endsWith(".feature") }.orEmpty().sortedBy { it.name }.flatMap { file ->
            val lines = file.readLines()
            lines.mapIndexedNotNull { index, line ->
                val tags = lines.getOrNull(index - 1)?.trim().orEmpty()
                if (tags.startsWith("@") && tags.split(" ").any { it in SKIPPED }) return@mapIndexedNotNull null
                SCENARIO.matchEntire(line)?.let { match ->
                    Entry("${file.nameWithoutExtension}: ${match.groupValues[1].trim()}", "classpath:features/${file.name}:${index + 1}")
                }
            }
        }
    }
}
