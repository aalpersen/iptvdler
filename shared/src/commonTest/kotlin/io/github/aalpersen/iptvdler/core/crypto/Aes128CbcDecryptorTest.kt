package io.github.aalpersen.iptvdler.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class Aes128CbcDecryptorTest {

    private val aes = createAes128CbcDecryptor()

    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** NIST SP 800-38A, F.2.2 CBC-AES128.Decrypt (no padding). */
    @Test
    fun nistCbcAes128Vector() {
        val key = hex("2b7e151628aed2a6abf7158809cf4f3c")
        val iv = hex("000102030405060708090a0b0c0d0e0f")
        val ciphertext = hex(
            "7649abac8119b246cee98e9b12e9197d 5086cb9b507219ee95db113a917678b2" +
                "73bed6b8e3c1743b7116e69e22229516 3ff1caa1681fac09120eca307586e1a7",
        )
        val plaintext = hex(
            "6bc1bee22e409f96e93d7e117393172a ae2d8a571e03ac9c9eb76fac45af8e51" +
                "30c81c46a35ce411e5fbc1191a0a52ef f69f2445df4f9b17ad2b417be66c3710",
        )
        assertContentEquals(plaintext, aes.decrypt(ciphertext, key, iv, pkcs7Padding = false))
    }

    /**
     * `printf 'iptvdler AES-128 test' | openssl enc -aes-128-cbc -K 000102030405060708090a0b0c0d0e0f
     *  -iv 0f0e0d0c0b0a09080706050403020100` — PKCS#7 padded like an HLS segment.
     */
    @Test
    fun pkcs7PaddedOpensslVector() {
        val decrypted = aes.decrypt(
            data = hex("f96b0fa804feb3f9f1412678553339ea76ed2c3ea8399f8418ee75554c76d676"),
            key = hex("000102030405060708090a0b0c0d0e0f"),
            iv = hex("0f0e0d0c0b0a09080706050403020100"),
        )
        assertContentEquals("iptvdler AES-128 test".encodeToByteArray(), decrypted)
    }

    /** Block-aligned plaintext gets a full padding block; IV = media sequence 7794 (as HLS derives it). */
    @Test
    fun fullPaddingBlockWithSequenceDerivedIv() {
        val decrypted = aes.decrypt(
            data = hex("35c2c02aa90d16ab9328c4f94898fc5375fca293e4dd3f7f1244480cbab81d61"),
            key = hex("000102030405060708090a0b0c0d0e0f"),
            iv = io.github.aalpersen.iptvdler.core.hls.HlsIv.fromSequence(7794),
        )
        assertContentEquals("0123456789abcdef".encodeToByteArray(), decrypted)
    }

    @Test
    fun rejectsWrongKeyAndIvSizes() {
        assertFailsWith<DecryptionException> { aes.decrypt(ByteArray(16), ByteArray(8), ByteArray(16)) }
        assertFailsWith<DecryptionException> { aes.decrypt(ByteArray(16), ByteArray(16), ByteArray(15)) }
    }

    @Test
    fun corruptPaddingIsReportedAsDecryptionException() {
        // Decrypting random bytes with PKCS#7 almost always yields invalid padding.
        assertFailsWith<DecryptionException> {
            aes.decrypt(ByteArray(32) { (it * 7).toByte() }, ByteArray(16) { 1 }, ByteArray(16))
        }
    }
}
