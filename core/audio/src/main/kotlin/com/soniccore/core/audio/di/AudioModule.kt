package com.soniccore.core.audio.di

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import androidx.core.content.ContextCompat
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/** The app's NotificationListenerService component, needed for MediaSessionManager. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NotificationListener

@Module
@InstallIn(SingletonComponent::class)
object AudioModule {

    @Provides
    @Singleton
    fun provideAudioManager(@ApplicationContext context: Context): AudioManager =
        ContextCompat.getSystemService(context, AudioManager::class.java)
            ?: error("AudioManager unavailable — the device has no audio service")

    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context = context

    /**
     * Points at the app module's listener service by name. Declared here so
     * :core:audio does not need a compile dependency on :app.
     */
    @Provides
    @Singleton
    @NotificationListener
    fun provideNotificationListenerComponent(@ApplicationContext context: Context): ComponentName =
        ComponentName(context, NOTIFICATION_LISTENER_CLASS)

    const val NOTIFICATION_LISTENER_CLASS =
        "com.soniccore.service.SonicNotificationListenerService"
}
