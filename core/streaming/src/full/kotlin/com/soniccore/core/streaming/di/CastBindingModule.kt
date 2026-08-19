package com.soniccore.core.streaming.di

import android.content.Context
import com.soniccore.core.streaming.CastStreamer
import com.soniccore.core.streaming.cast.CastAudioStreamer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Full flavor Cast binding — the real Google Cast SDK implementation.
 *
 * Only this flavor pulls in `play-services-cast-framework`, keeping the FOSS/F-Droid
 * build free of proprietary dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object CastBindingModule {

    @Provides
    @Singleton
    fun provideCastStreamer(
        @ApplicationContext context: Context,
    ): CastStreamer = CastAudioStreamer(context)
}
