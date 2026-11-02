package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Local addition: exists only so Android lets Home read the active media sessions (what is playing) once the
 * notification access is granted (`cmd notification allow_listener com.gathin.tvhome/.MediaListener`).
 * It reads no notifications itself.
 */
public class MediaListener extends NotificationListenerService {
}
