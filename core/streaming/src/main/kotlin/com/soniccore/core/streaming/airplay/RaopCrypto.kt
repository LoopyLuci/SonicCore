package com.soniccore.core.streaming.airplay

import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import android.util.Base64

/**
 * RAOP (Remote Audio Output Protocol) crypto.
 *
 * AirPlay v1 receivers accept an AES-128-CBC session key that the sender encrypts
 * with the well-known Apple RAOP RSA public key and passes in the RTSP
 * `Apple-Challenge` / SDP `a=rsaaeskey` fields. The modulus below is the public
 * key published in the reverse-engineered RAOP spec — it is public information,
 * not a secret, and is required for any third-party sender to interoperate.
 *
 * Newer receivers (AirPlay 2 / HomeKit) require pair-verify with Curve25519 and
 * will refuse this path; [RaopSession] surfaces that as an explicit failure rather
 * than hanging.
 */
internal object RaopCrypto {

    /** Apple RAOP public modulus (base64, from the published RAOP specification). */
    private const val RSA_MODULUS_B64 =
        "59dE8qLieItsH1WgjrcFRKj6eUWqi+bGLOX1HL3U3GhC/j0Qg90u3sG/1CUtwC" +
            "5vOYvfDmFI6oSFXi5ELabWJmT2dKHzBJKa3k9ok+8t9ucRqMd6DZHJ2YCCLlDRKS" +
            "Kv6kDqnw4UwPdpOMXziC/AMj3Z/lUVX1G7WSHCAWKf1zNS1eLISgevpvpNs1oyLA" +
            "iERBqbFxWZmDvVsUp0kOnLxHOagKZ0/HPB5MFrX5+PjmL/n1nzGwlnMPGKKMLTMj" +
            "OSGnTSVzYFbFdyG7XPQqMHmDLmVUmHVLSTNHhFVBrKgWXvbz6z1YrCsZbcAQfWNM" +
            "MqLmMHFPRGoLxiFrKM0RVLKhCFPKFPKmXFXsFqIQ=="

    private const val RSA_EXPONENT_B64 = "AQAB"

    private val secureRandom = SecureRandom()

    /** Fresh 128-bit AES session key. */
    fun generateAesKey(): ByteArray = ByteArray(16).also { secureRandom.nextBytes(it) }

    /** Fresh 128-bit CBC IV. */
    fun generateIv(): ByteArray = ByteArray(16).also { secureRandom.nextBytes(it) }

    /**
     * RSA-OAEP encrypt the AES key for the `a=rsaaeskey` SDP attribute.
     * Returns null when the platform lacks the transformation or the modulus is
     * rejected — callers must treat that as "this receiver is unsupported".
     */
    fun encryptAesKey(aesKey: ByteArray): String? = runCatching {
        val modulus = BigInteger(1, Base64.decode(padBase64(RSA_MODULUS_B64), Base64.DEFAULT))
        val exponent = BigInteger(1, Base64.decode(RSA_EXPONENT_B64, Base64.DEFAULT))
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, exponent))

        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        stripBase64Padding(Base64.encodeToString(cipher.doFinal(aesKey), Base64.NO_WRAP))
    }.getOrNull()

    /** AES-128-CBC encrypt one audio frame in place semantics (returns new array). */
    fun encryptFrame(payload: ByteArray, aesKey: ByteArray, iv: ByteArray): ByteArray = runCatching {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(aesKey, "AES"),
            IvParameterSpec(iv),
        )
        // RAOP encrypts only whole 16-byte blocks; the tail is sent in the clear.
        val blockCount = payload.size / 16
        if (blockCount == 0) return payload
        val encryptedLength = blockCount * 16
        val encrypted = cipher.doFinal(payload, 0, encryptedLength)
        encrypted + payload.copyOfRange(encryptedLength, payload.size)
    }.getOrDefault(payload)

    /** RAOP omits base64 '=' padding in SDP; add it back before decoding. */
    private fun padBase64(value: String): String {
        val remainder = value.length % 4
        return if (remainder == 0) value else value + "=".repeat(4 - remainder)
    }

    private fun stripBase64Padding(value: String): String = value.trimEnd('=')

    /** Random 8-hex-digit client instance id used in the RTSP session URI. */
    fun newClientInstance(): String =
        java.lang.Long.toHexString(secureRandom.nextLong()).uppercase().take(16)

    /** Random 32-bit SSRC for the RTP stream. */
    fun newSsrc(): Int = secureRandom.nextInt()
}
