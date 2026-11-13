package de.bikemaintainer.app.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.bikemaintainer.app.R
import de.bikemaintainer.app.domain.VehicleType
import de.bikemaintainer.app.ui.vehicleform.labelRes

/** Material Symbol per vehicle type, as in the web app (`vehicle-format.ts`; no ATV symbol, the quad uses the helmet). */
@DrawableRes
fun VehicleType.iconRes(): Int =
    when (this) {
        VehicleType.MOTORCYCLE -> R.drawable.ic_vehicle_motorcycle
        VehicleType.CAR -> R.drawable.ic_vehicle_car
        VehicleType.BICYCLE -> R.drawable.ic_vehicle_bicycle
        VehicleType.E_BIKE -> R.drawable.ic_vehicle_e_bike
        VehicleType.QUAD -> R.drawable.ic_vehicle_quad
        VehicleType.SCOOTER -> R.drawable.ic_vehicle_scooter
    }

/** Orange-tinted tile with the symbol of the vehicle type; TalkBack reads the type ("Motorrad"). */
@Composable
fun VehicleTypeTile(type: VehicleType, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .size(size)
            .background(primary.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(type.iconRes()),
            contentDescription = stringResource(type.labelRes()),
            tint = primary,
            modifier = Modifier.size(size * 0.58f),
        )
    }
}
