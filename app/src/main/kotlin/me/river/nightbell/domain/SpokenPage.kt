package me.river.nightbell.domain

/**
 * The words a page says out loud.
 *
 * Kept free of Android types because every interesting decision here is about
 * text, and text is the part that has to be right before anything is spoken:
 * a synthesiser will happily read `https://api.example.com/healthz` as
 * "h t t p s colon slash slash" at three in the morning.
 *
 * The rules, in the order they earn their place:
 *
 *  1. **A name is read, not spelled.** Monitors are frequently left unnamed, and
 *     [Monitor.displayName] then falls back to the URL. Speech gets the host on
 *     its own, with separators turned into pauses.
 *  2. **A duration is words.** The shade says `4m 12s` because it is being read
 *     by eyes. Out loud that is "four em twelve ess".
 *  3. **Length is capped.** A body assertion can carry a whole JSON snippet in
 *     its failure message, and an announcement that runs for forty seconds is
 *     one the user cannot interrupt and will not sit through.
 */
object SpokenPage {

    /**
     * What gets said when the user has not written their own.
     *
     * Front-loads the monitor name because that is the only word that matters if
     * the sentence is heard from another room.
     *
     * No duration in the default, deliberately. The same sentence is used for an
     * ordinary alert, which fires the moment a check fails and would say "down for
     * just now"; `{duration}` is there for anyone who wants it on their pages.
     */
    const val DEFAULT_TEMPLATE: String = "Nightbell alert. {name} is down. {reason}."

    /**
     * The language Nightbell's own words are in.
     *
     * There is one `values/strings.xml` and no translations, and the sentence
     * below plus every [FailureKind.headline] is written in English in the source.
     * So this is a fact about the app, not a preference: it decides which voice is
     * the right default, and whether a chosen voice can make sense of the words.
     */
    const val TEXT_LANGUAGE: String = "en"

    /** Hard ceiling on one announcement, in characters. Roughly twelve seconds. */
    private const val MAX_LENGTH = 220

    /** Ceiling on the failure message alone, so the name and duration always survive. */
    private const val MAX_REASON = 110

    /**
     * A placeholder a user may write in their template.
     *
     * The set is deliberately small. Every one of these is a fact the page
     * notification already states, so nothing here can say something the shade
     * does not agree with.
     */
    enum class Token(val token: String, val label: String, val example: String) {
        NAME("{name}", "Monitor name", "Wireguard gateway"),
        REASON("{reason}", "Why it failed", "host not found"),
        DURATION("{duration}", "How long it has been down", "4 minutes"),
        OTHERS("{others}", "Other monitors also down", "and 2 more are down"),
        ;

        companion object {
            val all: List<Token> get() = entries.toList()
        }
    }

    /**
     * Fills [template] in and returns something safe to hand a synthesiser.
     *
     * A template with no recognised placeholder is still spoken as written: the
     * user may genuinely want a fixed phrase, and refusing to say anything at all
     * because a page is down to one word would be the wrong failure.
     */
    fun render(
        template: String = DEFAULT_TEMPLATE,
        name: String,
        reason: String,
        downForMs: Long,
        otherPending: Int = 0,
    ): String {
        val text = template.ifBlank { DEFAULT_TEMPLATE }
            .replace(Token.NAME.token, speakableName(name))
            .replace(Token.REASON.token, speakableReason(reason))
            .replace(Token.DURATION.token, spokenDuration(downForMs))
            .replace(Token.OTHERS.token, others(otherPending))
        return tidy(text).take(MAX_LENGTH).trimEnd()
    }

    /**
     * A monitor's name as something worth reading aloud.
     *
     * URLs lose their scheme, their path and their `www`, because a hostname is
     * the recognisable part and the rest is punctuation with a job. Separators
     * become spaces so `wg-gateway_prod` is three words rather than one
     * unpronounceable one. Dots are left alone: engines read `api.example.com`
     * as a domain already, and spelling it out is worse.
     */
    fun speakableName(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "A monitor"
        val host = trimmed
            .substringBefore('?')
            .substringBefore('#')
            .replaceFirst(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://"), "")
            .let { if (it.contains('/')) it.substringBefore('/') else it }
            .removePrefix("www.")
            .substringBefore(':')
            .ifBlank { trimmed }
        val words = host.replace('_', ' ').replace('-', ' ')
        return tidy(words).ifBlank { "A monitor" }
    }

    /** The failure message, shortened at a word boundary and stripped of its full stop. */
    fun speakableReason(raw: String): String {
        val trimmed = tidy(raw).trimEnd('.', '!', ' ')
        if (trimmed.isEmpty()) return "Check failed"
        if (trimmed.length <= MAX_REASON) return trimmed
        val cut = trimmed.take(MAX_REASON)
        val boundary = cut.lastIndexOf(' ')
        return if (boundary > MAX_REASON / 2) cut.take(boundary) else cut
    }

    /**
     * A duration in words.
     *
     * Rounds to whole minutes above a minute, because "one hour, four minutes and
     * eleven seconds" is more precision than anyone woken by it can use, and the
     * page card carries the exact figure for whoever wants it.
     */
    fun spokenDuration(ms: Long): String {
        val seconds = (ms / 1000L).coerceAtLeast(0L)
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours > 0 && minutes > 0 -> "${plural(hours, "hour")} ${plural(minutes, "minute")}"
            hours > 0 -> plural(hours, "hour")
            minutes > 0 -> plural(minutes, "minute")
            seconds < 5 -> "just now"
            else -> plural(seconds, "second")
        }
    }

    /**
     * The policy that decides whether [monitor] speaks.
     *
     * The same resolution the rest of the alerting does: a monitor either follows
     * the global default or carries its own. Kept here so the count Settings
     * shows and the decision the checker makes cannot disagree, which is how a
     * user ends up with a switch that says on next to a monitor that stays quiet.
     */
    fun policyFor(monitor: Monitor, settings: GlobalSettings): AlertPolicy =
        if (monitor.useGlobalAlerts) settings.defaultAlert else monitor.alert

    /**
     * Whether this monitor would say anything, ignoring the things that can only
     * be known at the moment of an alert: the ringer, quiet hours, a mute.
     *
     * Every gate here is one the user can see on a screen, which is what makes it
     * safe to put a count of these in Settings.
     */
    fun speaks(monitor: Monitor, settings: GlobalSettings): Boolean {
        if (!settings.masterAlertsEnabled) return false
        val policy = policyFor(monitor, settings)
        return policy.enabled && policy.speak
    }

    /** How many of [monitors] would speak, for the line in Settings. */
    fun speakingCount(monitors: List<Monitor>, settings: GlobalSettings): Int =
        monitors.count { speaks(it, settings) }

    /**
     * Whether this tick owes the user an announcement.
     *
     * The service loop comes round every 15 to 60 seconds for the whole life of a
     * page, and every one of those ticks has the same facts in it. What makes an
     * announcement due is a *new page*, which is why the identity is the outage's
     * page counter rather than a timestamp: it advances exactly when the pager
     * decides to shout again, so speech inherits `urgentRepeatMinutes` for free
     * and cannot drift away from the notification it is reading out.
     *
     * [audible] is the siren's own verdict on the ringer switch. Speech never
     * gets its own answer to that question.
     */
    fun isDue(
        enabled: Boolean,
        audible: Boolean,
        onRepeats: Boolean,
        pageCount: Int,
        key: String,
        lastSpokenKey: String?,
    ): Boolean {
        if (!enabled || !audible) return false
        if (key == lastSpokenKey) return false
        return onRepeats || pageCount <= 1
    }

    /**
     * Adds [token] to a template, once.
     *
     * Appended rather than inserted at the cursor, because the field is a plain
     * string and a caret position would have to be threaded through the shared
     * text component to do better. Tapping the same placeholder twice is a
     * mis-tap, not a request for it twice, so a token already present is left
     * where the user put it.
     */
    fun withToken(template: String, token: Token): String {
        if (template.contains(token.token)) return template
        val base = template.trimEnd()
        val separator = if (base.isEmpty() || base.endsWith(" ")) "" else " "
        return base + separator + token.token
    }

    /**
     * Whether [template] still carries words Nightbell wrote in English.
     *
     * True for the default sentence, and true for any template using `{reason}`,
     * which is filled from [FailureKind.headline]. A template a user has written
     * themselves, without `{reason}`, contains only their own words and the
     * monitor's name, so it can be in any language they like.
     */
    fun carriesAppWords(template: String): Boolean =
        template.isBlank() || template.contains(Token.REASON.token)

    /**
     * Whether a chosen voice will mangle the sentence it has been given.
     *
     * A synthesiser does not translate. Given English text, a Vietnamese voice
     * reads English words with Vietnamese pronunciation, which is not a
     * translation and is not intelligible as either language. The picker is still
     * worth having, because a user who writes their own sentence in their own
     * language wants a voice to match, but choosing one while the words are still
     * Nightbell's is a mistake the screen has to name rather than a preference to
     * honour silently.
     */
    fun voiceMismatch(template: String, voiceTag: String): Boolean {
        if (voiceTag.isBlank()) return false
        val language = voiceTag.substringBefore('-').substringBefore('_').lowercase()
        if (language == TEXT_LANGUAGE) return false
        return carriesAppWords(template)
    }

    /** The identity of one announcement: this outage, this page. */
    fun keyOf(monitorId: String, pageCount: Int): String = "$monitorId#$pageCount"

    private fun others(count: Int): String = when {
        count <= 0 -> ""
        count == 1 -> "and one more is down"
        else -> "and $count more are down"
    }

    private fun plural(value: Long, unit: String): String =
        if (value == 1L) "1 $unit" else "$value ${unit}s"

    /**
     * Collapses whitespace and the punctuation an emptied placeholder leaves behind.
     *
     * `{others}` is blank whenever exactly one monitor is down, which is most
     * pages, and the default template would otherwise say "down for 4 minutes .
     * ." at the end of every single one of them.
     */
    private fun tidy(text: String): String = text
        .replace(Regex("\\s+"), " ")
        .replace(Regex("\\s+([.,!?])"), "$1")
        .replace(Regex("([.,!?])\\1+"), "$1")
        .trim()
}
