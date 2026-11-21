package de.bikemaintainer.app.features.support

import io.cucumber.plugin.ConcurrentEventListener
import io.cucumber.plugin.event.EventPublisher
import io.cucumber.plugin.event.PickleStepTestStep
import io.cucumber.plugin.event.Status
import io.cucumber.plugin.event.TestStepFinished

/** Keeps the first failing (or undefined) step of the scenario, so the JUnit test fails with its real cause. */
class FailureCollector : ConcurrentEventListener {
    override fun setEventPublisher(publisher: EventPublisher) {
        publisher.registerHandlerFor(TestStepFinished::class.java) { event ->
            if (failure != null) return@registerHandlerFor
            val step = (event.testStep as? PickleStepTestStep)?.step?.text ?: event.testStep.codeLocation
            failure = when (event.result.status) {
                Status.FAILED -> AssertionError("Step failed: $step", event.result.error)
                Status.UNDEFINED -> AssertionError("Undefined step: $step")
                Status.AMBIGUOUS -> AssertionError("Ambiguous step: $step", event.result.error)
                else -> null
            }
        }
    }

    companion object {
        @Volatile var failure: Throwable? = null

        fun reset() {
            failure = null
        }
    }
}
