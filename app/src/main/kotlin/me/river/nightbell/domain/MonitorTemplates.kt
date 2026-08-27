package me.river.nightbell.domain

/**
 * Starting points for a first monitor.
 *
 * The empty state used to go straight to a four-step wizard with every field
 * blank, behind a lone "Create a monitor" button. (`GlobalSettings` even carried a
 * `hasSeenOnboarding` flag that nothing ever read; it is gone.) That wizard is
 * good, and it stays; what was missing is a way to arrive at step two with the
 * boring decisions already made.
 *
 * A template is not a monitor and deliberately does not become one on its own. It
 * pre-fills a draft and hands it to the same wizard, so the user still sees, and
 * can still change, everything before anything is saved. Templates that quietly
 * create objects are how people end up with monitors they do not understand
 * paging them at 3am.
 */
object MonitorTemplates {

    data class Template(
        val id: String,
        val title: String,
        val blurb: String,
        /** What the wizard should start from. The URL is left to the user. */
        val apply: (Monitor) -> Monitor,
    )

    val all: List<Template> = listOf(
        Template(
            id = "website",
            title = "A website",
            blurb = "Any 2xx counts as up. Checked every 15 minutes, urgent off.",
            apply = { draft ->
                draft.copy(
                    kind = MonitorKind.HTTP_STATUS,
                    method = HttpMethod.GET,
                    status = StatusExpectation(mode = StatusMode.ANY_SUCCESS),
                    assertion = BodyAssertion(),
                    intervalMinutes = 15,
                    // A public page that is slow is usually the CDN having a
                    // moment, not an incident, so the latency track starts off.
                    latencySloMs = 0,
                    urgent = false,
                ).withTargets(emptyList())
            },
        ),
        Template(
            id = "health-endpoint",
            title = "A health endpoint",
            blurb = "Expects 200 and a JSON body that says it is ok. Pages you urgently.",
            apply = { draft ->
                draft.copy(
                    kind = MonitorKind.ADVANCED_REQUEST,
                    method = HttpMethod.GET,
                    status = StatusExpectation(mode = StatusMode.EXACT, code = 200),
                    // A health endpoint that returns 200 while reporting its own
                    // failure is the exact case a status-only check misses, which
                    // is why this template asserts on the body rather than the code.
                    assertion = BodyAssertion(
                        mode = AssertionMode.JSON_FIELD_EQUALS,
                        jsonPath = "status",
                        value = "ok",
                    ),
                    headers = listOf(HeaderPair("Accept", "application/json")),
                    intervalMinutes = 5,
                    urgent = true,
                    urgentRepeatMinutes = 5,
                ).withTargets(emptyList())
            },
        ),
        Template(
            id = "api-latency",
            title = "An API, with a budget",
            blurb = "200 plus a 1.5 s latency budget, so slow shows up before broken does.",
            apply = { draft ->
                draft.copy(
                    kind = MonitorKind.ADVANCED_REQUEST,
                    method = HttpMethod.GET,
                    status = StatusExpectation(mode = StatusMode.EXACT, code = 200),
                    assertion = BodyAssertion(),
                    headers = listOf(HeaderPair("Accept", "application/json")),
                    intervalMinutes = 15,
                    latencySloMs = 1_500,
                    useGlobalAlerts = false,
                    // Degraded on its own track: "slow" is the early warning, and
                    // it wants to arrive without the outage cooldown swallowing it.
                    alert = AlertPolicy(alertOnDegraded = true),
                ).withTargets(emptyList())
            },
        ),
        Template(
            id = "github-repo",
            title = "A GitHub repository",
            blurb = "Every new star, every new issue, every release. Checked every 15 minutes.",
            apply = { draft ->
                draft.copy(
                    kind = MonitorKind.GITHUB_REPO,
                    method = HttpMethod.GET,
                    status = StatusExpectation(mode = StatusMode.ANY_SUCCESS),
                    assertion = BodyAssertion(),
                    // The floor rather than a preference. GitHub allows 60 requests
                    // an hour without a token and one poll spends up to three, so
                    // anything tighter runs the device out of budget mid-hour.
                    intervalMinutes = 15,
                    latencySloMs = 0,
                    // A new star is not an outage and must never page anyone.
                    urgent = false,
                    github = GitHubWatch(),
                ).withTargets(emptyList())
            },
        ),
        Template(
            id = "page-element",
            title = "Something on a page",
            blurb = "Loads the real page so you can tap the element to watch.",
            apply = { draft ->
                draft.copy(
                    kind = MonitorKind.WEBSITE_ELEMENT,
                    method = HttpMethod.GET,
                    // Element checks boot a WebView, so a tight cadence is the one
                    // thing this kind should not default to.
                    intervalMinutes = 30,
                    timeoutSeconds = 30,
                    urgent = false,
                )
            },
        ),
    )

    fun byId(id: String): Template? = all.firstOrNull { it.id == id }
}
