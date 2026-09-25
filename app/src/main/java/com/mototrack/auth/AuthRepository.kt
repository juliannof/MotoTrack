package com.mototrack.auth

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Autenticación local sencilla: cuentas guardadas en el dispositivo con la
 * contraseña como hash PBKDF2 con sal. Sirve para tener el flujo de acceso
 * montado; cuando llegue el login con Google (y la nube) este es el único
 * sitio a sustituir: la UI solo usa [currentUser], [register], [login] y [logout].
 *
 * Los métodos devuelven un mensaje de error para mostrar, o null si fue bien.
 */
class AuthRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("mototrack_auth", Context.MODE_PRIVATE)

    fun currentUser(): String? = prefs.getString(KEY_SESSION, null)

    fun register(email: String, password: String): String? {
        val id = normalize(email)
        if (!EMAIL_REGEX.matches(id)) return "Correo no válido"
        if (password.length < MIN_PASSWORD) return "La contraseña necesita al menos $MIN_PASSWORD caracteres"
        if (prefs.contains(userKey(id))) return "Ya existe una cuenta con ese correo"

        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(userKey(id), encode(salt) + ":" + encode(hash(password, salt)))
            .putString(KEY_SESSION, id)
            .apply()
        return null
    }

    fun login(email: String, password: String): String? {
        val id = normalize(email)
        val stored = prefs.getString(userKey(id), null)?.split(":")
        // Mismo mensaje si no existe o si la contraseña falla: no revela qué correos hay
        val error = "Correo o contraseña incorrectos"
        if (stored == null || stored.size != 2) return error
        val expected = decode(stored[1])
        if (!MessageDigest.isEqual(expected, hash(password, decode(stored[0])))) return error

        prefs.edit().putString(KEY_SESSION, id).apply()
        return null
    }

    /** Sesión iniciada con Google; el token lo ha validado Google Play services en el dispositivo. */
    fun startGoogleSession(email: String) {
        prefs.edit().putString(KEY_SESSION, normalize(email)).apply()
    }

    fun logout() {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    private fun normalize(email: String) = email.trim().lowercase()
    private fun userKey(id: String) = "user_$id"

    private fun hash(password: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256))
            .encoded

    private fun encode(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun decode(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private companion object {
        const val KEY_SESSION = "session_email"
        const val MIN_PASSWORD = 6
        const val ITERATIONS = 120_000
        val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
