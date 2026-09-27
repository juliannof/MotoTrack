package com.mototrack.service

import android.service.notification.NotificationListenerService

/**
 * No procesa notificaciones: existe solo porque Android exige tener activado el "acceso a
 * notificaciones" para dejar leer las sesiones multimedia de otras apps (ver [com.mototrack.utils.NowPlaying]).
 */
class MediaListenerService : NotificationListenerService()
