package de.bikemaintainer.app.domain

/** The signed-in person: [email] in portal mode, [displayName] in household mode. */
data class Account(val email: String?, val displayName: String?) {
    /** Personal data stays out of logs and crash reports: only whether a value is present. */
    override fun toString(): String =
        "Account(email=${if (email == null) "null" else "***"}, displayName=${if (displayName == null) "null" else "***"})"
}
