package com.soniccore.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Exists purely to unlock `MediaSessionManager.getActiveSessions`, which Android
 * only grants to an enabled notification listener. SonicCore reads media session
 * metadata and volume — notification content is deliberately ignored.
 */
class SonicNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Intentionally empty: we never inspect notification content.
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Intentionally empty.
    }
}
