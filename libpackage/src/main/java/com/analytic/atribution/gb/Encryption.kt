package com.analytic.atribution.gb

import android.os.Build
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object Encryption {
    private val keyBytes: ByteArray =
        "5X<#)kRN+)S-2=9A<T,oQk4BUP?ACPVk".reversed().toByteArray(Charsets.UTF_8)

    fun encrypt(message: String): String {
        val messageBytes: ByteArray = message.toByteArray(Charsets.UTF_8)

        val iv: ByteArray = ByteArray(16)
        val ivParameterSpec: IvParameterSpec = IvParameterSpec(iv)

        val cipher: Cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), ivParameterSpec)

        val encryptedMessageBytes: ByteArray = cipher.doFinal(messageBytes)
        val encryptedMessage: String = if (Build.VERSION.SDK_INT >= 26) {
            Base64.getEncoder().encodeToString(iv + encryptedMessageBytes)
        } else {
            android.util.Base64.encodeToString(
                iv + encryptedMessageBytes,
                android.util.Base64.NO_WRAP
            )
        }
        return encryptedMessage
    }

    fun decrypt(payload: String): String {
        val raw: ByteArray = if (Build.VERSION.SDK_INT >= 26) {
            Base64.getDecoder().decode(payload)
        } else {
            android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
        }
        val iv: ByteArray = raw.copyOfRange(0, 16)
        val ciphertext: ByteArray = raw.copyOfRange(16, raw.size)

        val cipher: Cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))

        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
}
