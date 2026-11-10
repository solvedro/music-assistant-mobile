package io.music_assistant.sendspin.noise.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.random.CryptographyRandom
import org.bouncycastle.math.ec.rfc7748.X25519

/**
 * The Android platform JCA registers an X25519 `KeyPairGenerator` only from
 * API 33, and this module supports API 28. BouncyCastle's lightweight RFC 7748
 * implementation covers the whole range.
 *
 * This is the lightweight math API, not the `BouncyCastleProvider` JCA bridge:
 * the calls below are direct and static, so R8 keeps the curve arithmetic and
 * strips the rest of the library. The provider bridge registers its algorithms
 * by reflective class name and cannot be shrunk.
 */
internal actual fun defaultX25519Backend(provider: CryptographyProvider): X25519Backend =
    BouncyCastleX25519Backend

internal actual fun defaultAeadBackend(provider: CryptographyProvider): AeadBackend =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        CryptographyKotlinAeadBackend(provider)
    } else {
        BouncyCastleChaCha20Poly1305Backend
    }

private object BouncyCastleChaCha20Poly1305Backend : AeadBackend {
    override suspend fun encrypt(
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val cipher = org.bouncycastle.crypto.modes.ChaCha20Poly1305()
        val keyParam = org.bouncycastle.crypto.params.KeyParameter(key)
        val params = org.bouncycastle.crypto.params.AEADParameters(keyParam, 128, nonce, associatedData)
        cipher.init(true, params)
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        cipher.doFinal(out, len)
        return out
    }

    override suspend fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray? {
        return try {
            val cipher = org.bouncycastle.crypto.modes.ChaCha20Poly1305()
            val keyParam = org.bouncycastle.crypto.params.KeyParameter(key)
            val params = org.bouncycastle.crypto.params.AEADParameters(keyParam, 128, nonce, associatedData)
            cipher.init(false, params)
            val out = ByteArray(cipher.getOutputSize(ciphertext.size))
            val len = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            val finalLen = cipher.doFinal(out, len)
            if (len + finalLen == out.size) {
                out
            } else {
                out.copyOf(len + finalLen)
            }
        } catch (_: Exception) {
            null
        }
    }
}

private object BouncyCastleX25519Backend : X25519Backend {
    override suspend fun generateKeyPair(): X25519KeyPair {
        // CryptographyRandom is the same CSPRNG the rest of NoiseCrypto draws
        // PSKs and nonces from.
        val privateKey = CryptographyRandom.nextBytes(X25519.SCALAR_SIZE)
        X25519.clampPrivateKey(privateKey)
        return X25519KeyPair(privateKey = privateKey, publicKey = publicKey(privateKey))
    }

    override suspend fun publicKey(privateKey: ByteArray): ByteArray =
        ByteArray(X25519.POINT_SIZE).also {
            X25519.generatePublicKey(privateKey, 0, it, 0)
        }

    override suspend fun sharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray =
        ByteArray(X25519.POINT_SIZE).also {
            // False means an all-zero secret: the peer sent a small-order
            // point. RFC 7748 leaves the reaction to the protocol; Noise
            // cannot continue with it, so refuse rather than key off zeros.
            check(X25519.calculateAgreement(privateKey, 0, publicKey, 0, it, 0)) {
                "X25519 agreement produced an all-zero shared secret"
            }
        }
}
