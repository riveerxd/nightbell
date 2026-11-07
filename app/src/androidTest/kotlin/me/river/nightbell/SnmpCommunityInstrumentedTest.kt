package me.river.nightbell

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.awaitTrue
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.check.MailChecker
import me.river.nightbell.domain.MailWatch
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.UdpProbe
import me.river.nightbell.domain.Validation
import me.river.nightbell.ui.SetupViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The SNMP community once shared the mailbox password's vault field. A mailbox
 * switched to an SNMP probe passed the community check with the IMAP password,
 * and an IMAP password typed into an abandoned edit of an SNMP monitor became
 * its community: both sent in clear to whatever host was set. Driven on the
 * real view model and the real vault.
 */
@RunWith(AndroidJUnit4::class)
class SnmpCommunityInstrumentedTest {

    private val graph get() = Nightbell.install(appContext)
    private val main = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setUp() {
        NightbellTestSupport.resetApp()
    }

    @After
    fun tearDown() {
        runBlocking {
            graph.store.currentSnapshot().monitors.forEach { graph.vault.delete(it.id) }
            graph.vault.delete(MAILBOX_ID)
            graph.vault.delete(SNMP_ID)
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var out: Result<T>? = null
        main.runOnMainSync { out = runCatching(block) }
        return out!!.getOrThrow()
    }

    private fun stored(id: String) = runBlocking { graph.mailCredentials.password(id) }

    private fun community(id: String) = runBlocking { graph.mailCredentials.community(id) }

    private fun seededMailbox(): SetupViewModel {
        runBlocking {
            graph.mailCredentials.save(MAILBOX_ID, "imap-secret")
            graph.store.upsert(
                Monitor(
                    id = MAILBOX_ID,
                    name = "Work inbox",
                    kind = MonitorKind.MAILBOX,
                    mail = MailWatch(host = "127.0.0.1", port = 993, username = "me@example.com"),
                ),
            )
        }
        val vm = onMain { SetupViewModel(graph, MAILBOX_ID) }
        awaitTrue(description = "the mailbox loaded with its password held") { !vm.loading && vm.mailPasswordHeld }
        return vm
    }

    @Test
    fun aMailboxSwitchedToSnmpNeedsItsOwnCommunity() {
        val vm = seededMailbox()
        onMain {
            vm.setKind(MonitorKind.UDP_PORT)
            vm.update { it.copy(network = it.network.copy(host = "127.0.0.1")) }
            vm.setUdpProbe(UdpProbe.SNMP)
        }
        // Give a stray vault read the time it would take to come back.
        Thread.sleep(500)
        assertFalse("the IMAP password counted as the community", vm.mailPasswordHeld)
        assertNotNull(vm.report.of(Validation.Field.COMMUNITY))
        assertFalse(vm.report.isValid)

        onMain {
            vm.setMailPassword("public")
            vm.save()
        }
        awaitTrue(description = "saved") { vm.saved }
        assertEquals("public", community(MAILBOX_ID))
        assertEquals("the IMAP password outlived the mailbox", MailChecker.Stored.None, stored(MAILBOX_ID))
    }

    @Test
    fun anImapPasswordTypedIntoAnAbandonedEditNeverBecomesTheCommunity() {
        runBlocking {
            graph.mailCredentials.saveCommunity(SNMP_ID, "public")
            graph.store.upsert(
                Monitor(
                    id = SNMP_ID,
                    name = "Switch",
                    kind = MonitorKind.UDP_PORT,
                    network = me.river.nightbell.domain.NetworkWatch(host = "127.0.0.1", port = 161),
                    udp = me.river.nightbell.domain.UdpWatch(probe = UdpProbe.SNMP),
                ),
            )
        }
        val owner = ViewModelStore()
        val vm = onMain {
            ViewModelProvider(owner, viewModelFactory { initializer { SetupViewModel(graph, SNMP_ID) } })[SetupViewModel::class.java]
        }
        awaitTrue(description = "the community held") { !vm.loading && vm.mailPasswordHeld }
        onMain {
            vm.setKind(MonitorKind.MAILBOX)
            vm.updateMail { it.copy(host = "127.0.0.1", port = 9, username = "me@example.com") }
            vm.setMailPassword("imap-secret")
            // Signs in for the folder list, which stores the password first.
            vm.loadMailFolders()
        }
        awaitTrue(description = "the IMAP password reached the vault") { stored(SNMP_ID) is MailChecker.Stored.Password }
        awaitTrue(timeoutMs = 30_000, description = "the folder list gave up") { !vm.loadingMailFolders }
        onMain { owner.clear() }
        assertEquals(MonitorKind.UDP_PORT, runBlocking { graph.store.currentSnapshot().monitors.single().kind })
        assertEquals("the next check would send this", "public", community(SNMP_ID))
    }

    @Test
    fun aPasswordTypedForAMailboxIsNotCarriedIntoSnmp() {
        val vm = onMain { SetupViewModel(graph, null) }
        onMain {
            vm.setKind(MonitorKind.MAILBOX)
            vm.setMailPassword("imap-secret")
            vm.setKind(MonitorKind.UDP_PORT)
            vm.setUdpProbe(UdpProbe.SNMP)
        }
        assertEquals("", vm.mailPasswordInput)
        assertFalse(vm.mailPasswordHeld)
    }

    @Test
    fun goingBackToTheMailboxFindsItsPasswordAgain() {
        val vm = seededMailbox()
        onMain {
            vm.setKind(MonitorKind.UDP_PORT)
            vm.setUdpProbe(UdpProbe.SNMP)
            vm.setKind(MonitorKind.MAILBOX)
        }
        awaitTrue(description = "the IMAP password held again") { vm.mailPasswordHeld }
    }

    @Test
    fun aMailboxSavedAsAnotherKindDropsItsPassword() {
        val vm = seededMailbox()
        onMain {
            vm.setKind(MonitorKind.PING)
            vm.update { it.copy(network = it.network.copy(host = "127.0.0.1")) }
            vm.save()
        }
        awaitTrue(description = "saved") { vm.saved }
        assertEquals(MailChecker.Stored.None, stored(MAILBOX_ID))
    }

    @Test
    fun aTcpCheckOn443SwitchedToUdpSpeaksHttp3() {
        val vm = onMain { SetupViewModel(graph, null) }
        onMain {
            vm.setKind(MonitorKind.TCP_PORT)
            vm.update { it.copy(network = it.network.copy(host = "example.com", port = 443)) }
            vm.setKind(MonitorKind.UDP_PORT)
        }
        assertEquals(443, vm.draft.network.port)
        assertEquals(UdpProbe.QUIC, vm.draft.udp.probe)
    }

    @Test
    fun aCommunityStoredByTestNowGoesWithAnUnsavedDraft() {
        val owner = ViewModelStore()
        val vm = onMain {
            ViewModelProvider(owner, viewModelFactory { initializer { SetupViewModel(graph, null) } })[SetupViewModel::class.java]
        }
        val id = vm.draft.id
        onMain {
            vm.setKind(MonitorKind.UDP_PORT)
            vm.update { it.copy(network = it.network.copy(host = "127.0.0.1")) }
            vm.setUdpProbe(UdpProbe.SNMP)
            vm.setMailPassword("public")
            vm.runTest()
        }
        awaitTrue(description = "Test now stored the community") { community(id) == "public" }
        awaitTrue(timeoutMs = 30_000, description = "the test finished") { !vm.testing }
        onMain { owner.clear() }
        awaitTrue(description = "the community left the vault with the draft") { community(id).isEmpty() }
        assertTrue(runBlocking { graph.store.currentSnapshot().monitors.isEmpty() })
    }

    private companion object {
        const val MAILBOX_ID = "snmp-mbx"
        const val SNMP_ID = "snmp-switch"
    }
}
