package com.soniccore.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions

/**
 * Cast SDK entry point. `CastContext.getSharedInstance()` throws unless this class
 * is named in the manifest via the `CAST_OPTIONS_PROVIDER_CLASS_NAME` meta-data —
 * that missing declaration is the single most common cause of Cast "not working".
 *
 * Uses the default media receiver so any Chromecast/Android TV target works without
 * a registered custom receiver app.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions {
        val notificationOptions = NotificationOptions.Builder()
            .setTargetActivityClassName(com.soniccore.MainActivity::class.java.name)
            .build()

        val mediaOptions = CastMediaOptions.Builder()
            .setNotificationOptions(notificationOptions)
            .build()

        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(mediaOptions)
            .setStopReceiverApplicationWhenEndingSession(true)
            // Let the SDK resume a session if the app is relaunched mid-cast.
            .setResumeSavedSession(true)
            .setEnableReconnectionService(true)
            .build()
    }

    /** No custom session types beyond Cast. */
    override fun getAdditionalSessionProviders(context: Context): MutableList<SessionProvider>? = null
}
