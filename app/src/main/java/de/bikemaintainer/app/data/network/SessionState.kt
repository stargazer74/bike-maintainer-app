package de.bikemaintainer.app.data.network

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import okhttp3.Interceptor
import okhttp3.Response

enum class SessionStatus { ACTIVE, LOGIN_REQUIRED, PROFILE_SELECTION_REQUIRED }

/** The session generation a request was sent in; attached to requests as OkHttp tag. */
data class SessionGeneration(val value: Long)

/**
 * In-memory state of the server session. Every new session (renewal by the authenticator, login, profile selection)
 * starts a new generation, so that a 401 from a request of an older generation does not trigger another renewal.
 */
class SessionState(initial: SessionStatus = SessionStatus.ACTIVE) {
    private val _status = MutableStateFlow(initial)
    val status: StateFlow<SessionStatus> = _status.asStateFlow()

    /**
     * Serialises everything that stores credentials and starts a new session: the authenticator's token exchange as
     * well as login, profile selection and logout. An in-flight exchange of an old token thus cannot overwrite a
     * fresh login (and vice versa).
     */
    val lock = Mutex()

    private val _generation = AtomicLong()
    val generation: Long get() = _generation.get()

    fun sessionStarted() {
        _generation.incrementAndGet()
        _status.value = SessionStatus.ACTIVE
    }

    fun require(status: SessionStatus) {
        _status.value = status
    }
}

/** Application interceptor: tags every request with the session generation it is sent in. */
class GenerationTagInterceptor(private val state: SessionState) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return chain.proceed(
            request.newBuilder().tag(SessionGeneration::class, SessionGeneration(state.generation)).build(),
        )
    }
}
