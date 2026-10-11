package me.river.nightbell.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.river.nightbell.domain.HealthAuth
import me.river.nightbell.domain.HealthFormat
import me.river.nightbell.domain.HealthSignIn
import me.river.nightbell.domain.JwtPeek
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.SsoGrant
import me.river.nightbell.domain.Validation
import me.river.nightbell.ui.SetupViewModel
import me.river.nightbell.ui.components.ButtonTone
import me.river.nightbell.ui.components.ChipSelector
import me.river.nightbell.ui.components.FieldNote
import me.river.nightbell.ui.components.GlassCard
import me.river.nightbell.ui.components.GlassField
import me.river.nightbell.ui.components.NightbellButton
import me.river.nightbell.ui.components.SectionHeader
import me.river.nightbell.ui.components.SegmentedSelector
import me.river.nightbell.ui.components.StepperRow
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.NightbellColors

private val HealthSignIn.label: String
    get() = when (this) {
        HealthSignIn.NONE -> "None"
        HealthSignIn.HEADERS -> "Headers"
        HealthSignIn.BASIC -> "Basic"
        HealthSignIn.SSO -> "Sign-on"
    }

private val SsoGrant.label: String
    get() = when (this) {
        SsoGrant.BROWSER -> "Through the web app"
        SsoGrant.PASSWORD -> "Username and password"
        SsoGrant.CLIENT_CREDENTIALS -> "Service account"
        SsoGrant.PASTED -> "Paste a token"
    }

/**
 * The address, then the sign-in card. The card's states are the ones the plan
 * names: nothing until the address has been tried, "No sign-in needed" when it
 * answered with a report, an offer to sign in when it answered 401 or 403, and
 * the signed-in summary once there is one.
 */
@Composable
internal fun HealthTargetCard(
    viewModel: SetupViewModel,
    draft: Monitor,
    report: Validation.Report,
    accent: Color,
) {
    GlassField(
        value = draft.url,
        onValueChange = viewModel::setUrl,
        onBlur = {
            viewModel.completeUrlScheme()
            viewModel.probeHealth()
        },
        label = "Health endpoint URL",
        placeholder = "https://api.example.com/health",
        note = viewModel.urlNote,
        leadingIcon = NightbellIcons.Link,
        accent = accent,
        keyboardType = KeyboardType.Uri,
        modifier = Modifier.testTag("health-url"),
    )
    // An address already in the field when this card appears (editing, a
    // template, a step back and forward) gets its answer without a keystroke.
    LaunchedEffect(Unit) {
        if (viewModel.healthProbe == null && draft.health.signIn == HealthSignIn.NONE) viewModel.probeHealth()
    }
    SignInCard(viewModel, draft, report, accent)
}

@Composable
private fun SignInCard(
    viewModel: SetupViewModel,
    draft: Monitor,
    report: Validation.Report,
    accent: Color,
) {
    val watch = draft.health
    val probe = viewModel.healthProbe
    val refused = probe != null && HealthFormat.isRefusal(probe)
    // Choosing a method is open from the start for anyone who already knows,
    // and folded away behind "Other ways" once the endpoint has asked for SSO.
    var showMethods by remember { mutableStateOf(watch.signIn != HealthSignIn.NONE) }

    SectionHeader("Sign-in", icon = NightbellIcons.Shield, accent = accent)

    when {
        viewModel.probing -> Hint("Trying the address without a sign-in…")
        watch.signIn == HealthSignIn.NONE && probe == null -> Hint(
            "Type the address and Nightbell tries it once without a sign-in, to see whether it needs one.",
        )
        watch.signIn == HealthSignIn.NONE && probe != null && HealthFormat.isReport(probe) -> GlassCard(
            Modifier.fillMaxWidth().testTag("health-no-sign-in"),
            contentPadding = 14.dp,
        ) {
            Text("No sign-in needed", style = MaterialTheme.typography.titleSmall, color = NightbellColors.TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(
                text = probeLine(probe),
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextSecondary,
            )
        }
        watch.signIn == HealthSignIn.NONE && refused -> GlassCard(
            Modifier.fillMaxWidth().testTag("health-needs-sign-in"),
            contentPadding = 14.dp,
        ) {
            Text(
                "This endpoint needs a sign-in.",
                style = MaterialTheme.typography.titleSmall,
                color = NightbellColors.TextPrimary,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NightbellButton(
                    text = "Sign in",
                    onClick = {
                        viewModel.setSignIn(HealthSignIn.SSO)
                        viewModel.setGrant(SsoGrant.BROWSER)
                        viewModel.openBrowserSignIn()
                    },
                    icon = NightbellIcons.Shield,
                    accent = accent,
                    modifier = Modifier.weight(1f).testTag("health-sign-in"),
                )
                NightbellButton(
                    text = "Other ways to sign in",
                    shortText = "Other ways",
                    onClick = { showMethods = true },
                    tone = ButtonTone.Secondary,
                    modifier = Modifier.weight(1f).testTag("health-other-ways"),
                )
            }
        }
        // A login page or a redirect to one is not a 401, so this is also where
        // a protected endpoint can land. Without the button it was a dead end:
        // the methods only showed after a refusal.
        watch.signIn == HealthSignIn.NONE && probe != null -> GlassCard(
            Modifier.fillMaxWidth().testTag("health-no-report"),
            contentPadding = 14.dp,
        ) {
            Text("No report at this address", style = MaterialTheme.typography.titleSmall, color = NightbellColors.TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(
                text = probe.message.ifBlank { "It answered, but not with a health report" }.trimEnd('.') +
                    ". Check the path, or choose a sign-in if it sits behind one.",
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextSecondary,
            )
            if (!showMethods) {
                Spacer(Modifier.height(10.dp))
                NightbellButton(
                    text = "Choose a sign-in",
                    onClick = { showMethods = true },
                    tone = ButtonTone.Secondary,
                    modifier = Modifier.fillMaxWidth().testTag("health-choose-sign-in"),
                )
            }
        }
    }

    if (showMethods || watch.signIn != HealthSignIn.NONE) {
        SegmentedSelector(
            options = HealthSignIn.entries.toList(),
            selected = watch.signIn,
            onSelect = viewModel::setSignIn,
            label = { it.label },
            accent = accent,
            modifier = Modifier.testTag("health-method"),
        )
    }

    when (watch.signIn) {
        HealthSignIn.NONE -> Unit
        HealthSignIn.HEADERS -> {
            Hint("A fixed key, sent as it is on every check: Authorization: Bearer …, X-Api-Key: …")
            HeadersEditor(
                headers = draft.headers,
                onChange = { value -> viewModel.update { it.copy(headers = value) } },
                note = report.of(Validation.Field.HEADERS) ?: report.of(Validation.Field.SIGN_IN),
                accent = accent,
            )
        }
        HealthSignIn.BASIC -> BasicForm(viewModel, draft, report, accent)
        HealthSignIn.SSO -> SsoForm(viewModel, draft, report, accent)
    }

    viewModel.signInMessage?.let { message ->
        // Not rose: Nightbell's own sign-in failing says nothing about the
        // service, and colour on this screen is kept for the service's health.
        GlassCard(Modifier.fillMaxWidth().testTag("health-sign-in-message"), contentPadding = 14.dp) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = NightbellColors.TextSecondary)
        }
    }
}

@Composable
private fun BasicForm(viewModel: SetupViewModel, draft: Monitor, report: Validation.Report, accent: Color) {
    val signedIn = viewModel.signInStatus
    GlassField(
        value = draft.health.username,
        onValueChange = { value -> viewModel.updateHealth { it.copy(username = value) } },
        label = "Username",
        note = report.of(Validation.Field.SIGN_IN),
        leadingIcon = NightbellIcons.Eye,
        accent = accent,
        modifier = Modifier.testTag("health-username"),
    )
    GlassField(
        value = viewModel.passwordInput,
        onValueChange = { viewModel.passwordInput = it },
        label = "Password",
        placeholder = if (signedIn != null) "Stored. Type to replace it." else "",
        leadingIcon = NightbellIcons.Shield,
        accent = accent,
        masked = true,
        keyboardType = KeyboardType.Password,
        modifier = Modifier.testTag("health-password"),
    )
    Hint("Stored encrypted on this phone, never in a backup.")
    NightbellButton(
        text = "Save and test",
        onClick = viewModel::signIn,
        loading = viewModel.signingIn,
        enabled = viewModel.passwordInput.isNotEmpty() && draft.health.username.isNotBlank(),
        accent = accent,
        modifier = Modifier.fillMaxWidth().testTag("health-basic-save"),
    )
}

@Composable
private fun SsoForm(viewModel: SetupViewModel, draft: Monitor, report: Validation.Report, accent: Color) {
    val watch = draft.health
    val status = viewModel.signInStatus
    if (status != null) {
        SignedInCard(viewModel, watch.issuer, status, accent)
        return
    }

    ChipSelector(
        options = SsoGrant.entries.toList(),
        selected = watch.grant,
        onSelect = viewModel::setGrant,
        label = { it.label },
        accent = accent,
        modifier = Modifier.testTag("health-grant"),
    )

    when (watch.grant) {
        SsoGrant.BROWSER -> {
            GlassField(
                value = watch.webAppUrl,
                onValueChange = viewModel::setWebAppUrl,
                label = "Web app",
                placeholder = viewModel.webAppAddress.ifBlank { "https://app.example.com" },
                helper = "Where you normally sign in. Blank uses the endpoint's own address.",
                leadingIcon = NightbellIcons.Globe,
                accent = accent,
                keyboardType = KeyboardType.Uri,
                modifier = Modifier.testTag("health-web-app"),
            )
            Hint(
                "Opens the web app. Tap its own Sign in button and log in as usual. Nightbell " +
                    "reads the provider and client from that sign-in. Google sign-in does not work " +
                    "inside an app; use Paste a token for that.",
            )
            NightbellButton(
                text = "Sign in",
                onClick = viewModel::openBrowserSignIn,
                icon = NightbellIcons.Shield,
                enabled = viewModel.webAppAddress.isNotBlank(),
                accent = accent,
                modifier = Modifier.fillMaxWidth().testTag("health-browser-sign-in"),
            )
        }
        SsoGrant.PASSWORD -> {
            ProviderFields(viewModel, draft, report, accent)
            GlassField(
                value = watch.username,
                onValueChange = { value -> viewModel.updateHealth { it.copy(username = value) } },
                label = "Username",
                leadingIcon = NightbellIcons.Eye,
                accent = accent,
                modifier = Modifier.testTag("health-username"),
            )
            GlassField(
                value = viewModel.passwordInput,
                onValueChange = { viewModel.passwordInput = it },
                label = "Password",
                leadingIcon = NightbellIcons.Shield,
                accent = accent,
                masked = true,
                keyboardType = KeyboardType.Password,
                modifier = Modifier.testTag("health-password"),
            )
            Hint(
                "For a client that allows direct sign-in. The password is stored encrypted and used " +
                    "again only when the session ends. A refused password is tried once, never on a schedule.",
            )
            SignInButton(viewModel, accent, enabled = viewModel.passwordInput.isNotEmpty() && watch.username.isNotBlank())
        }
        SsoGrant.CLIENT_CREDENTIALS -> {
            ProviderFields(viewModel, draft, report, accent)
            GlassField(
                value = viewModel.clientSecretInput,
                onValueChange = { viewModel.clientSecretInput = it },
                label = "Client secret",
                leadingIcon = NightbellIcons.Shield,
                accent = accent,
                masked = true,
                keyboardType = KeyboardType.Password,
                modifier = Modifier.testTag("health-client-secret"),
            )
            SignInButton(viewModel, accent, enabled = viewModel.clientSecretInput.isNotEmpty())
        }
        SsoGrant.PASTED -> {
            GlassField(
                value = viewModel.pastedTokenInput,
                onValueChange = viewModel::pasteToken,
                label = "Refresh token",
                placeholder = "eyJ…",
                leadingIcon = NightbellIcons.Shield,
                accent = accent,
                singleLine = false,
                minLines = 3,
                masked = false,
                modifier = Modifier.testTag("health-paste"),
            )
            val claims = JwtPeek.claims(viewModel.pastedTokenInput)
            if (claims != null && claims.type.equals("Refresh", true)) {
                val until = if (claims.expiresAt > 0) " on ${dateOf(claims.expiresAt * 1000L)}" else ""
                Hint(
                    "This sign-in belongs to the browser session it came from. Signing out there, or that " +
                        "session ending$until, signs Nightbell out. If the provider rotates tokens, Nightbell's " +
                        "first refresh also ends the browser's copy.",
                )
            }
            ProviderFields(viewModel, draft, report, accent)
            SignInButton(viewModel, accent, enabled = viewModel.pastedTokenInput.isNotBlank())
        }
    }
}

/** "Enter provider details": the issuer, found by discovery, and the client. */
@Composable
private fun ProviderFields(viewModel: SetupViewModel, draft: Monitor, report: Validation.Report, accent: Color) {
    val watch = draft.health
    var issuer by remember(watch.issuer) { mutableStateOf(watch.issuer) }
    GlassField(
        value = issuer,
        onValueChange = { issuer = it },
        onBlur = { viewModel.discover(it) },
        label = "Provider address",
        placeholder = "https://sso.example.com/realms/main",
        leadingIcon = NightbellIcons.Globe,
        accent = accent,
        keyboardType = KeyboardType.Uri,
        modifier = Modifier.testTag("health-issuer"),
    )
    FieldNote(
        note = report.of(Validation.Field.SIGN_IN),
        helper = when {
            viewModel.discovering -> "Looking for the provider…"
            else -> viewModel.discoveryNote.orEmpty()
        },
    )
    GlassField(
        value = watch.clientId,
        onValueChange = { value -> viewModel.updateHealth { it.copy(clientId = value) } },
        label = "Client ID",
        placeholder = "The client your web app uses",
        leadingIcon = NightbellIcons.Tag,
        accent = accent,
        modifier = Modifier.testTag("health-client-id"),
    )
}

@Composable
private fun SignInButton(viewModel: SetupViewModel, accent: Color, enabled: Boolean) {
    NightbellButton(
        text = "Sign in and test",
        shortText = "Sign in",
        onClick = viewModel::signIn,
        loading = viewModel.signingIn,
        enabled = enabled && !viewModel.signingIn,
        accent = accent,
        modifier = Modifier.fillMaxWidth().testTag("health-sign-in-submit"),
    )
}

@Composable
private fun SignedInCard(viewModel: SetupViewModel, issuer: String, status: HealthAuth, accent: Color) {
    val host = runCatching { URI(issuer).host }.getOrNull().orEmpty()
    val who = status.account.ifBlank { "this account" }
    val through = if (host.isBlank()) "" else " through $host"
    val lasts = when {
        status.offline -> "Stays signed in"
        status.refreshExpiresAt > 0 -> "Until ${dateOf(status.refreshExpiresAt)}"
        else -> "Until the provider ends the session"
    }
    GlassCard(Modifier.fillMaxWidth().testTag("health-signed-in"), contentPadding = 14.dp) {
        Text(
            "Signed in as $who$through",
            style = MaterialTheme.typography.titleSmall,
            color = NightbellColors.TextPrimary,
        )
        Spacer(Modifier.height(2.dp))
        Text(lasts, style = MaterialTheme.typography.bodySmall, color = NightbellColors.TextSecondary)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NightbellButton(
                text = "Sign in again",
                shortText = "Again",
                onClick = viewModel::signInAgain,
                tone = ButtonTone.Secondary,
                modifier = Modifier.weight(1f).testTag("health-sign-in-again"),
            )
            NightbellButton(
                text = "Sign out",
                onClick = viewModel::signOut,
                tone = ButtonTone.Ghost,
                modifier = Modifier.weight(1f).testTag("health-sign-out"),
            )
        }
    }
}

/** Expectations step: the only expectation a report has, which is not being stale. */
@Composable
internal fun HealthExpectationCard(viewModel: SetupViewModel, draft: Monitor, report: Validation.Report, accent: Color) {
    SectionHeader("Report", icon = NightbellIcons.Clock, accent = accent)
    Hint(
        "The report's own status is the verdict. Up, degraded and down page through the alert " +
            "policy like any other monitor, and each part is listed with the reasons it gave.",
    )
    val reported = (viewModel.testResult?.health ?: viewModel.healthProbe?.health)?.reportedAt ?: 0L
    if (reported > 0L || draft.health.staleAfterMinutes > 0) {
        StepperRow(
            title = "Treat as down if the report is older than",
            value = draft.health.staleAfterMinutes,
            onValueChange = { minutes -> viewModel.updateHealth { it.copy(staleAfterMinutes = minutes) } },
            range = 0..1440,
            suffix = "min",
            zeroLabel = "Off",
            icon = NightbellIcons.History,
            accent = accent,
            note = report.of(Validation.Field.STALE),
            modifier = Modifier.testTag("health-stale"),
        )
    } else {
        Hint("Once a test returns a report with checkedAt, a limit on its age can be set here.")
    }
}

@Composable
private fun Hint(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = NightbellColors.TextTertiary)
}

private fun probeLine(probe: me.river.nightbell.domain.CheckResult): String {
    val state = probe.health ?: return probe.message
    val summary = HealthFormat.summary(state)
    // The message already says "Reported up in 4ms"; a verdict in front of it said it twice.
    if (summary.isBlank()) return probe.message
    val verdict = if (probe.ok) {
        if (probe.degraded) "Degraded" else "Up"
    } else {
        "Down"
    }
    return "$verdict · $summary"
}

private fun dateOf(epochMs: Long): String = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(epochMs))
