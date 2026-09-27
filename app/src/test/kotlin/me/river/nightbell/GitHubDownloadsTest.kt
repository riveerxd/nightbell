package me.river.nightbell

import me.river.nightbell.domain.GitHubAsset
import me.river.nightbell.domain.GitHubDownloads
import me.river.nightbell.domain.GitHubRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which files count, and what they add up to.
 *
 * The interesting cases are all about a filename that changes every release.
 * A filter naming one release's APK is useless by the next tag, so the whole
 * feature rests on the normalising in here being right, and the fixture is the
 * real shape this repository publishes rather than an invented one.
 */
class GitHubDownloadsTest {

    private fun release(
        tag: String,
        vararg assets: Pair<String, Int>,
        draft: Boolean = false,
        prerelease: Boolean = false,
    ) = GitHubRelease(
        id = tag.hashCode().toLong(),
        tag = tag,
        name = tag,
        url = "https://github.com/riveerxd/nightbell/releases/tag/$tag",
        draft = draft,
        prerelease = prerelease,
        assets = assets.map { GitHubAsset(it.first, it.second) },
    )

    /**
     * The shape this repository actually has: one APK per tag, and a run of
     * older tags still carrying the name the app had before it was renamed.
     */
    private val realish = listOf(
        release("v3.13.0", "Nightbell-3.13.0-release.apk" to 295),
        release("v3.12.0", "Nightbell-3.12.0-release.apk" to 125),
        release("v3.11.0", "Nightbell-3.11.0-release.apk" to 144),
        release("v1.6.0", "Pulse-1.6.0-release.apk" to 5),
        release("v1.5.0", "Pulse-1.5.0-release.apk" to 10),
    )

    // ---- normalising ---------------------------------------------------------

    @Test
    fun `a version comes out as one wildcard, not three`() {
        assertEquals(
            "nightbell-*-release.apk",
            GitHubDownloads.normalise("Nightbell-3.13.0-release.apk"),
        )
    }

    @Test
    fun `every release of the same file normalises to the same pattern`() {
        val patterns = listOf(
            "Nightbell-3.13.0-release.apk",
            "Nightbell-3.9.0-release.apk",
            "Nightbell-10.0.1-release.apk",
        ).map(GitHubDownloads::normalise).distinct()
        assertEquals(1, patterns.size)
    }

    @Test
    fun `a rename produces a second pattern rather than hiding under the first`() {
        val grouped = GitHubDownloads.group(realish, emptyList())
        assertEquals(
            listOf("nightbell-*-release.apk", "pulse-*-release.apk"),
            grouped.map { it.pattern },
        )
        assertEquals(564, grouped.first().downloads)
        assertEquals(15, grouped.last().downloads)
    }

    @Test
    fun `a name with no digits survives untouched`() {
        assertEquals("checksums.txt", GitHubDownloads.normalise("checksums.txt"))
    }

    // ---- matching ------------------------------------------------------------

    @Test
    fun `an empty filter counts every file`() {
        assertTrue(GitHubDownloads.matches("anything.apk", emptyList()))
        assertTrue(GitHubDownloads.matches("checksums.txt", emptyList()))
    }

    @Test
    fun `a bare extension matches, whatever the case`() {
        val apk = listOf(".apk")
        assertTrue(GitHubDownloads.matches("Nightbell-3.13.0-release.apk", apk))
        assertTrue(GitHubDownloads.matches("NIGHTBELL.APK", apk))
        assertFalse(GitHubDownloads.matches("Nightbell-3.13.0-release.apk.asc", apk))
    }

    @Test
    fun `several terms each get a chance`() {
        val filters = listOf("*.apk", "*.aab")
        assertTrue(GitHubDownloads.matches("app.apk", filters))
        assertTrue(GitHubDownloads.matches("app.aab", filters))
        assertFalse(GitHubDownloads.matches("app.asc", filters))
    }

    @Test
    fun `a glob matches the middle of a name`() {
        assertTrue(GitHubDownloads.matches("Nightbell-3.13.0-release.apk", listOf("nightbell-*.apk")))
        assertFalse(GitHubDownloads.matches("Pulse-1.6.0-release.apk", listOf("nightbell-*.apk")))
    }

    @Test
    fun `a dot in a typed filename is a dot and not any character`() {
        // The whole reason the glob is compiled rather than handed to Regex: a
        // user pasting a real filename must not be told it matched a file it
        // does not.
        assertTrue(GitHubDownloads.matches("nightbell-3.13.0.apk", listOf("nightbell-3.13.0.apk")))
        assertFalse(GitHubDownloads.matches("nightbell-3x13x0.apk", listOf("nightbell-3.13.0.apk")))
    }

    // ---- summing -------------------------------------------------------------

    @Test
    fun `one release sums only the files that match`() {
        val assets = listOf(
            GitHubAsset("Nightbell-3.13.0-release.apk", 295),
            GitHubAsset("Nightbell-3.13.0-release.apk.asc", 4),
        )
        assertEquals(299, GitHubDownloads.sum(assets, emptyList()))
        assertEquals(295, GitHubDownloads.sum(assets, listOf(".apk")))
        assertEquals(4, GitHubDownloads.sum(assets, listOf(".asc")))
    }

    @Test
    fun `a total spans every release`() {
        assertEquals(579, GitHubDownloads.total(realish, emptyList()))
    }

    @Test
    fun `a filter matching nothing totals zero`() {
        assertEquals(0, GitHubDownloads.total(realish, listOf(".exe")))
        assertTrue(GitHubDownloads.group(realish, listOf(".exe")).isEmpty())
    }

    @Test
    fun `a release with no assets contributes nothing and does not throw`() {
        val releases = realish + release("v4.0.0")
        assertEquals(579, GitHubDownloads.total(releases, emptyList()))
    }

    @Test
    fun `a repository that has only ever tagged source totals zero`() {
        val sourceOnly = listOf(release("v1.0.0"), release("v1.1.0"))
        assertEquals(0, GitHubDownloads.total(sourceOnly, emptyList()))
        assertTrue(GitHubDownloads.group(sourceOnly, emptyList()).isEmpty())
    }

    // ---- drafts --------------------------------------------------------------

    @Test
    fun `a draft never counts, because nobody but the maintainer can reach it`() {
        val withDraft = realish + release("v4.0.0", "Nightbell-4.0.0-release.apk" to 900, draft = true)
        assertEquals(579, GitHubDownloads.total(withDraft, emptyList()))
        assertTrue(GitHubDownloads.group(withDraft, emptyList()).none { it.downloads >= 900 })
    }

    @Test
    fun `a prerelease counts, because the caller decides whether to pass it here`() {
        val withPre = realish + release("v4.0.0-rc1", "Nightbell-4.0.0-release.apk" to 11, prerelease = true)
        assertEquals(590, GitHubDownloads.total(withPre, emptyList()))
    }

    // ---- grouping ------------------------------------------------------------

    @Test
    fun `the breakdown counts contributing releases, not every release`() {
        val grouped = GitHubDownloads.group(realish, emptyList())
        assertEquals(3, grouped.first { it.pattern == "nightbell-*-release.apk" }.releases)
        assertEquals(2, grouped.first { it.pattern == "pulse-*-release.apk" }.releases)
    }

    @Test
    fun `the breakdown is largest first and capped`() {
        // Names that differ by a letter rather than a digit, because two files
        // whose names differ only by a number are the same file to [normalise]
        // and would arrive here as one row.
        val many = ('a'..'t').mapIndexed { index, letter ->
            release("v1.$index.0", "$letter-1.0.0.bin" to index + 1)
        }
        val grouped = GitHubDownloads.group(many, emptyList())
        assertEquals(GitHubDownloads.MAX_FILES, grouped.size)
        assertEquals(grouped.map { it.downloads }.sortedDescending(), grouped.map { it.downloads })
        // The tail is what gets dropped, never the head.
        assertEquals(20, grouped.first().downloads)
    }

    @Test
    fun `two files with equal counts keep a stable order`() {
        val tied = listOf(release("v1.0.0", "b-1.0.0.bin" to 3, "a-1.0.0.bin" to 3))
        assertEquals(
            listOf("a-*.bin", "b-*.bin"),
            GitHubDownloads.group(tied, emptyList()).map { it.pattern },
        )
    }

    // ---- extensions ----------------------------------------------------------

    @Test
    fun `extensions are offered most common first`() {
        val releases = listOf(
            release("v1", "a-1.0.apk" to 1, "a-1.0.apk.asc" to 1),
            release("v2", "a-2.0.apk" to 1),
        )
        assertEquals(listOf(".apk", ".asc"), GitHubDownloads.extensionsIn(releases))
    }

    @Test
    fun `a version's own dot is not offered as a file type`() {
        val releases = listOf(release("v1", "nightbell-3.13.0" to 1))
        assertTrue(GitHubDownloads.extensionsIn(releases).isEmpty())
    }

    @Test
    fun `only the trailing extension is offered, so a signature is its own file`() {
        val releases = listOf(release("v1", "nightbell-3.13.0.apk.asc" to 1))
        assertEquals(listOf(".asc"), GitHubDownloads.extensionsIn(releases))
    }

    @Test
    fun `a draft's files are not offered as chips either`() {
        val releases = listOf(release("v1", "secret-1.0.exe" to 1, draft = true))
        assertTrue(GitHubDownloads.extensionsIn(releases).isEmpty())
    }

    // ---- what the setup screen offers ----------------------------------------

    private fun controls(
        types: List<String>,
        filter: String = "",
        loading: Boolean = false,
    ) = GitHubDownloads.filterControls(types, filter, loading)

    @Test
    fun `two file types make the filter a real choice`() {
        val c = controls(listOf(".apk", ".asc"))
        assertTrue(c.chips)
        assertTrue(c.field)
        assertEquals(null, c.onlyType)
    }

    @Test
    fun `one file type offers no filter, because it could not change the answer`() {
        // The whole point. This repository ships one APK a release, so counting
        // ".apk" and counting everything are the same number, and a control
        // whose two positions are identical is worse than none.
        val c = controls(listOf(".apk"))
        assertFalse(c.chips)
        assertFalse(c.field)
        assertEquals(".apk", c.onlyType)
    }

    @Test
    fun `a filter already typed keeps the field, so it can always be cleared`() {
        val c = controls(listOf(".apk"), filter = ".exe")
        assertTrue(c.field)
        assertFalse(c.chips)
        // No "nothing to narrow" line while a filter is in force: it would be
        // telling somebody their own setting does not exist.
        assertEquals(null, c.onlyType)
    }

    @Test
    fun `a repository nothing is known about still gets the field`() {
        val c = controls(emptyList())
        assertTrue(c.field)
        assertFalse(c.chips)
        assertEquals(null, c.onlyType)
    }

    @Test
    fun `nothing is offered while the look is still in flight`() {
        val c = controls(emptyList(), loading = true)
        assertFalse(c.field)
        assertFalse(c.chips)
        assertEquals(null, c.onlyType)
    }

    @Test
    fun `a typed filter survives the look being in flight`() {
        val c = controls(emptyList(), filter = ".apk", loading = true)
        assertTrue(c.field)
    }
}
