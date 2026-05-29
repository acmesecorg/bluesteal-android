package org.crainiate.bluesteal.crypto.cable

import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.util.BigIntegers
import org.crainiate.bluesteal.crypto.Hkdf
import java.math.BigInteger
import java.security.SecureRandom

/**
 * A P-256 keypair held as a private scalar plus its public point. Mirrors the
 * role of CryptoKit's `P256.KeyAgreement.PrivateKey` in the macOS port.
 */
class CableKey internal constructor(
    internal val scalar: BigInteger,
    internal val point: ECPoint
) {
    /** Uncompressed X9.62 public key: `0x04 || X || Y`, 65 bytes. */
    val publicKeyX962: ByteArray by lazy { point.getEncoded(false) }
}

/**
 * P-256 ECDH helpers for caBLE Noise, byte-compatible with the macOS `CableP256`
 * (CryptoKit) and Windows `P256.cs` (.NET). Uses BouncyCastle's lightweight EC
 * math directly — no JCE provider registration. The only non-trivial piece is
 * [deriveKeyFromSeed], which replicates BoringSSL's `EC_KEY_derive_from_secret`
 * (HKDF → 48-byte big-endian integer → reduce mod the P-256 group order).
 */
object CableP256 {
    const val X962_LENGTH = 65

    private val x9: X9ECParameters = CustomNamedCurves.getByName("secp256r1")
    private val n: BigInteger = x9.n
    private val rng = SecureRandom()

    fun deriveKeyFromSeed(seed: ByteArray): CableKey {
        require(seed.size == 32) { "seed must be 32 bytes" }
        val salt = "EC_KEY_derive_from_secret".toByteArray(Charsets.UTF_8)
        val hkdf = Hkdf.derive(ikm = seed, salt = salt, info = ByteArray(0), length = 48)
        var scalar = BigInteger(1, hkdf).mod(n)
        if (scalar.signum() == 0) scalar = BigInteger.ONE
        return keyFromScalar(scalar)
    }

    fun generateEphemeral(): CableKey {
        var d: BigInteger
        do {
            d = BigInteger(n.bitLength(), rng)
        } while (d < BigInteger.ONE || d >= n)
        return keyFromScalar(d)
    }

    fun importPublicKeyX962(x962: ByteArray): ECPoint = x9.curve.decodePoint(x962).normalize()

    /** Raw ECDH shared secret: the 32-byte big-endian X coordinate. */
    fun computeSharedSecret(priv: CableKey, peerPoint: ECPoint): ByteArray {
        val shared = peerPoint.multiply(priv.scalar).normalize()
        return BigIntegers.asUnsignedByteArray(32, shared.affineXCoord.toBigInteger())
    }

    fun computeSharedSecret(priv: CableKey, peerX962: ByteArray): ByteArray =
        computeSharedSecret(priv, importPublicKeyX962(peerX962))

    private fun keyFromScalar(scalar: BigInteger): CableKey {
        val point = x9.g.multiply(scalar).normalize()
        return CableKey(scalar, point)
    }
}
