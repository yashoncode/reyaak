package io.reyaak.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Setup is an address and an app password, so the server has to be right
 * without being asked for. What breaks here is a user staring at a connection
 * timeout with nothing to go on.
 */
class MailHostsTest {

    @Test
    fun `the providers whose imap host is not their mail domain are known`() {
        assertEquals("imap.gmail.com", guessImapHost("me@gmail.com"))
        assertEquals("outlook.office365.com", guessImapHost("me@hotmail.com"))
        assertEquals("imap.mail.me.com", guessImapHost("me@icloud.com"))
        assertEquals("imap.mail.yahoo.com", guessImapHost("ME@Yahoo.com "))
    }

    @Test
    fun `a custom domain falls through to the convention`() {
        assertEquals("imap.stockarea.io", guessImapHost("me@stockarea.io"))
    }

    @Test
    fun `something that is not an address yields nothing to connect to`() {
        assertEquals("", guessImapHost(""))
        assertEquals("", guessImapHost("not-an-address"))
        assertEquals("", guessImapHost("me@localhost"))
    }

    @Test
    fun `an explicit server wins over the guess`() {
        val config = ToolConfig(imapUser = "me@gmail.com", imapHost = " mail.work.internal ")
        assertEquals("mail.work.internal", config.imapServer)
    }

    @Test
    fun `bridge-only providers are flagged rather than left to time out`() {
        assertTrue(ToolConfig(imapUser = "me@proton.me").imapNeedsBridge)
        // An explicit host means the user has a bridge reachable somehow.
        assertFalse(
            ToolConfig(imapUser = "me@proton.me", imapHost = "10.0.0.5").imapNeedsBridge
        )
        assertFalse(ToolConfig(imapUser = "me@gmail.com").imapNeedsBridge)
    }
}
