package de.bikemaintainer.app.features.steps

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import de.bikemaintainer.app.features.support.World
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.Assert.assertTrue

/**
 * Generic steps by visible text, label or content description (quoted text is the German UI), like the web app's
 * `ui.steps.ts`. Each step waits for the app: requests run on real threads against the fake backend.
 */
class UiSteps {
    private val compose get() = World.compose

    @Given("I open the app")
    fun openApp() {
        check(!World.started) { "The app is already open" }
        World.started = true
        compose.setContent(World.app.content())
        settle()
    }

    @When("I fill in {string} with {string}")
    fun fillIn(label: String, value: String) {
        val field = node(hasSetTextAction() and hasText(label, substring = true), "text field \"$label\"")
        act {
            field.performTextClearance()
            field.performTextInput(value)
        }
        settle()
    }

    @When("I clear {string}")
    fun clear(label: String) {
        val field = node(hasSetTextAction() and hasText(label, substring = true), "text field \"$label\"")
        act { field.performTextClearance() }
        settle()
    }

    @When("I click {string}")
    fun click(text: String) {
        node(clickable(text), "button \"$text\"")
        awaitStable()
        val before = screenTexts()
        act { first(clickable(text)).performClick() }
        awaitStable()
        // Navigation drops a tap while the page is not resumed yet (still entering): if nothing happened, tap again,
        // as a user would. Switches change no text: use "I check" for them.
        var retries = 0
        while (screenTexts() == before && exists(clickable(text)) && retries++ < MAX_RETRIES) {
            act { first(clickable(text)).performClick() }
            awaitStable()
        }
    }

    @When("I check {string}")
    fun check(text: String) {
        val box = node(hasText(text) and (isToggleable() or hasClickAction()), "checkbox \"$text\"")
        act { box.performClick() }
        settle()
    }

    @When("I select {string} in {string}")
    fun select(option: String, label: String) {
        val selection = node(hasText(label, substring = true) and hasClickAction(), "selection \"$label\"")
        act { selection.performClick() }
        settle()
        click(option)
    }

    @When("I open the vehicle {string}")
    fun openVehicle(name: String) = click(name)

    @When("I go back")
    fun goBack() = click("Zurück")

    @Then("I see the text {string}")
    fun seeText(text: String) {
        val matcher = hasText(text, substring = true) or hasContentDescription(text, substring = true)
        waitFor("text \"$text\"", matcher)
    }

    @Then("I do not see the text {string}")
    fun notSeeText(text: String) = waitForAbsence("text \"$text\"", hasText(text, substring = true))

    @Then("I see a button {string}")
    fun seeButton(text: String) {
        waitFor("button \"$text\"", clickable(text))
    }

    @Then("I do not see a button {string}")
    fun notSeeButton(text: String) = waitForAbsence("button \"$text\"", clickable(text))

    @Then("the field {string} contains {string}")
    fun fieldContains(label: String, value: String) {
        waitFor("field \"$label\" with \"$value\"", hasSetTextAction() and hasText(label, substring = true) and hasEditableText(value))
    }

    private fun clickable(text: String) = (hasText(text) or hasContentDescription(text)) and hasClickAction()

    private fun hasEditableText(value: String) = SemanticsMatcher("EditableText = $value") {
        (if (SemanticsProperties.EditableText in it.config) it.config[SemanticsProperties.EditableText].text else null) == value
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /**
     * Scrolls the first lazy list that holds a node for [matcher] to it (items off screen are not composed).
     */
    private fun scrollTo(matcher: SemanticsMatcher): Boolean {
        val lists = compose.onAllNodes(hasScrollToNodeAction())
        for (i in 0 until lists.fetchSemanticsNodes().size) {
            try {
                lists[i].performScrollToNode(matcher)
                return true
            } catch (_: AssertionError) {
                // not in this list
            }
        }
        return false
    }

    /** Found, or found after scrolling a lazy list once. */
    private fun existsOrScroll(matcher: SemanticsMatcher) = exists(matcher) || (scrollTo(matcher) && exists(matcher))

    /**
     * The (first) node, preferring one in an open dialog. No `performScrollTo`: it loops forever on a node that never
     * fits the viewport; the screen is tall and lazy lists are scrolled by [waitFor].
     */
    private fun node(matcher: SemanticsMatcher, what: String): SemanticsNodeInteraction {
        waitFor(what, matcher)
        return first(matcher)
    }

    /** The first node for [matcher] right now, preferring one in an open dialog. */
    private fun first(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val inDialog = matcher and hasAnyAncestor(isDialog())
        return if (exists(inDialog)) compose.onAllNodes(inDialog)[0] else compose.onAllNodes(matcher)[0]
    }

    /** Waits for [matcher]; now and then it also tries to scroll a lazy list to it. */
    private fun waitFor(what: String, matcher: SemanticsMatcher) {
        var polls = 0
        try {
            compose.waitUntil(TIMEOUT_MILLIS) {
                exists(matcher) || (polls++ % SCROLL_EVERY_POLLS == 0 && scrollTo(matcher) && exists(matcher))
            }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Not found within ${TIMEOUT_MILLIS / 1000} s: $what\nOn screen: ${screenTexts()}", e)
        }
    }

    /** Waits until [matcher] is gone, e.g. after a reload whose answer is still on its way. */
    private fun waitForAbsence(what: String, matcher: SemanticsMatcher) {
        settle()
        try {
            compose.waitUntil(TIMEOUT_MILLIS) { !existsOrScroll(matcher) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Still there after ${TIMEOUT_MILLIS / 1000} s: $what\nOn screen: ${screenTexts()}", e)
        }
    }

    /** Lets the app finish what it started (requests, transitions). */
    private fun settle() = compose.waitForIdle()

    /** Until the screen stops changing (data still arriving, a page still entering), at most a few rounds. */
    private fun awaitStable() {
        var previous = screenTexts()
        repeat(MAX_RETRIES) {
            compose.mainClock.advanceTimeBy(STABLE_MILLIS)
            settle()
            val current = screenTexts()
            if (current == previous) return
            previous = current
        }
    }

    private fun act(input: () -> Unit) {
        input()
        settle()
    }

    /** Every text on screen, for failure messages. */
    private fun screenTexts(): List<String> = compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .flatMap { node ->
            val texts = if (SemanticsProperties.Text in node.config) node.config[SemanticsProperties.Text].map { it.text } else emptyList()
            val descriptions = if (SemanticsProperties.ContentDescription in node.config) {
                node.config[SemanticsProperties.ContentDescription].map { "[$it]" }
            } else {
                emptyList()
            }
            texts + descriptions
        }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val STABLE_MILLIS = 200L
        const val MAX_RETRIES = 5
        const val SCROLL_EVERY_POLLS = 25
    }
}
