package com.mototrack.utils

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.MutableLiveData
import com.mototrack.service.MediaListenerService

/**
 * Qué está sonando en el móvil (iVoox, Apple Music, la app de la SER…): lee la sesión multimedia
 * activa que publica cada reproductor. Android solo lo permite con el acceso a notificaciones
 * activado para la app (Ajustes), por eso [accessGranted].
 */
object NowPlaying {
    data class Info(val title: String, val artist: String?, val app: String)

    /** Lo que suena ahora; null si nada suena (en pausa también es null) o no hay acceso. */
    val info = MutableLiveData<Info?>(null)
    val accessGranted = MutableLiveData(false)

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var manager: MediaSessionManager? = null
    private var controllers: List<MediaController> = emptyList()

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { track(it.orEmpty()) }

    fun hasAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Empieza a escuchar (hilo principal). Se puede llamar de nuevo al volver de Ajustes. */
    fun start(context: Context) {
        stop()
        val ctx = context.applicationContext
        appContext = ctx
        val granted = hasAccess(ctx)
        accessGranted.value = granted
        if (!granted) return
        try {
            val mgr = ctx.getSystemService(MediaSessionManager::class.java)
            val listener = ComponentName(ctx, MediaListenerService::class.java)
            mgr.addOnActiveSessionsChangedListener(sessionsListener, listener, main)
            manager = mgr
            track(mgr.getActiveSessions(listener))
        } catch (e: SecurityException) {
            accessGranted.value = false
        }
    }

    fun stop() {
        manager?.removeOnActiveSessionsChangedListener(sessionsListener)
        manager = null
        controllers.forEach { it.unregisterCallback(controllerCallback) }
        controllers = emptyList()
    }

    private fun track(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(controllerCallback) }
        controllers = list
        list.forEach { it.registerCallback(controllerCallback, main) }
        refresh()
    }

    private fun refresh() {
        // Solo la que está sonando; si hay varias, la primera (la más reciente para Android)
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val meta = playing?.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        info.value = if (playing == null || meta == null || title.isNullOrBlank()) null else Info(
            title = title,
            artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            app = appLabel(playing.packageName)
        )
    }

    /**
     * Despliega la barra de notificaciones, donde el sistema muestra los controles de la música.
     * No hay API pública: se llama por reflexión a StatusBarManager (permiso EXPAND_STATUS_BAR).
     * Devuelve false si esta versión de Android no lo permite.
     */
    @android.annotation.SuppressLint("WrongConstant", "PrivateApi")
    fun expandNotificationShade(context: Context): Boolean = try {
        val bar = context.getSystemService("statusbar")
        Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(bar)
        true
    } catch (e: Exception) {
        false
    }

    private fun appLabel(pkg: String): String {
        val pm = appContext?.packageManager
        return try {
            pm?.getApplicationLabel(pm.getApplicationInfo(pkg, 0))?.toString() ?: pkg
        } catch (e: Exception) {
            pkg   // sin visibilidad sobre esa app: mejor el nombre del paquete que nada
        }
    }
}
