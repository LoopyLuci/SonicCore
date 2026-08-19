package com.soniccore.core.streaming.di

import com.soniccore.core.streaming.CastStreamer
import com.soniccore.core.streaming.NoOpCastStreamer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * FOSS flavor Cast binding.
 *
 * Supplies the no-op implementation so the F-Droid build contains no reference to
 * `com.google.android.gms`. The `full` flavor provides the SDK-backed streamer from the
 * same module path, so nothing above the [CastStreamer] interface changes.
 */
@Module
@InstallIn(SingletonComponent::class)
object CastBindingModule {

    @Provides
    @Singleton
    fun provideCastStreamer(): CastStreamer = NoOpCastStreamer()
}
