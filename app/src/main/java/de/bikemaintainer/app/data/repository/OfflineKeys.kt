package de.bikemaintainer.app.data.repository

/** Keys of the server answers stored offline; per-vehicle keys end in `-<vehicleId>`. */
internal object OfflineKeys {
    const val VEHICLES = "vehicles"

    fun vehicle(id: Long) = "vehicle-$id"

    fun tasks(vehicleId: Long) = "tasks-$vehicleId"

    fun templates(vehicleId: Long) = "templates-$vehicleId"

    fun specification(vehicleId: Long) = "specification-$vehicleId"

    fun latest(vehicleId: Long) = "latest-$vehicleId"

    /** The vehicle a per-vehicle key belongs to, null for other keys. */
    fun vehicleIdOf(key: String): Long? = PER_VEHICLE.matchEntire(key)?.groupValues?.get(1)?.toLongOrNull()

    private val PER_VEHICLE = Regex("(?:vehicle|tasks|templates|specification|latest)-(\\d+)")
}
