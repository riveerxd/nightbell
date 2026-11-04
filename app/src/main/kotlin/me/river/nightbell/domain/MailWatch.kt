package me.river.nightbell.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a [MonitorKind.MAILBOX] connection is encrypted.
 *
 * There is no third option for plain IMAP on purpose. The check signs in on
 * every run, so an unencrypted one would send the password across the network
 * every fifteen minutes, and the people most likely to want it (a box on the
 * LAN) are the ones whose server already offers STARTTLS on 143.
 */
@Serializable
enum class MailSecurity(val label: String, val defaultPort: Int) {
    /** TLS from the first byte, the usual 993. */
    @SerialName("tls")
    TLS("TLS", 993),

    /**
     * Plain on 143, upgraded before anything is sent. A server that does not
     * offer the upgrade fails the check instead of carrying on in clear.
     */
    @SerialName("starttls")
    STARTTLS("STARTTLS", 143),
}

/**
 * Providers whose IMAP settings are fixed, as shortcuts in setup.
 *
 * Only ones that take a plain password or an app password over IMAP. Microsoft
 * is not here because it refuses both since 2024, and Proton and Tuta because
 * they offer no IMAP to a phone; setup says so in words rather than offering a
 * chip that can only fail.
 */
@Serializable
enum class MailPreset(
    val label: String,
    val host: String,
    /** Domains whose addresses this provider hosts, to pick the chip from an address. */
    val domains: List<String>,
    /** What to type in the password field. Shown under the field. */
    val passwordHint: String,
) {
    @SerialName("other")
    OTHER(
        "Other",
        "",
        emptyList(),
        "The password you sign in to webmail with.",
    ),

    @SerialName("gmail")
    GMAIL(
        "Gmail",
        "imap.gmail.com",
        listOf("gmail.com", "googlemail.com"),
        "An app password from your Google account, under Security, 2-Step Verification. " +
            "Your normal Google password is refused.",
    ),

    @SerialName("icloud")
    ICLOUD(
        "iCloud",
        "imap.mail.me.com",
        listOf("icloud.com", "me.com", "mac.com"),
        "An app-specific password from account.apple.com. Your Apple Account password is refused.",
    ),

    @SerialName("fastmail")
    FASTMAIL(
        "Fastmail",
        "imap.fastmail.com",
        listOf("fastmail.com", "fastmail.fm"),
        "An app password from Fastmail's settings, under Privacy & Security.",
    ),

    @SerialName("yahoo")
    YAHOO(
        "Yahoo",
        "imap.mail.yahoo.com",
        listOf("yahoo.com", "ymail.com"),
        "An app password from Yahoo's account security page. Your normal password is refused.",
    ),

    @SerialName("seznam")
    SEZNAM(
        "Seznam",
        "imap.seznam.cz",
        listOf("seznam.cz", "email.cz", "post.cz", "spoluzaci.cz"),
        "Your Seznam password. If the sign-in is refused, allow other mail apps in Seznam's " +
            "mail settings first.",
    ),
    ;

    companion object {
        /** The preset an address belongs to, or [OTHER]. */
        fun forAddress(address: String): MailPreset {
            val domain = address.substringAfterLast('@', "").trim().lowercase()
            if (domain.isEmpty()) return OTHER
            return entries.firstOrNull { domain in it.domains } ?: OTHER
        }

        /**
         * Domains whose mail cannot be read this way at all, and why, so the
         * setup screen can say so the moment the address is typed rather than
         * after a refused sign-in.
         */
        fun unsupported(address: String): String? {
            val domain = address.substringAfterLast('@', "").trim().lowercase()
            return when {
                domain in MICROSOFT -> "Outlook and Hotmail only accept a Microsoft sign-in over IMAP, " +
                    "which Nightbell does not have. Forwarding to another mailbox and watching that works."
                domain in PROTON -> "Proton only offers IMAP through Proton Bridge on a computer, " +
                    "which a phone cannot reach."
                domain in TUTA -> "Tuta does not offer IMAP at all."
                else -> null
            }
        }

        private val MICROSOFT = setOf("outlook.com", "hotmail.com", "live.com", "msn.com", "outlook.cz", "hotmail.cz")
        private val PROTON = setOf("proton.me", "protonmail.com", "pm.me", "protonmail.ch")
        private val TUTA = setOf("tuta.com", "tutanota.com", "tutanota.de", "tuta.io", "keemail.me")
    }
}

/**
 * What a [MonitorKind.MAILBOX] monitor reads, and what it says about it.
 *
 * Nothing secret is here. The password is in the credential vault under the
 * monitor's id, for the reasons [me.river.nightbell.data.auth.CredentialVault]
 * gives, and it is the only field the check needs that this does not hold.
 */
@Serializable
data class MailWatch(
    val host: String = "",
    /** 0 is the security mode's own port. */
    val port: Int = 0,
    val security: MailSecurity = MailSecurity.TLS,
    /** Usually the whole address. Some servers want the part before the @ only. */
    val username: String = "",
    /** Which mailbox, as the server names it. Non-ASCII is fine: it is encoded on the way out. */
    val folder: String = "INBOX",
    /** A notification for every new message, not only the ones [pageFrom] or [pageSubject] match. */
    val notifyAll: Boolean = true,
    /** Comma separated. A new message from a sender containing any of these is an alert, not news. */
    val pageFrom: String = "",
    /** Comma separated. A new message whose subject contains any of these is an alert, not news. */
    val pageSubject: String = "",
    /**
     * Say nothing about a message that was already read by the time the check
     * found it. On, because the case it exists for is ordinary: somebody reads a
     * message on their laptop at 10:02 and the phone announces it at 10:05.
     */
    val skipRead: Boolean = true,
    /** Where a tap on the notification goes. The Roundcube address, say. Empty opens the monitor. */
    val webmailUrl: String = "",
    val preset: MailPreset = MailPreset.OTHER,
) {
    val cleanHost: String get() = host.trim().removePrefix("imaps://").removePrefix("imap://").substringBefore('/').trim()

    val effectivePort: Int get() = if (port > 0) port else security.defaultPort

    val cleanFolder: String get() = folder.trim().ifEmpty { "INBOX" }

    val fromTerms: List<String> get() = terms(pageFrom)

    val subjectTerms: List<String> get() = terms(pageSubject)

    /** Whether anything at all can reach the shade from this monitor's new mail. */
    val announces: Boolean get() = notifyAll || fromTerms.isNotEmpty() || subjectTerms.isNotEmpty()

    /** `user@host` for a card, without the port unless it is not the usual one. */
    val display: String
        get() {
            val where = if (port > 0 && port != security.defaultPort) "${cleanHost}:$port" else cleanHost
            val folderPart = if (cleanFolder.equals("INBOX", ignoreCase = true)) "" else " · $cleanFolder"
            return listOf(username.trim(), where).filter { it.isNotEmpty() }.joinToString(" on ") + folderPart
        }

    private fun terms(text: String): List<String> =
        text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

/** One message as the check saw it. The body is never fetched. */
@Serializable
data class MailMessage(
    val uid: Long,
    val fromName: String = "",
    val fromAddress: String = "",
    val subject: String = "",
    /** When the server received it, epoch millis, 0 if it did not say. */
    val receivedAt: Long = 0L,
    val seen: Boolean = false,
    /** Matched one of the monitor's page terms when it arrived. */
    val matched: Boolean = false,
) {
    /** The sender as a person would say it. */
    val from: String get() = fromName.ifBlank { fromAddress }.ifBlank { "Unknown sender" }

    val subjectOrPlaceholder: String get() = subject.ifBlank { "(no subject)" }
}

/**
 * What one look at the folder found. Produced by the checker, judged by
 * [MailEvents].
 */
data class MailSnapshot(
    val uidValidity: Long,
    val uidNext: Long,
    val messages: Int,
    val unseen: Int,
    /** Messages whose uid is past the stored [MailState.uidNext], newest last, at most [MailEvents.FETCH_LIMIT]. */
    val fresh: List<MailMessage> = emptyList(),
    /** How many arrived in total, which is more than [fresh] when a flood was capped. */
    val freshCount: Int = fresh.size,
)

/**
 * Where a mailbox monitor left the folder, per monitor.
 *
 * The uid is the whole mechanism. IMAP gives every message in a folder an id
 * that only grows, and `UIDNEXT` is the one the next message will get, so
 * "UIDNEXT moved" is "something arrived" whatever happened to the unread count
 * in the meantime. Reading the unread count instead would miss every message
 * read elsewhere before the check, and announce old ones marked unread again.
 */
@Serializable
data class MailState(
    val seeded: Boolean = false,
    /** Changes when the server renumbers the folder, which voids every stored uid. */
    val uidValidity: Long = 0L,
    val uidNext: Long = 0L,
    val messages: Int = -1,
    val unseen: Int = -1,
    /** The newest arrivals, newest first, for the detail screen. */
    val recent: List<MailMessage> = emptyList(),
    val lastArrivalAt: Long = 0L,
)

/** Something a mailbox check found worth saying. */
sealed interface MailEvent {
    val key: String

    /** One message, on its own row. */
    data class Arrived(val message: MailMessage, val folder: String) : MailEvent {
        override val key: String get() = "uid${message.uid}"
    }

    /** Too many at once to post one each. Only ever for messages that did not match. */
    data class Several(val count: Int, val newest: MailMessage?, val folder: String) : MailEvent {
        override val key: String get() = "several"
    }
}

/**
 * Turns one look at a folder into what to announce and what to keep.
 *
 * Pure, so the cases that would otherwise be found by somebody's phone going
 * quiet are tested: the first check, a renumbered folder, a message read
 * elsewhere, a flood.
 */
object MailEvents {

    /** Envelopes fetched per check. Past this a flood is counted, not listed. */
    const val FETCH_LIMIT = 20

    /** Messages posted one each before the rest are folded into one notification. */
    const val ONE_EACH = 4

    /** Kept on the detail screen. */
    const val RECENT_KEPT = 10

    data class Evaluation(val events: List<MailEvent>, val state: MailState)

    fun matches(watch: MailWatch, message: MailMessage): Boolean {
        val from = (message.fromName + " " + message.fromAddress).lowercase()
        val subject = message.subject.lowercase()
        return watch.fromTerms.any { from.contains(it.lowercase()) } ||
            watch.subjectTerms.any { subject.contains(it.lowercase()) }
    }

    fun evaluate(watch: MailWatch, previous: MailState, snapshot: MailSnapshot, nowMs: Long): Evaluation {
        val reseed = !previous.seeded || previous.uidValidity != snapshot.uidValidity
        val base = previous.copy(
            seeded = true,
            uidValidity = snapshot.uidValidity,
            // Never backwards. A server that briefly reports a lower UIDNEXT (a
            // replica behind the primary) would otherwise have the next check
            // announce everything between the two again.
            uidNext = if (reseed) snapshot.uidNext else maxOf(previous.uidNext, snapshot.uidNext),
            messages = snapshot.messages,
            unseen = snapshot.unseen,
            recent = if (reseed) emptyList() else previous.recent,
        )
        if (reseed) return Evaluation(emptyList(), base)

        val fresh = snapshot.fresh
            .filter { it.uid >= previous.uidNext }
            .distinctBy { it.uid }
            .sortedBy { it.uid }
            .map { it.copy(matched = matches(watch, it)) }
        if (fresh.isEmpty()) return Evaluation(emptyList(), base)

        val announceable = fresh.filter { !(watch.skipRead && it.seen) }
        val matched = announceable.filter { it.matched }
        val plain = if (watch.notifyAll) announceable.filterNot { it.matched } else emptyList()
        // Counted against the whole arrival, not just what was fetched, so a flood
        // of 300 says 300 and not 20.
        val unfetched = (snapshot.freshCount - fresh.size).coerceAtLeast(0)
        val events = mutableListOf<MailEvent>()
        matched.forEach { events += MailEvent.Arrived(it, watch.cleanFolder) }
        if (plain.size + unfetched <= ONE_EACH && unfetched == 0) {
            plain.forEach { events += MailEvent.Arrived(it, watch.cleanFolder) }
        } else if (watch.notifyAll && plain.size + unfetched > 0) {
            events += MailEvent.Several(plain.size + unfetched, plain.lastOrNull(), watch.cleanFolder)
        }
        val recent = (fresh.reversed() + previous.recent).distinctBy { it.uid }.take(RECENT_KEPT)
        return Evaluation(
            events = events,
            state = base.copy(recent = recent, lastArrivalAt = nowMs),
        )
    }
}

/** What a healthy mailbox card says in its reading strip. */
object MailLine {
    fun of(state: MailState): String? {
        if (!state.seeded) return null
        val counts = when {
            state.unseen >= 0 -> "${state.unseen} unread"
            state.messages >= 0 -> "${state.messages} messages"
            else -> return null
        }
        val latest = state.recent.firstOrNull() ?: return counts
        return "$counts · latest from ${latest.from}"
    }
}
