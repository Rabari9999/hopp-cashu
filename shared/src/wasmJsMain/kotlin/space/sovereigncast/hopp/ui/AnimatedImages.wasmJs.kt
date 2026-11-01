package space.sovereigncast.hopp.ui

import coil3.ComponentRegistry

// No animated-image decoder for Coil on the web yet: the first frame, as on the JVM.
internal actual fun ComponentRegistry.Builder.addAnimatedImages(): ComponentRegistry.Builder = this
