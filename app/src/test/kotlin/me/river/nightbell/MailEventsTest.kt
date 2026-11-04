package me.river.nightbell

import me.river.nightbell.domain.MailEvent
import me.river.nightbell.domain.MailEvents
import me.river.nightbell.domain.MailLine
import me.river.nightbell.domain.MailMessage
import me.river.nightbell.domain.MailPreset
import me.river.nightbell.domain.MailSnapshot
import me.river.nightbell.domain.MailState
import me.river.nightbell.domain.MailWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailEventsTest {

    private val watch = MailWatch(host = "mail.example.com", username = "me@example.com")
    private val seeded = MailState(seeded = true, uidValidity = 7, uidNext = 100, messages = 50, unseen = 2)

    private fun msg(uid: Long, from: String = "ann@example.com", subject: String = "Hello", seen: Boolean = false) =
        MailMessage(uid = uid, fromAddress = from, subject = subject, seen = seen)

    private fun snap(uidNext: Long, vararg fresh: MailMessage, validity: Long = 7, count: Int = fresh.size) =
        MailSnapshot(uidValidity = validity, uidNext = uidNext, messages = 52, unseen = 4, fresh = fresh.toList(), freshCount = count)

    @Test
    fun `the first check writes down where the folder is and says nothing`() {
        val e = MailEvents.evaluate(watch, MailState(), snap(100, msg(98), msg(99)), 1L)
        assertTrue(e.events.isEmpty())
        assertTrue(e.state.seeded)
        assertEquals(100L, e.state.uidNext)
        assertTrue(e.state.recent.isEmpty())
    }

    @Test
    fun `a renumbered folder seeds again rather than announcing every message in it`() {
        val e = MailEvents.evaluate(watch, seeded.copy(recent = listOf(msg(90))), snap(5, msg(1), msg(2), validity = 8), 1L)
        assertTrue(e.events.isEmpty())
        assertEquals(8L, e.state.uidValidity)
        assertEquals(5L, e.state.uidNext)
        assertTrue(e.state.recent.isEmpty())
    }

    @Test
    fun `new messages are announced one each`() {
        val e = MailEvents.evaluate(watch, seeded, snap(102, msg(100), msg(101, subject = "Second")), 9L)
        assertEquals(2, e.events.size)
        assertEquals(101L, (e.events[1] as MailEvent.Arrived).message.uid)
        assertEquals(102L, e.state.uidNext)
        assertEquals(listOf(101L, 100L), e.state.recent.map { it.uid })
        assertEquals(9L, e.state.lastArrivalAt)
    }

    @Test
    fun `the star quirk, an old message returned for n colon star, is not announced`() {
        val e = MailEvents.evaluate(watch, seeded, snap(100, msg(99)), 1L)
        assertTrue(e.events.isEmpty())
        assertTrue(e.state.recent.isEmpty())
    }

    @Test
    fun `a message already read elsewhere is kept but not announced`() {
        val e = MailEvents.evaluate(watch, seeded, snap(101, msg(100, seen = true)), 1L)
        assertTrue(e.events.isEmpty())
        assertEquals(listOf(100L), e.state.recent.map { it.uid })
        val loud = MailEvents.evaluate(watch.copy(skipRead = false), seeded, snap(101, msg(100, seen = true)), 1L)
        assertEquals(1, loud.events.size)
    }

    @Test
    fun `a match on sender or subject is marked, and announced even with every message off`() {
        val quiet = watch.copy(notifyAll = false, pageFrom = "alerts@, Grafana", pageSubject = "down")
        val e = MailEvents.evaluate(
            quiet,
            seeded,
            snap(
                104,
                msg(100, from = "ALERTS@monitor.example.com"),
                msg(101, subject = "web-3 is DOWN"),
                msg(102, subject = "lunch?"),
                MailMessage(uid = 103, fromName = "Grafana", fromAddress = "noreply@x.io", subject = "ok"),
            ),
            1L,
        )
        assertEquals(listOf(100L, 101L, 103L), e.events.map { (it as MailEvent.Arrived).message.uid })
        assertTrue(e.events.all { (it as MailEvent.Arrived).message.matched })
        assertFalse(e.state.recent.first { it.uid == 102L }.matched)
    }

    @Test
    fun `several at once fold into one notification that counts the whole arrival`() {
        val fresh = (100L..109L).map { msg(it) }.toTypedArray()
        val e = MailEvents.evaluate(watch, seeded, snap(400, *fresh, count = 300), 1L)
        val several = e.events.single() as MailEvent.Several
        assertEquals(300, several.count)
        assertEquals(109L, several.newest?.uid)
    }

    @Test
    fun `matches still get their own row inside a flood`() {
        val fresh = (100L..106L).map { msg(it) } + msg(107, subject = "FIRING: disk")
        val e = MailEvents.evaluate(watch.copy(pageSubject = "firing"), seeded, snap(108, *fresh.toTypedArray()), 1L)
        assertEquals(2, e.events.size)
        assertTrue((e.events[0] as MailEvent.Arrived).message.matched)
        assertEquals(7, (e.events[1] as MailEvent.Several).count)
    }

    @Test
    fun `four or fewer go one each, five fold`() {
        val four = MailEvents.evaluate(watch, seeded, snap(104, msg(100), msg(101), msg(102), msg(103)), 1L)
        assertEquals(4, four.events.size)
        val five = MailEvents.evaluate(watch, seeded, snap(105, msg(100), msg(101), msg(102), msg(103), msg(104)), 1L)
        assertEquals(1, five.events.size)
    }

    @Test
    fun `uidnext never goes backwards, so a lagging replica cannot replay mail`() {
        val e = MailEvents.evaluate(watch, seeded, snap(90), 1L)
        assertEquals(100L, e.state.uidNext)
    }

    @Test
    fun `recent keeps the newest ten`() {
        var state = seeded
        for (uid in 100L until 115L) {
            state = MailEvents.evaluate(watch, state, snap(uid + 1, msg(uid)), 1L).state
        }
        assertEquals(10, state.recent.size)
        assertEquals(114L, state.recent.first().uid)
    }

    @Test
    fun `presets come from the address and unsupported providers are named`() {
        assertEquals(MailPreset.GMAIL, MailPreset.forAddress("Someone@Gmail.com"))
        assertEquals(MailPreset.SEZNAM, MailPreset.forAddress("x@seznam.cz"))
        assertEquals(MailPreset.OTHER, MailPreset.forAddress("me@bohemian.example"))
        assertNotNull(MailPreset.unsupported("a@outlook.com"))
        assertNotNull(MailPreset.unsupported("a@proton.me"))
        assertNull(MailPreset.unsupported("a@gmail.com"))
    }

    @Test
    fun `the card line says unread and who wrote last`() {
        assertNull(MailLine.of(MailState()))
        assertEquals("2 unread", MailLine.of(seeded))
        assertEquals(
            "2 unread · latest from Ann",
            MailLine.of(seeded.copy(recent = listOf(MailMessage(uid = 1, fromName = "Ann", fromAddress = "a@b")))),
        )
    }

    @Test
    fun `the watch reads its fields the way the checker uses them`() {
        val w = MailWatch(host = " imaps://mail.example.com/ ", folder = " ", pageFrom = " a, ,b ")
        assertEquals("mail.example.com", w.cleanHost)
        assertEquals("INBOX", w.cleanFolder)
        assertEquals(993, w.effectivePort)
        assertEquals(listOf("a", "b"), w.fromTerms)
    }
}
