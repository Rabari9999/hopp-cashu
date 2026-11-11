package space.sovereigncast.hopp.ui.components

import androidx.compose.ui.unit.dp

// Compose keeps it to itself here: the keys stay with whatever holds the focus.
internal actual fun textFieldFocused(): Boolean? = null

// A notch of a mouse wheel.
internal actual fun scrollUnitPx(density: androidx.compose.ui.unit.Density): Float = with(density) { 64.dp.toPx() }
