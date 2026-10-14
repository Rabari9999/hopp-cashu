package space.sovereigncast.hopp.ui

import android.os.Build
import coil3.ComponentRegistry
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder

// Android 9 brought ImageDecoder, which also plays animated WebP and HEIF.
internal actual fun ComponentRegistry.Builder.addAnimatedImages(): ComponentRegistry.Builder =
    add(if (Build.VERSION.SDK_INT >= 28) AnimatedImageDecoder.Factory() else GifDecoder.Factory())
