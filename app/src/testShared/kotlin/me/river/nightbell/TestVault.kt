package me.river.nightbell

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import me.river.nightbell.data.auth.CredentialVault
import me.river.nightbell.data.auth.VaultCipher
import me.river.nightbell.data.auth.VaultStorage

/** The vault's storage as a map, for tests that are not about where it lives. */
class MemoryVaultStorage : VaultStorage {
    val map = ConcurrentHashMap<String, String>()

    override suspend fun all(): Map<String, String> = HashMap(map)

    override suspend fun edit(transform: (MutableMap<String, String>) -> Unit) {
        synchronized(map) {
            val copy = HashMap(map)
            transform(copy)
            map.clear()
            map.putAll(copy)
        }
    }
}

/**
 * Not encryption, and not meant to be: the JVM has no AndroidKeyStore. It
 * changes the bytes so a test can tell sealed from plain, and [broken] stands in
 * for a key that has gone, which is the path the vault has to survive.
 */
class FakeCipher : VaultCipher {
    @Volatile
    var broken = false

    override fun seal(plain: ByteArray): ByteArray = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

    override fun open(sealed: ByteArray): ByteArray {
        if (broken) error("key gone")
        return sealed.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }
}

fun testVault(cipher: FakeCipher = FakeCipher(), storage: MemoryVaultStorage = MemoryVaultStorage()) =
    CredentialVault(storage, cipher)

/**
 * A JWT-shaped string with the claims given, and a signature nobody checks.
 * Synthetic on purpose: no real token goes into a fixture, ever.
 */
fun syntheticJwt(claimsJson: String): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val header = encoder.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
    val payload = encoder.encodeToString(claimsJson.toByteArray())
    return "$header.$payload.c3ludGhldGljLXNpZ25hdHVyZQ"
}
