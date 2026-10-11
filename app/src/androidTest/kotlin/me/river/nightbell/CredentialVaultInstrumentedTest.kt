package me.river.nightbell

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.river.nightbell.data.auth.CredentialVault
import me.river.nightbell.data.auth.KeystoreCipher
import me.river.nightbell.data.auth.VaultEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The vault on a real AndroidKeyStore. Its own key alias and an in-memory
 * store, so nothing here touches the app's real sign-ins.
 */
@RunWith(AndroidJUnit4::class)
class CredentialVaultInstrumentedTest {

    private val alias = "nightbell_vault_test"
    private val storage = MemoryVaultStorage()

    @After
    fun tearDown() {
        KeystoreCipher(alias).deleteKey()
    }

    @Test
    fun aValueSealedOnePlaceOpensInAnother() = runBlocking {
        CredentialVault(storage, KeystoreCipher(alias)).write("m", VaultEntry(refreshToken = "synthetic-refresh", password = "pw"))

        // A second vault and a second cipher over the same storage: what a new
        // process after a restart would have.
        val read = CredentialVault(storage, KeystoreCipher(alias)).read("m")

        assertEquals("synthetic-refresh", (read as CredentialVault.Read.Found).entry.refreshToken)
        assertFalse(storage.map.getValue("m").contains("synthetic-refresh"))
    }

    @Test
    fun aLostKeyReadsAsSignedOut() = runBlocking {
        CredentialVault(storage, KeystoreCipher(alias)).write("m", VaultEntry(refreshToken = "synthetic-refresh"))
        KeystoreCipher(alias).deleteKey()

        val read = CredentialVault(storage, KeystoreCipher(alias)).read("m")

        assertTrue(read is CredentialVault.Read.Unreadable)
    }

    @Test
    fun pruningDropsOnlyEntriesWhoseMonitorIsGone() = runBlocking {
        val vault = CredentialVault(storage, KeystoreCipher(alias))
        vault.write("keep", VaultEntry(password = "a"))
        vault.write("gone", VaultEntry(password = "b"))

        vault.prune(setOf("keep"))

        assertEquals(setOf("keep"), storage.map.keys)
    }
}
