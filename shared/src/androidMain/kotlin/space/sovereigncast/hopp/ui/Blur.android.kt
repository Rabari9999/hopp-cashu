package space.sovereigncast.hopp.ui

import android.os.Build

internal actual val canBlur: Boolean = Build.VERSION.SDK_INT >= 31
