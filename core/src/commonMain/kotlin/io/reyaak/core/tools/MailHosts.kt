package io.reyaak.core.tools

/**
 * Which IMAP server an address belongs to.
 *
 * The whole point of the IMAP path is that setup is an address and an app
 * password, so the server is guessed rather than asked for. Guessed, not
 * looked up: an autodiscover probe is a network round trip and a second failure
 * mode, and `imap.<domain>` is what the overwhelming majority of hosts answer
 * on anyway. The table is only for the providers whose IMAP host is not their
 * mail domain, which is exactly the set a user would have to go and search for.
 */
private val KNOWN = mapOf(
    "gmail.com" to "imap.gmail.com",
    "googlemail.com" to "imap.gmail.com",
    "outlook.com" to "outlook.office365.com",
    "hotmail.com" to "outlook.office365.com",
    "live.com" to "outlook.office365.com",
    "msn.com" to "outlook.office365.com",
    "yahoo.com" to "imap.mail.yahoo.com",
    "yahoo.co.in" to "imap.mail.yahoo.com",
    "icloud.com" to "imap.mail.me.com",
    "me.com" to "imap.mail.me.com",
    "mac.com" to "imap.mail.me.com",
    "aol.com" to "imap.aol.com",
    "zoho.com" to "imap.zoho.com",
    "zohomail.com" to "imap.zoho.com",
    "fastmail.com" to "imap.fastmail.com",
    "gmx.com" to "imap.gmx.com",
    "yandex.com" to "imap.yandex.com",
    "proton.me" to "127.0.0.1",
    "protonmail.com" to "127.0.0.1",
)

/**
 * @return the IMAP host for [address], or blank if it is not an address at all.
 *
 * A custom domain falls through to `imap.<domain>`, which is right for Zoho,
 * Fastmail and Workspace custom domains and for most self-hosted servers. Where
 * it is wrong the user overrides it, which is what [ToolConfig.imapHost] is for.
 */
fun guessImapHost(address: String): String {
    val domain = address.trim().substringAfter('@', "").lowercase()
    if (domain.isBlank() || '.' !in domain) return ""
    return KNOWN[domain] ?: "imap.$domain"
}

/**
 * The server to actually connect to: the override if there is one, else the guess.
 *
 * Derived rather than stored so that correcting a typo in the address fixes the
 * server too, instead of leaving a stale host behind that was right for the old one.
 */
val ToolConfig.imapServer: String
    get() = imapHost.trim().ifBlank { guessImapHost(imapUser) }

/**
 * Proton and Tuta do not speak IMAP to the internet; their bridges listen on
 * localhost on a desktop, which a phone cannot reach. Saying so beats a
 * connection timeout the user has to interpret.
 */
val ToolConfig.imapNeedsBridge: Boolean
    get() = imapHost.isBlank() && guessImapHost(imapUser) == "127.0.0.1"
