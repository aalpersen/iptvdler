package io.github.aalpersen.iptvdler.core.crypto

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

actual fun createAes128CbcDecryptor(): Aes128CbcDecryptor = JavaxAes128CbcDecryptor

private object JavaxAes128CbcDecryptor : Aes128CbcDecryptor {
    override fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray, pkcs7Padding: Boolean): ByteArray {
        if (key.size != 16) throw DecryptionException("AES-128 key must be 16 bytes, got ${key.size}")
        if (iv.size != 16) throw DecryptionException("IV must be 16 bytes, got ${iv.size}")
        return try {
            val cipher = Cipher.getInstance(if (pkcs7Padding) "AES/CBC/PKCS5Padding" else "AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(data)
        } catch (e: java.security.GeneralSecurityException) {
            throw DecryptionException("Decryption failed (wrong key or corrupt segment)", e)
        }
    }
}
