package space.sovereigncast.hopp.ui

import coil3.ComponentRegistry

/**
 * Decoders that play animated images — chapter images are often GIFs (device
 * feedback 2026-10-14: Re:Set's did not move). Coil's own decoders show the
 * first frame at most; Android has coil-gif, the other platforms nothing yet.
 */
internal expect fun ComponentRegistry.Builder.addAnimatedImages(): ComponentRegistry.Builder
