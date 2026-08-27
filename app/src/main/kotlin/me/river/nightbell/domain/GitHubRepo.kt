package me.river.nightbell.domain

import kotlinx.serialization.Serializable

/**
 * One GitHub repository, parsed once so nothing downstream has to guess.
 *
 * People paste whatever they have in front of them: the slug from a README, the
 * address bar while looking at issue 12, the clone line out of the green button.
 * All of those name the same repository, and refusing any of them would be a
 * setup screen arguing with the user about punctuation.
 */
@Serializable
data class GitHubRepo(val owner: String = "", val name: String = "") {

    val isSet: Boolean get() = owner.isNotBlank() && name.isNotBlank()

    /** `owner/repo`, which is what every notification and card says. */
    val slug: String get() = "$owner/$name"

    val url: String get() = "https://github.com/$owner/$name"

    val issuesUrl: String get() = "$url/issues"

    val releasesUrl: String get() = "$url/releases"

    companion object {
        /** GitHub caps a login at 39 characters and forbids a leading or trailing hyphen. */
        private val OWNER = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$")

        /** Repository names allow dots and underscores as well, up to 100 characters. */
        private val NAME = Regex("^[A-Za-z0-9._-]{1,100}$")

        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

        private val HOSTS = setOf("github.com", "www.github.com", "api.github.com")

        /**
         * Reads any of the shapes a repository is written in, or null.
         *
         * Accepts `owner/repo`, `https://github.com/owner/repo`, a deep link into
         * that repo (`/issues/4`, `/tree/main/app`, `?tab=readme`), the API form
         * `api.github.com/repos/owner/repo`, and the SSH clone line
         * `git@github.com:owner/repo.git`.
         *
         * Rejects anything hosted somewhere else. A GitLab address is not a
         * GitHub repository, and quietly treating it as one would produce a
         * monitor that polls the wrong service forever.
         */
        fun parse(raw: String): GitHubRepo? {
            var text = raw.trim()
            if (text.isEmpty()) return null

            text = text.removePrefix("git@github.com:")
            SCHEME.find(text)?.let { text = text.substring(it.value.length) }

            // Credentials in front of the host, as a clone URL can carry.
            val slash = text.indexOf('/').takeIf { it >= 0 } ?: text.length
            val at = text.indexOf('@')
            if (at in 0 until slash) text = text.substring(at + 1)

            // A first segment carrying a dot is a hostname rather than an owner:
            // no GitHub login contains one.
            var hadHost = false
            val first = text.substringBefore('/')
            if (first.contains('.')) {
                val host = first.substringBefore(':').lowercase()
                if (host !in HOSTS) return null
                hadHost = true
                text = text.substringAfter('/', "")
                if (host == "api.github.com") text = text.removePrefix("repos/")
            }

            text = text.substringBefore('?').substringBefore('#')
            val parts = text.split('/').filter { it.isNotBlank() }
            if (parts.size < 2) return null
            // Without a host there is nothing to justify extra segments: a bare
            // `a/b/c` is a path, not a repository.
            if (!hadHost && parts.size != 2) return null

            val owner = parts[0]
            val name = parts[1].removeSuffix(".git")
            if (!OWNER.matches(owner)) return null
            if (!NAME.matches(name)) return null
            // Legal characters, illegal names: both would resolve to a directory.
            if (name == "." || name == "..") return null
            return GitHubRepo(owner, name)
        }
    }
}
