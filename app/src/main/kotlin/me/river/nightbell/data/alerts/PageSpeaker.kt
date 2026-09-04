package me.river.nightbell.data.alerts

import me.river.nightbell.data.diag.Diag
import me.river.nightbell.domain.LogEvent
import me.river.nightbell.domain.LogField
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import me.river.nightbell.domain.SpokenPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Says a page out loud, on the device, with no network anywhere in the path.
 *
 * ### Why the engine is the phone's own
 * Synthesis happens inside whichever TTS engine is installed, in that engine's
 * process. Nothing about an announcement leaves the device. That is not merely a
 * privacy preference here: the announcement exists to report that something on
 * the network could not be reached, so a synthesiser that needs the network to
 * speak would be silent in precisely the case it was installed for. Google's
 * engine will happily fetch a nicer voice over the wire, so [pickVoice] refuses
 * any voice that says it needs a connection. The old per-request way of asking
 * for that, `KEY_FEATURE_EMBEDDED_SYNTHESIS`, is deprecated and engines are free
 * to ignore it, which is why the choice is made from the voice list instead.
 *
 * ### Why speaking is not just `speak()`
 *  - **The siren is already looping.** [UrgentAlarm] owns a `MediaPlayer` playing
 *    an alarm tone on repeat, and words spoken over it are mush. [say] mutes it
 *    for the duration and restores it in a `finally`, because a page that has
 *    gone quiet because the speech threw is worse than a page nobody understood.
 *  - **Init is asynchronous.** The constructor returns before the engine is
 *    bound, and speaking too early is dropped silently. Init is awaited once,
 *    with a timeout, and the result cached.
 *  - **A tick is not an utterance.** The service loop comes round every 15 to 60
 *    seconds for as long as the page is unacknowledged. [say] is serialised on a
 *    mutex and drops anything that arrives while it is still talking, so a slow
 *    engine cannot build a queue of stale announcements.
 *
 * Owned by the graph and released when nothing is paging, so an engine process is
 * not kept bound for the whole life of the app.
 */
class PageSpeaker(
    private val context: Context,
    private val alarm: UrgentAlarm? = null,
) {

    /** What the setup screen needs to know, in the order it should complain about it. */
    enum class Readiness {
        /** An engine answered and has an installed, offline-capable voice. */
        READY,

        /** No TTS engine on the device at all, or it refused to initialise. */
        NO_ENGINE,

        /**
         * The engine is there but every voice it offers needs the network or is
         * not downloaded. Speaking would work on wifi and fail during an outage,
         * which is the only time it matters.
         */
        NO_OFFLINE_VOICE,

        /**
         * The engine accepted a voice and then produced nothing.
         *
         * A separate verdict because it is a separate lie: an emulator's Google
         * engine reported an installed, offline en-US voice and failed every
         * utterance with `ERROR_SERVICE`. Believing the voice list would have left
         * Settings showing no warning at all next to a switch that does nothing,
         * which is the exact complaint this feature was rebuilt for.
         */
        ENGINE_SILENT,
    }

    private var engine: TextToSpeech? = null

    /**
     * The last sentence the engine reported having finished, and how many it has
     * finished in this process.
     *
     * Read by the device tests, which cannot hear anything: the alternative is a
     * suite that proves the code path was entered and never that a word came out.
     */
    @Volatile
    var lastSpoken: String? = null
        private set

    @Volatile
    var spokenCount: Int = 0
        private set

    /**
     * The last sentence handed to the engine, and how many have been handed over.
     *
     * Separate from [lastSpoken] because the two answer different questions. This
     * one says the app asked, which is what a device with a broken engine can
     * still prove; [lastSpoken] says audio came out, which only a device with a
     * working one can.
     */
    @Volatile
    var lastRequested: String? = null
        private set

    @Volatile
    var requestCount: Int = 0
        private set

    /** Cached init result, so a dead engine is not re-probed on every tick. */
    private var ready: CompletableDeferred<Readiness>? = null
    private val talking = Mutex()

    /** Serialises binding and tearing the engine down, which several threads do. */
    private val lifecycle = Mutex()

    /**
     * Binds the engine and reports what it can do.
     *
     * Safe to call repeatedly. The first call pays the binding cost, which is
     * typically a couple of hundred milliseconds and has been seen to take
     * several seconds on a cold engine, hence the timeout: a page must not be
     * held up by a synthesiser that is not answering.
     */
    suspend fun readiness(probe: Boolean = false): Readiness {
        val verdict = bind().second
        if (verdict != Readiness.READY || !probe) return verdict
        probed?.let { return it }
        // Synthesised to a file rather than spoken: the question is whether the
        // engine can produce audio, and asking it out loud would mean Settings
        // talking to itself every time the card scrolls into view.
        val works = synthesisWorks(bind().first ?: return Readiness.NO_ENGINE)
        val result = if (works) Readiness.READY else Readiness.ENGINE_SILENT
        probed = result
        return result
    }

    /** Cached synthesis probe, cleared by [release] so a fixed engine can be re-asked. */
    private var probed: Readiness? = null

    private suspend fun synthesisWorks(tts: TextToSpeech): Boolean = talking.withLock {
        val id = "probe-${System.nanoTime()}"
        val finished = CompletableDeferred<Boolean>()
        pending = id to finished
        try {
            val target = File(context.cacheDir, "tts-probe.wav")
            val queued = runCatching {
                tts.synthesizeToFile(PROBE_TEXT, speechParams(), target, id)
            }.getOrDefault(TextToSpeech.ERROR)
            if (queued != TextToSpeech.SUCCESS) return@withLock false
            val ok = runCatching { withTimeout(PROBE_TIMEOUT_MS) { finished.await() } }
                .getOrDefault(false)
            runCatching { target.delete() }
            ok
        } finally {
            pending = null
        }
    }

    /**
     * The bound engine, and what it can do, as one answer.
     *
     * Returned together rather than through the field, and that is the whole
     * point. `readiness()` used to say READY while a `release()` on another
     * thread had already set `engine` to null, so `say` looked at a good verdict,
     * found no engine and returned false without a word in the log. An ordinary
     * alert reproduced it every time: the check pass ends with
     * `onStateChanged`, which released the engine, while the announcement it had
     * just launched was still starting up.
     *
     * Serialised on [lifecycle] so two callers cannot each construct one.
     */
    private suspend fun bind(): Pair<TextToSpeech?, Readiness> = lifecycle.withLock {
        engine?.let { live ->
            val settled = ready?.let { gate ->
                runCatching { withTimeout(INIT_TIMEOUT_MS) { gate.await() } }
                    .getOrElse { Readiness.NO_ENGINE }
            } ?: Readiness.NO_ENGINE
            if (settled != Readiness.READY) return@withLock live to settled
            return@withLock live to voiceReadiness(live)
        }
        val gate = CompletableDeferred<Readiness>()
        val created = runCatching {
            TextToSpeech(context) { status ->
                gate.complete(
                    if (status == TextToSpeech.SUCCESS) Readiness.READY else Readiness.NO_ENGINE,
                )
            }
        }.getOrElse {
            Diag.log(LogEvent.ALERT_SPEAK_FAILED, LogField.tag("at", "construct"), LogField.error("why", it))
            gate.complete(Readiness.NO_ENGINE)
            null
        }
        ready = gate
        engine = created
        val initial = try {
            withTimeout(INIT_TIMEOUT_MS) { gate.await() }
        } catch (_: TimeoutCancellationException) {
            Diag.log(LogEvent.ALERT_SPEAK_FAILED, LogField.tag("at", "init"), LogField.ms("waited", INIT_TIMEOUT_MS))
            Readiness.NO_ENGINE
        }
        val tts = created
        if (initial != Readiness.READY || tts == null) return@withLock tts to Readiness.NO_ENGINE
        tts.setOnUtteranceProgressListener(progress)
        tts to voiceReadiness(tts)
    }

    /**
     * Whether the bound engine has anything it can say offline.
     *
     * Re-asked rather than cached with the init result, because a user sent to the
     * system's speech settings to download a language pack comes back to an engine
     * that has one, and being told to go and fix it again would be a dead end.
     *
     * Polled rather than asked once. `onInit` reporting SUCCESS does not mean the
     * voice list is populated: on a freshly started Google engine the first call
     * to `getVoices()` came back empty and a second call a moment later returned
     * en-US, so asking once made Settings show "no voice installed" to a user who
     * had one. Only a list that is still unusable after [VOICE_WAIT_MS] is worth
     * complaining about.
     */
    private suspend fun voiceReadiness(tts: TextToSpeech): Readiness {
        val deadline = System.currentTimeMillis() + VOICE_WAIT_MS
        while (true) {
            if (pickVoice(tts, preferred = "") != null) return Readiness.READY
            if (System.currentTimeMillis() >= deadline) return Readiness.NO_OFFLINE_VOICE
            delay(VOICE_POLL_MS)
        }
    }

    /**
     * The voices that can be offered in Settings.
     *
     * Only installed, offline-capable ones, and only one entry per language: an
     * engine can expose a dozen variants of the same locale and a list of
     * `en-us-x-sfg#female_2-local` is not a choice anyone can make.
     */
    suspend fun offlineVoices(): List<Choice> {
        val (tts, verdict) = bind()
        if (tts == null || verdict != Readiness.READY) return emptyList()
        return runCatching {
            tts.voices.orEmpty()
                .filter { it.usable }
                .sortedBy { it.name }
                .distinctBy { it.locale.toLanguageTag() }
                .map { Choice(tag = it.locale.toLanguageTag(), label = it.locale.displayName) }
                .sortedBy { it.label }
        }.getOrElse { emptyList() }
    }

    /**
     * The language tag of the voice that would actually read the next alert.
     *
     * Not the same question as "what did the user choose". A stored choice can
     * have been uninstalled since, and a phone whose engine ships only one
     * language never offers a choice at all: on a Vietnamese-only engine the
     * English sentence is read by a Vietnamese voice with nothing selected and
     * nothing to select. Settings warns on this rather than on the preference, so
     * that case is not silent.
     */
    suspend fun effectiveVoiceTag(preferred: String): String? {
        val (tts, verdict) = bind()
        if (tts == null || verdict != Readiness.READY) return null
        return pickVoice(tts, preferred)?.locale?.toLanguageTag()
    }

    data class Choice(val tag: String, val label: String)

    /**
     * Speaks [text] and suspends until the engine says it has finished.
     *
     * Returns false when nothing was said, which the caller wants to know: a
     * missing engine is worth a warning in Settings rather than a page that has
     * silently stopped announcing itself.
     *
     * [usage] is the same [AudioAttributes] usage the siren was built for, so the
     * announcement follows the same volume slider the page sound does instead of
     * arriving at media volume.
     */
    suspend fun say(text: String, usage: Int, voice: String = ""): Boolean {
        if (text.isBlank()) return false
        if (talking.isLocked) {
            // A tick arriving mid-sentence. Dropping it is right: the thing it
            // would announce is the thing currently being announced.
            return false
        }
        val (bound, verdict) = bind()
        if (bound == null || verdict != Readiness.READY) return false
        return talking.withLock {
            val tts = bound
            runCatching {
                tts.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                pickVoice(tts, voice)?.let { tts.voice = it }
            }.onFailure { Diag.log(LogEvent.ALERT_SPEAK_FAILED, LogField.tag("at", "voice"), LogField.error("why", it)) }

            lastRequested = text
            requestCount++
            val id = "page-${System.nanoTime()}"
            val finished = CompletableDeferred<Boolean>()
            pending = id to finished
            val focus = requestFocus(usage)
            alarm?.setDucked(true)
            try {
                val queued = runCatching {
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, speechParams(), id)
                }.getOrElse {
                    Diag.log(LogEvent.ALERT_SPEAK_FAILED, LogField.tag("at", "speak"), LogField.error("why", it))
                    TextToSpeech.ERROR
                }
                if (queued != TextToSpeech.SUCCESS) return@withLock false
                // Bounded: an engine that never reports completion would otherwise
                // hold the siren muted forever, which is the worst available
                // failure for a pager.
                try {
                    withTimeout(SPEAK_TIMEOUT_MS) { finished.await() }.also { spoken ->
                        if (spoken) {
                            lastSpoken = text
                            spokenCount++
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    Diag.log(LogEvent.ALERT_SPEAK_FAILED, LogField.tag("at", "finish"), LogField.ms("waited", SPEAK_TIMEOUT_MS))
                    runCatching { tts.stop() }
                    false
                }
            } finally {
                pending = null
                alarm?.setDucked(false)
                abandonFocus(focus)
                // A release that arrived mid-sentence was deferred to here rather
                // than shutting the engine down under the utterance.
                if (releaseWanted) tearDown()
            }
        }
    }

    /**
     * Drops the engine binding, once nothing is being said.
     *
     * Deferred rather than immediate while an utterance is in flight: this is
     * called from the service loop and from the graph on any state change, both of
     * which can land in the middle of a sentence, and shutting an engine down
     * under its own utterance is how a page went quiet with nothing in the log.
     */
    fun release() {
        if (talking.isLocked) {
            releaseWanted = true
            return
        }
        tearDown()
    }

    @Volatile
    private var releaseWanted = false

    private fun tearDown() {
        val tts = engine
        engine = null
        ready = null
        probed = null
        pending = null
        releaseWanted = false
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }.onFailure { Diag.log(LogEvent.ALERT_SPEAK_FAILED, LogField.tag("at", "shutdown"), LogField.error("why", it)) }
    }

    // ---- internals -----------------------------------------------------------

    private var pending: Pair<String, CompletableDeferred<Boolean>>? = null

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            settle(utteranceId, spoken = true)
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) {
            settle(utteranceId, spoken = false)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            Diag.log(LogEvent.ALERT_SPEAK_FAILED, LogField.tag("at", "engine"), LogField.of("code", errorCode))
            settle(utteranceId, spoken = false)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            settle(utteranceId, spoken = !interrupted)
        }
    }

    private fun settle(utteranceId: String?, spoken: Boolean) {
        val (id, gate) = pending ?: return
        if (utteranceId != null && utteranceId != id) return
        gate.complete(spoken)
    }

    private fun speechParams() = Bundle().apply {
        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
    }

    /**
     * The best voice that will work with no connection.
     *
     * [preferred] is a BCP-47 tag the user chose in Settings; it is honoured only
     * if that voice is still installed, because a language pack can be removed
     * after it was picked and a stale choice must not silence the pager.
     */
    private fun pickVoice(tts: TextToSpeech, preferred: String): Voice? = runCatching {
        val usable = tts.voices.orEmpty().filter { it.usable }
        if (usable.isEmpty()) return@runCatching null
        preferred.takeIf { it.isNotBlank() }
            ?.let { tag -> usable.firstOrNull { it.locale.toLanguageTag().equals(tag, ignoreCase = true) } }
        // The engine's own default comes *after* the language of the words, not
        // before it. A synthesiser does not translate, it pronounces: hand English
        // text to the default voice on a Vietnamese phone and you get English words
        // read with Vietnamese phonology, which is unintelligible and was reported
        // as such. Nightbell's sentence is English (there is one `values/strings.xml`
        // and no translations), so an English voice is the only correct default,
        // whatever the phone's own language is. The user can still override this;
        // that is what the picker is for, and Settings says what it does and does
        // not do.
            ?: usable.firstOrNull { it.locale.language == SpokenPage.TEXT_LANGUAGE }
            ?: tts.voice?.takeIf { it.usable }
            ?: usable.first()
    }.getOrNull()

    private fun requestFocus(usage: Int): AudioFocusRequest? = runCatching {
        val manager = context.getSystemService(AudioManager::class.java) ?: return@runCatching null
        // Transient, and the user's music is asked to duck rather than stop: this
        // is a sentence, not a track, and whatever was playing should come back.
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        manager.requestAudioFocus(request)
        request
    }.getOrNull()

    private fun abandonFocus(request: AudioFocusRequest?) {
        val focus = request ?: return
        runCatching {
            context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(focus)
        }
    }

    private companion object {
        const val TAG = "PageSpeaker"
        const val INIT_TIMEOUT_MS = 6_000L
        const val VOICE_WAIT_MS = 2_000L
        const val VOICE_POLL_MS = 150L
        const val PROBE_TIMEOUT_MS = 8_000L
        const val PROBE_TEXT = "Nightbell"
        const val SPEAK_TIMEOUT_MS = 20_000L
    }
}

/**
 * Installed, and able to speak with the network down.
 *
 * `FEATURE_NOT_INSTALLED` is how an engine advertises a voice it would have to
 * download first, and [Voice.isNetworkConnectionRequired] is how it advertises one
 * it never downloads at all. Either disqualifies a voice here for the same reason:
 * the announcement is about a network that is not working.
 */
private val Voice.usable: Boolean
    get() = !isNetworkConnectionRequired &&
        !features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
