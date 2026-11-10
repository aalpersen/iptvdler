package io.github.aalpersen.iptvdler.core.crypto

/** AES-128-CBC decryption as used by HLS `METHOD=AES-128` (whole segment, PKCS#7 padding). */
interface Aes128CbcDecryptor {
    /**
     * @param key 16 bytes
     * @param iv 16 bytes
     * @param pkcs7Padding remove PKCS#7 padding (true for HLS segments)
     */
    fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray, pkcs7Padding: Boolean = true): ByteArray
}

expect fun createAes128CbcDecryptor(): Aes128CbcDecryptor

class DecryptionException(message: String, cause: Throwable? = null) : Exception(message, cause)
