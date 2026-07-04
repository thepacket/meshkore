package org.thepacket.meshcore.protocol

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The MeshCore group-channel cipher (firmware `Utils::encryptThenMAC`/`MACThenDecrypt`),
 * so the packet monitor can decode GRP_TXT/GRP_DATA payloads for channels we hold the key to.
 *
 * A GRP payload is `channel_hash(1) | mac(2) | ciphertext`:
 *  - channel_hash = first byte of SHA-256 over the channel key (16 or 32 bytes as configured)
 *  - mac          = first 2 bytes of HMAC-SHA256 over the ciphertext, keyed with the 32-byte
 *                   secret field (a 128-bit key is zero-padded to 32)
 *  - ciphertext   = AES-128-ECB blocks (plaintext zero-padded to a 16-byte multiple), keyed
 *                   with the first 16 bytes of the secret
 *
 * GRP_TXT plaintext: `timestamp(4 LE) | txt_type(1) | "Sender: text"` (zero padded).
 * GRP_DATA plaintext: `data_type(2 LE) | ...`.
 */
object GroupCipher {

    /** The 1-byte channel hash the firmware derives from a channel key. */
    fun channelHash(secret: ByteArray): Int {
        val key = normalizeKeyLen(secret)
        return MessageDigest.getInstance("SHA-256").digest(key)[0].toInt() and 0xFF
    }

    /**
     * MAC-check then decrypt a GRP payload body (the bytes after the channel-hash byte,
     * i.e. `mac(2) | ciphertext`). Returns the plaintext blocks, or null if the MAC
     * doesn't match (wrong key) or the body is malformed.
     */
    fun decrypt(secret: ByteArray, macAndCiphertext: ByteArray): ByteArray? {
        if (macAndCiphertext.size <= MAC_SIZE) return null
        val ciphertext = macAndCiphertext.copyOfRange(MAC_SIZE, macAndCiphertext.size)
        if (ciphertext.size % 16 != 0) return null

        val padded32 = secret.copyOf(32) // HMAC key: the 32-byte secret field (zero-padded)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(padded32, "HmacSHA256")) }
        val expected = mac.doFinal(ciphertext)
        if (expected[0] != macAndCiphertext[0] || expected[1] != macAndCiphertext[1]) return null

        val aes = Cipher.getInstance("AES/ECB/NoPadding")
        aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret.copyOf(16), "AES"))
        return aes.doFinal(ciphertext)
    }

    /** A decrypted GRP_TXT message. [text] is usually "Sender: message" by client convention. */
    data class GroupText(val timestamp: Long, val txtType: Int, val text: String)

    /** Parse a decrypted GRP_TXT plaintext, or null if too short. */
    fun parseGroupText(plain: ByteArray): GroupText? {
        if (plain.size < 5) return null
        val ts = (plain[0].toLong() and 0xFF) or ((plain[1].toLong() and 0xFF) shl 8) or
            ((plain[2].toLong() and 0xFF) shl 16) or ((plain[3].toLong() and 0xFF) shl 24)
        val type = plain[4].toInt() and 0xFF
        var end = plain.size
        for (j in 5 until plain.size) if (plain[j] == 0.toByte()) { end = j; break }
        return GroupText(ts, type, String(plain, 5, end - 5, Charsets.UTF_8))
    }

    /** Parse a decrypted GRP_DATA plaintext's data type (u16 LE), or null if too short. */
    fun parseGroupDataType(plain: ByteArray): Int? {
        if (plain.size < 2) return null
        return (plain[0].toInt() and 0xFF) or ((plain[1].toInt() and 0xFF) shl 8)
    }

    /** Hash input length matches the configured key size: 16 unless the upper half is used. */
    private fun normalizeKeyLen(secret: ByteArray): ByteArray {
        if (secret.size <= 16) return secret.copyOf(16)
        val upperAllZero = (16 until minOf(secret.size, 32)).all { secret[it] == 0.toByte() }
        return if (upperAllZero) secret.copyOf(16) else secret.copyOf(32)
    }

    private const val MAC_SIZE = 2
}
