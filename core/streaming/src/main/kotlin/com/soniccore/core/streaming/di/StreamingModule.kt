package com.soniccore.core.streaming.di

/**
 * The streaming layer needs no bindings of its own.
 *
 * `CastAudioStreamer` and `AirPlayAudioStreamer` are `@Singleton` classes with
 * `@Inject` constructors, so Hilt constructs them directly. `Context` is already
 * provided by `:core:audio`'s AudioModule — binding it again here caused
 * `[Dagger/DuplicateBindings] android.content.Context is bound multiple times`.
 *
 * Kept as documentation so nobody re-adds a Context provider.
 */
internal object StreamingBindings
