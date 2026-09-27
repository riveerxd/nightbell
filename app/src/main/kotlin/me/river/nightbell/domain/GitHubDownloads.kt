package me.river.nightbell.domain

import kotlinx.serialization.Serializable

/**
 * One file attached to one release, as the release endpoints return it.
 *
 * The source tarballs GitHub generates for every tag are not assets and never
 * appear here, which is why a repository that has only ever tagged source
 * reports zero downloads rather than nothing at all.
 */
data class GitHubAsset(
    val name: String,
    val downloads: Int,
)

/**
 * One kind of file and everything it has accumulated across releases.
 *
 * [pattern] is the normalised name rather than any one release's filename,
 * because the filename carries the version and every release therefore has a
 * different one. See [GitHubDownloads.normalise].
 */
@Serializable
data class FileDownloads(
    val pattern: String,
    val downloads: Int,
    /** How many releases contributed, so a file shipped once reads as once. */
    val releases: Int,
)

/**
 * What one poll learned about the release files.
 *
 * [complete] is the field that matters. A page walk stopped by its cap or by a
 * thin request budget has summed a prefix of the repository's releases, so the
 * result is a floor rather than a total. Storing it as a total would have the
 * card claim a number the app knows is short, so the flag travels with it and
 * the screen says how many releases it actually covers.
 */
data class DownloadReading(
    val latest: Int,
    val total: Int,
    /** How many releases the total covers, drafts already excluded. */
    val releases: Int,
    val complete: Boolean,
    val byFile: List<FileDownloads>,
) {
    companion object {
        /**
         * A repository with no releases at all.
         *
         * Zero rather than absent, because a 404 from the releases endpoint is
         * a fact about the repository and not a failure to read it. Nothing has
         * been downloaded because there is nothing to download.
         */
        val EMPTY = DownloadReading(
            latest = 0,
            total = 0,
            releases = 0,
            complete = true,
            byFile = emptyList(),
        )
    }
}

/**
 * Turning a repository's release assets into the numbers the user asked for.
 *
 * All of it is pure, and that is the point: which files count, what a wildcard
 * means and how two releases of the same file add up are the things anyone will
 * argue about, and every one of them is a JVM test rather than something to
 * reproduce by downloading an APK from a second machine.
 *
 * Nothing here knows what a total is *for*. A count is a count; whether it is
 * worth a notification is [GitHubEvents]' problem, and what it is worth saying
 * about it is the card's.
 */
/**
 * What the file picker on the setup screen should put in front of somebody.
 *
 * Pure, and separate from the screen, because the rule it encodes is the one
 * anybody would argue about: a filter is only a choice when there is more than
 * one kind of file to choose between, and a repository that ships one APK per
 * release counts the same total whichever way that chip is set.
 */
data class FilterControls(
    /** The tappable file types. Only when tapping one can change the answer. */
    val chips: Boolean,
    /** The free text filter. */
    val field: Boolean,
    /** The one kind of file this repository ships, when that is all it ships. */
    val onlyType: String?,
)

object GitHubDownloads {

    /**
     * See [FilterControls].
     *
     * [filterText] being set keeps the field alive whatever else is true, so a
     * filter somebody typed can always be read and cleared. A repository
     * nothing is known about yet also gets the field: this screen may simply
     * not have looked yet, and the user may know what the repository ships.
     */
    fun filterControls(
        knownTypes: List<String>,
        filterText: String,
        loading: Boolean,
    ): FilterControls {
        val typed = filterText.isNotBlank()
        val chips = knownTypes.size >= 2
        val onlyType = knownTypes.singleOrNull()?.takeUnless { typed }
        val field = chips || typed || (knownTypes.isEmpty() && !loading)
        return FilterControls(chips = chips, field = field, onlyType = onlyType)
    }


    /**
     * Rows kept in the per file breakdown.
     *
     * Sorted by count before the cut, so what a repository publishing twelve
     * files a release loses is the tail rather than an arbitrary dozen.
     */
    const val MAX_FILES = 8

    /** Distinct asset names remembered for the setup screen's chips. */
    const val MAX_KNOWN_NAMES = 24

    /**
     * An asset name with its version taken out.
     *
     * `Nightbell-3.13.0-release.apk` becomes `nightbell-*-release.apk`, and so
     * does every other release of the same file, which is the whole point: the
     * question is how many APKs have ever been pulled, and every release spells
     * that file's name differently.
     *
     * Runs of digits joined by dots, underscores or hyphens collapse together,
     * so a version is one wildcard rather than three. Checked against the 38
     * assets this repository has published: they fall into two groups, and the
     * second one is the releases still carrying the name the app had before it
     * was renamed, which is a fact worth surfacing rather than a bug.
     */
    fun normalise(assetName: String): String =
        DIGIT_RUN.replace(assetName.trim().lowercase(), "*")

    /**
     * Whether one asset counts, given what the user typed.
     *
     * An empty filter means every file, because a user who has not narrowed
     * anything is asking about the release rather than about one file in it.
     *
     * A term carrying no wildcard and starting with a dot is an extension, so
     * `.apk` is read as `*.apk`. That is what lets a chip write something the
     * user could also have typed by hand: a filter field whose chips produce
     * syntax nobody would write is two interfaces wearing one label.
     */
    fun matches(assetName: String, filters: List<String>): Boolean {
        if (filters.isEmpty()) return true
        val name = assetName.trim().lowercase()
        return filters.any { raw ->
            val term = raw.trim().lowercase()
            when {
                term.isEmpty() -> false
                term.startsWith('.') && !term.hasWildcard() -> name.endsWith(term)
                else -> globToRegex(term).matches(name)
            }
        }
    }

    /** What the matching assets on one release have accumulated. */
    fun sum(assets: List<GitHubAsset>, filters: List<String>): Int =
        assets.filter { matches(it.name, filters) }.sumOf { it.downloads }

    /**
     * Every matching asset on every release, added up.
     *
     * Drafts are dropped here rather than by the caller, so no reader of this
     * object can forget: a draft is not public, its assets cannot have been
     * downloaded by anybody but the maintainer, and counting them would put a
     * number on the card that disagrees with the releases page.
     */
    fun total(releases: List<GitHubRelease>, filters: List<String>): Int =
        releases.filterNot { it.draft }.sumOf { sum(it.assets, filters) }

    /**
     * The per file breakdown, largest first.
     *
     * Releases that contributed nothing to a pattern do not count towards its
     * release tally, so a file that appeared once and was dropped says one
     * rather than claiming every release it was absent from.
     */
    fun group(releases: List<GitHubRelease>, filters: List<String>): List<FileDownloads> {
        val downloads = LinkedHashMap<String, Int>()
        val counts = LinkedHashMap<String, Int>()
        releases.filterNot { it.draft }.forEach { release ->
            release.assets
                .filter { matches(it.name, filters) }
                .forEach { asset ->
                    val key = normalise(asset.name)
                    downloads[key] = (downloads[key] ?: 0) + asset.downloads
                    counts[key] = (counts[key] ?: 0) + 1
                }
        }
        return downloads.entries
            .map { (pattern, total) ->
                FileDownloads(pattern, total, counts[pattern] ?: 0)
            }
            // Ties broken by name, so the order is the same frame to frame and
            // two files that have never been downloaded do not swap places.
            .sortedWith(compareByDescending<FileDownloads> { it.downloads }.thenBy { it.pattern })
            .take(MAX_FILES)
    }

    /**
     * Extensions seen across the releases, for the chips on the setup screen.
     *
     * Extensions rather than whole names, because the chip has to be a term the
     * user would also have typed, and nobody types a version number they want
     * to match every release of. Ordered by how often each one appears, so the
     * file a repository actually ships leads.
     */
    fun extensionsIn(releases: List<GitHubRelease>): List<String> {
        val seen = LinkedHashMap<String, Int>()
        releases.filterNot { it.draft }.forEach { release ->
            release.assets.forEach { asset ->
                val ext = extensionOf(asset.name)
                if (ext.isNotEmpty()) seen[ext] = (seen[ext] ?: 0) + 1
            }
        }
        return seen.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
            .take(MAX_KNOWN_NAMES)
    }

    /**
     * The trailing extension, dot included, or empty when there is none.
     *
     * Only the last one, so `nightbell-3.13.0.apk.asc` offers `.asc` rather
     * than `.apk.asc`. A signature is its own file and a user muting it is
     * muting signatures, not APKs.
     */
    private fun extensionOf(assetName: String): String {
        val name = assetName.trim().lowercase().substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return ""
        val ext = name.substring(dot)
        // A dot inside a version is not an extension. `.0` is never a file type.
        return if (ext.drop(1).all { it.isDigit() }) "" else ext
    }

    private fun String.hasWildcard(): Boolean = contains('*') || contains('?')

    /**
     * `*` and `?`, and deliberately nothing else.
     *
     * Everything outside those two is quoted, so a filter naming a real file
     * cannot accidentally be a regex: `nightbell-3.13.0.apk` has three dots in
     * it that would otherwise each match any character, and a user who typed a
     * filename would be told it matched files it does not. A fuller dialect is
     * surface that has to be defended forever and nobody asked for it.
     */
    private fun globToRegex(glob: String): Regex {
        val pattern = buildString {
            glob.forEach { c ->
                when (c) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(c.toString()))
                }
            }
        }
        return Regex(pattern, RegexOption.IGNORE_CASE)
    }

    /** Digits, and the separators that join them inside one version. */
    private val DIGIT_RUN = Regex("""\d+(?:[._-]\d+)*""")
}
