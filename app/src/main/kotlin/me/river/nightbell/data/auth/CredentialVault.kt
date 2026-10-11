package me.river.nightbell.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.river.nightbell.data.diag.Diag
import me.river.nightbell.domain.LogEvent
import me.river.nightbell.domain.LogField

/**
 * One monitor's sign-in secrets. Lives only in the [CredentialVault].
 *
 * Expiry is the phone's clock at the moment the provider answered plus what the
 * provider said, never a JWT's `exp`: the provider's clock and the phone's can
 * disagree by minutes, and a token judged by the wrong one is refused mid-check.
 */
@Serializable
data class VaultEntry(
    val accessToken: String = "",
    val accessExpiresAt: Long = 0L,
    val refreshToken: String = "",
    /** 0 when the provider did not say, which for an offline token usually means never. */
    val refreshExpiresAt: Long = 0L,
    val password: String = "",
    val clientSecret: String = "",
    /**
     * Fingerprint of a password or client secret the provider has refused.
     *
     * A refused credential is tried once per change and never on a schedule,
     * because a provider with brute force detection on locks the account after
     * thirty failures and a monitor checking every minute gets there in half an
     * hour. Editing the credential changes its fingerprint, which is what lets
     * the next attempt through.
     */
    val refusedFingerprint: String = "",
    /** The token endpoint found by rediscovery after the stored one answered 404. */
    val tokenEndpoint: String = "",
    /** The refresh token is an offline one. */
    val offline: Boolean = false,
    /** When the tokens were last refreshed, for the detail screen and the diagnostic log. */
    val refreshedAt: Long = 0L,
) {
    val hasTokens: Boolean get() = accessToken.isNotBlank() || refreshToken.isNotBlank()

    /** Every value here that would be a leak in a log line. */
    val secrets: List<String> get() = listOf(accessToken, refreshToken, password, clientSecret)

    fun withoutTokens(): VaultEntry = copy(
        accessToken = "",
        accessExpiresAt = 0L,
        refreshToken = "",
        refreshExpiresAt = 0L,
        offline = false,
    )
}

/** Seals and opens vault values. The real one is [KeystoreCipher]; tests pass their own. */
interface VaultCipher {
    fun seal(plain: ByteArray): ByteArray

    /** Throws when the value cannot be opened, which the vault reads as "not signed in". */
    fun open(sealed: ByteArray): ByteArray
}

/** Where the sealed values sit. A DataStore file on the phone, a map in a test. */
interface VaultStorage {
    suspend fun all(): Map<String, String>

    suspend fun edit(transform: (MutableMap<String, String>) -> Unit)
}

/**
 * Sign-in secrets for health monitors, by monitor id, outside the snapshot.
 *
 * Outside it for three reasons, each of which was a way to lose or leak a
 * token. Setup saves the whole draft over the monitor, so a token rotated by
 * the worker while setup was open would be overwritten with the old one, and
 * with rotation on the old one no longer works. Backups are built from the
 * snapshot, and a token in a backup file is a login in a backup file. And the
 * diagnostic runtime export reads the snapshot too.
 *
 * Encrypted with a key that needs no user authentication, because checks run
 * with the screen locked. That makes it defence against an imaged or rooted
 * phone rather than against somebody holding it with the screen on, which is the
 * threat it is for: an offline token never expires while it is used and these
 * open admin APIs.
 */
class CredentialVault(
    private val storage: VaultStorage,
    private val cipher: VaultCipher,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** Decrypted values, kept for the log redactor, which cannot suspend. */
    private val known = ConcurrentHashMap<String, List<String>>()

    sealed interface Read {
        data class Found(val entry: VaultEntry) : Read

        data object Empty : Read

        /** Held something that would not open: a lost key after a restore, or a Keystore fault. */
        data object Unreadable : Read
    }

    suspend fun read(monitorId: String): Read {
        val sealed = storage.all()[monitorId] ?: return Read.Empty
        val entry = runCatching {
            val bytes = cipher.open(Base64.getDecoder().decode(sealed))
            json.decodeFromString<VaultEntry>(String(bytes, Charsets.UTF_8))
        }.getOrElse { error ->
            Diag.logError(LogEvent.VAULT_UNREADABLE, error, LogField.monitor(monitorId))
            known.remove(monitorId)
            return Read.Unreadable
        }
        known[monitorId] = entry.secrets.filter { it.isNotBlank() }
        return Read.Found(entry)
    }

    /** The entry, or a blank one when there is none or it would not open. */
    suspend fun entry(monitorId: String): VaultEntry = (read(monitorId) as? Read.Found)?.entry ?: VaultEntry()

    suspend fun write(monitorId: String, entry: VaultEntry) {
        val sealed = Base64.getEncoder().encodeToString(
            cipher.seal(json.encodeToString(VaultEntry.serializer(), entry).toByteArray(Charsets.UTF_8)),
        )
        storage.edit { it[monitorId] = sealed }
        known[monitorId] = entry.secrets.filter { it.isNotBlank() }
    }

    suspend fun delete(monitorId: String) {
        storage.edit { it.remove(monitorId) }
        known.remove(monitorId)
    }

    /**
     * Drops entries whose monitor is gone, the way the store prunes groups.
     * Deletes and imports come in through more than one door.
     */
    suspend fun prune(keep: Set<String>) {
        val gone = storage.all().keys - keep
        if (gone.isEmpty()) return
        storage.edit { map -> gone.forEach { map.remove(it) } }
        gone.forEach { known.remove(it) }
        Diag.log(LogEvent.STORE_REPAIR, LogField.tag("what", "vault_pruned"), LogField.count("entries", gone.size))
    }

    /** Every secret read or written in this process, for [me.river.nightbell.domain.LogRedactor.replaceKnown]. */
    fun knownSecrets(): List<String> = known.values.flatten()

    companion object {
        /** A fingerprint that says whether a credential changed, without keeping it twice. */
        fun fingerprint(secret: String): String =
            if (secret.isBlank()) "" else me.river.nightbell.domain.LogRedactor.fingerprint(secret)
    }
}

/**
 * AES-256-GCM with a key that never leaves AndroidKeyStore.
 *
 * The platform API directly. androidx.security-crypto would have done the same
 * in fewer lines and is deprecated, and this app takes no dependency it does not
 * need. The twelve byte IV the cipher picks is stored in front of each value.
 */
class KeystoreCipher(private val alias: String = "nightbell_vault") : VaultCipher {

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Off, and it has to be: checks run with the screen locked.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES) { "sealed value too short" }
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        // Never generated here: a missing key means everything sealed under the
        // old one is gone, and a fresh key would not open it either.
        val key = (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: error("vault key missing")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    /** For the instrumented test of the key-lost path. */
    fun deleteKey() {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(alias)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

private val Context.vaultDataStore: DataStore<Preferences> by preferencesDataStore(name = "nightbell_vault")

/** The vault's own DataStore file, separate from the snapshot's. */
class DataStoreVaultStorage(private val context: Context) : VaultStorage {
    override suspend fun all(): Map<String, String> =
        context.vaultDataStore.data.first().asMap().entries
            .mapNotNull { (key, value) -> (value as? String)?.let { key.name to it } }
            .toMap()

    override suspend fun edit(transform: (MutableMap<String, String>) -> Unit) {
        context.vaultDataStore.edit { prefs ->
            val current = prefs.asMap().entries
                .mapNotNull { (key, value) -> (value as? String)?.let { key.name to it } }
                .toMap()
            val next = current.toMutableMap().also(transform)
            (current.keys - next.keys).forEach { prefs.remove(stringPreferencesKey(it)) }
            next.forEach { (key, value) -> if (current[key] != value) prefs[stringPreferencesKey(key)] = value }
        }
    }
}
