package de.bikemaintainer.app.ui.navigation

import kotlinx.serialization.Serializable

@Serializable object SetupRoute

@Serializable data class LoginRoute(val serverUrl: String, val relogin: Boolean = false)

@Serializable data class ProfilesRoute(val serverUrl: String)

@Serializable object VehiclesRoute

@Serializable object SettingsRoute
