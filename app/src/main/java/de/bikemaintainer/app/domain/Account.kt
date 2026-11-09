package de.bikemaintainer.app.domain

/** The signed-in person: [email] in portal mode, [displayName] in household mode. */
data class Account(val email: String?, val displayName: String?)
