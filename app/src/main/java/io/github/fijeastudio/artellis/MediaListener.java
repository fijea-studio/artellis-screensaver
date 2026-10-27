package io.github.fijeastudio.artellis;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so Android lets the screensaver list the TV's media sessions (song and artist). Switched on
 * over ADB by the install script; it reads no notifications.
 */
public class MediaListener extends NotificationListenerService {
}
