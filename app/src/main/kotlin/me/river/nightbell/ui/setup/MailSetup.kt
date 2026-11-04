package me.river.nightbell.ui.setup

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.river.nightbell.domain.MailPreset
import me.river.nightbell.domain.MailSecurity
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.Validation
import me.river.nightbell.ui.SetupViewModel
import me.river.nightbell.ui.components.ButtonTone
import me.river.nightbell.ui.components.ChipSelector
import me.river.nightbell.ui.components.GlassCard
import me.river.nightbell.ui.components.GlassField
import me.river.nightbell.ui.components.NightbellButton
import me.river.nightbell.ui.components.SectionHeader
import me.river.nightbell.ui.components.SegmentedSelector
import me.river.nightbell.ui.components.ToggleRow
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.NightbellColors

/** Account, password and server, in the order a person reads them off their webmail. */
@Composable
internal fun MailTargetCard(
    viewModel: SetupViewModel,
    draft: Monitor,
    report: Validation.Report,
    accent: Color,
) {
    val watch = draft.mail
    SectionHeader("Provider", icon = NightbellIcons.Mail, accent = accent)
    ChipSelector(
        options = MailPreset.entries.toList(),
        selected = watch.preset,
        onSelect = viewModel::applyMailPreset,
        label = { it.label },
        accent = accent,
    )
    GlassField(
        value = watch.username,
        onValueChange = viewModel::setMailUser,
        label = "Address or user name",
        placeholder = "you@example.com",
        note = shown(report.of(Validation.Field.MAIL_USER), watch.username, viewModel, "user"),
        onBlur = { viewModel.touchMail("user") },
        helper = "Usually the whole address. A few servers want only the part before the @.",
        leadingIcon = NightbellIcons.Mail,
        accent = accent,
        keyboardType = KeyboardType.Email,
        modifier = Modifier.testTag("mail-user"),
    )
    GlassField(
        value = viewModel.mailPasswordInput,
        onValueChange = viewModel::setMailPassword,
        label = if (watch.preset == MailPreset.OTHER) "Password" else "App password",
        placeholder = if (viewModel.mailPasswordHeld) "Saved. Type to replace it." else "",
        note = shown(report.of(Validation.Field.MAIL_PASSWORD), viewModel.mailPasswordInput, viewModel, "password"),
        onBlur = { viewModel.touchMail("password") },
        leadingIcon = NightbellIcons.Shield,
        accent = accent,
        masked = true,
        keyboardType = KeyboardType.Password,
        holdErrorUntilBlur = true,
        modifier = Modifier.testTag("mail-password"),
    )
    Note(watch.preset.passwordHint)
    Note("Kept encrypted on this phone. Never put in a backup, a log or a webhook.")
    GlassField(
        value = watch.webmailUrl,
        onValueChange = { value -> viewModel.updateMail { it.copy(webmailUrl = value) } },
        label = "Webmail address (optional)",
        placeholder = "https://mail.example.com",
        note = report.of(Validation.Field.WEBMAIL),
        helper = "Your Roundcube, for example. Helps find the server, and a tap on a new-mail notification opens it.",
        leadingIcon = NightbellIcons.Link,
        accent = accent,
        keyboardType = KeyboardType.Uri,
        modifier = Modifier.testTag("mail-webmail"),
    )
    if (watch.preset == MailPreset.OTHER) {
        NightbellButton(
            text = "Find the server",
            onClick = viewModel::discoverMailServer,
            loading = viewModel.discoveringMail,
            enabled = me.river.nightbell.domain.MailDiscovery.domainOf(watch.username).contains('.'),
            icon = NightbellIcons.Search,
            accent = accent,
            modifier = Modifier.fillMaxWidth().testTag("mail-discover"),
        )
        val found = viewModel.mailDiscoveryNote
        if (found == null) {
            Note(
                "Looks in the address's DNS records and its mail settings file, and at the webmail's host. " +
                    "Nothing is signed in to until the server is found.",
            )
        } else {
            Text(
                text = found,
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextSecondary,
                modifier = Modifier.testTag("mail-discovery-note"),
            )
        }
    }

    SectionHeader("Server", icon = NightbellIcons.Server, accent = accent)
    GlassField(
        value = watch.host,
        onValueChange = { value -> viewModel.updateMail { it.copy(host = value.trim()) } },
        label = "IMAP server",
        placeholder = "mail.example.com",
        note = shown(report.of(Validation.Field.HOST), watch.host, viewModel, "host"),
        onBlur = { viewModel.touchMail("host") },
        helper = if (watch.preset == MailPreset.OTHER) {
            "For Roundcube and most hosting, the server webmail reads from. Often mail. or imap. and your domain."
        } else {
            ""
        },
        leadingIcon = NightbellIcons.Globe,
        accent = accent,
        keyboardType = KeyboardType.Uri,
        holdErrorUntilBlur = true,
        modifier = Modifier.testTag("mail-host"),
    )
    SegmentedSelector(
        options = MailSecurity.entries.toList(),
        selected = watch.security,
        onSelect = { value -> viewModel.updateMail { it.copy(security = value, port = 0) } },
        label = { "${it.label} · ${it.defaultPort}" },
        accent = accent,
    )
    Note(
        when (watch.security) {
            MailSecurity.TLS -> "Encrypted from the first byte. What almost every provider uses."
            MailSecurity.STARTTLS -> "Starts plain and upgrades before signing in. A server that " +
                "does not offer the upgrade fails the check rather than receive the password in the clear."
        },
    )
    GlassField(
        value = if (watch.port > 0) watch.port.toString() else "",
        onValueChange = viewModel::setMailPort,
        label = "Port",
        placeholder = watch.security.defaultPort.toString(),
        note = report.of(Validation.Field.PORT),
        leadingIcon = NightbellIcons.Plug,
        accent = accent,
        keyboardType = KeyboardType.Number,
        modifier = Modifier.testTag("mail-port"),
    )
    GlassField(
        value = watch.folder,
        onValueChange = { value -> viewModel.updateMail { it.copy(folder = value) } },
        label = "Folder",
        placeholder = "INBOX",
        note = report.of(Validation.Field.MAIL_FOLDER),
        helper = "As the server names it. INBOX is the inbox everywhere.",
        leadingIcon = NightbellIcons.Layers,
        accent = accent,
        modifier = Modifier.testTag("mail-folder"),
    )
    val folders = viewModel.mailFolders
    if (folders == null) {
        // Needs the account filled in, since listing means signing in.
        val ready = watch.cleanHost.isNotEmpty() && watch.username.isNotBlank() &&
            (viewModel.mailPasswordInput.isNotEmpty() || viewModel.mailPasswordHeld)
        NightbellButton(
            text = "Pick from the server",
            onClick = viewModel::loadMailFolders,
            loading = viewModel.loadingMailFolders,
            enabled = ready,
            tone = ButtonTone.Secondary,
            icon = NightbellIcons.Layers,
            modifier = Modifier.fillMaxWidth().testTag("mail-folders-load"),
        )
        if (!ready) Note("Fill in the address, password and server first: listing folders means signing in.")
    } else if (folders.isNotEmpty()) {
        ChipSelector(
            options = folders.take(FOLDERS_SHOWN),
            selected = folders.firstOrNull { it == watch.cleanFolder } ?: "",
            onSelect = { name -> viewModel.updateMail { it.copy(folder = name) } },
            label = { it },
            accent = accent,
            modifier = Modifier.testTag("mail-folder-chips"),
        )
        if (folders.size > FOLDERS_SHOWN) {
            Note("and ${folders.size - FOLDERS_SHOWN} more. Type the name for one not shown.")
        }
    }
    viewModel.mailFoldersNote?.let { note ->
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            // Not amber or rose: this is about the setup, not the server's health.
            color = NightbellColors.TextSecondary,
            modifier = Modifier.testTag("mail-folders-note"),
        )
    }
    Note(
        "Not possible: Outlook, Hotmail and Microsoft 365, which only accept a Microsoft sign-in " +
            "Nightbell does not have, and Proton and Tuta, which offer no IMAP to a phone.",
    )
}

/** Past this the chips are a wall; the rest can be typed. */
private const val FOLDERS_SHOWN = 24

/** What gets announced, and the sentence that says what up means for this kind. */
@Composable
internal fun MailExpectationCard(
    viewModel: SetupViewModel,
    draft: Monitor,
    report: Validation.Report,
    accent: Color,
) {
    val watch = draft.mail
    SectionHeader("New mail", icon = NightbellIcons.Bell, accent = accent)
    ToggleRow(
        title = "Every new message",
        subtitle = if (watch.notifyAll) {
            "A quiet notification each. Several at once arrive as one."
        } else {
            "Only messages that match below"
        },
        checked = watch.notifyAll,
        onCheckedChange = { on -> viewModel.updateMail { it.copy(notifyAll = on) } },
        icon = NightbellIcons.Mail,
        accent = accent,
        modifier = Modifier.testTag("mail-notify-all"),
    )
    GlassField(
        value = watch.pageFrom,
        onValueChange = { value -> viewModel.updateMail { it.copy(pageFrom = value) } },
        label = "Alert when the sender has",
        placeholder = "alerts@, monitoring, boss@example.com",
        leadingIcon = NightbellIcons.Zap,
        accent = accent,
        modifier = Modifier.testTag("mail-page-from"),
    )
    GlassField(
        value = watch.pageSubject,
        onValueChange = { value -> viewModel.updateMail { it.copy(pageSubject = value) } },
        label = "Alert when the subject has",
        placeholder = "DOWN, FIRING, urgent",
        note = report.of(Validation.Field.MAIL_TERMS),
        helper = "Comma separated, any case. A match makes a sound with this monitor's alert settings, " +
            "and is announced even when every message is off.",
        leadingIcon = NightbellIcons.Zap,
        accent = accent,
        modifier = Modifier.testTag("mail-page-subject"),
    )
    ToggleRow(
        title = "Skip messages already read",
        subtitle = if (watch.skipRead) {
            "Read on another device before the check found it, so nothing is said"
        } else {
            "Announced whether or not it was read somewhere else first"
        },
        checked = watch.skipRead,
        onCheckedChange = { on -> viewModel.updateMail { it.copy(skipRead = on) } },
        icon = NightbellIcons.Eye,
        accent = accent,
        modifier = Modifier.testTag("mail-skip-read"),
    )
    GlassCard(Modifier.fillMaxWidth().testTag("mail-explainer"), contentPadding = 16.dp) {
        Text(
            text = "Up when Nightbell can sign in and read the folder.",
            style = MaterialTheme.typography.titleSmall,
            color = NightbellColors.TextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "A server that cannot be reached, a bad certificate or a refused password is down " +
                "and alerts like any outage. A refused password is not tried again until it is edited, " +
                "so a server's brute-force guard never blocks this phone. The first check only notes " +
                "where the folder is, so you hear about what arrives after it, not your backlog. The " +
                "folder is opened read only: nothing is ever marked read.",
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextSecondary,
        )
    }
}

/**
 * A field's note, held back while the field is still empty and has never been
 * left. Red under an empty field the moment the step opens is shouting at
 * somebody for not having typed yet; the Test panel already says what is missing.
 */
private fun shown(note: Validation.Note?, value: String, viewModel: SetupViewModel, field: String): Validation.Note? =
    note?.takeIf { value.isNotEmpty() || field in viewModel.mailTouched }

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = NightbellColors.TextTertiary,
    )
}
