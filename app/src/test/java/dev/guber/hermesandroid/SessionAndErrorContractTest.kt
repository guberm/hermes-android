package dev.guber.hermesandroid

import dev.guber.hermesandroid.data.AuthApi
import dev.guber.hermesandroid.data.AuthSession
import dev.guber.hermesandroid.data.approvalResponseParams
import dev.guber.hermesandroid.data.promptSubmitParams
import dev.guber.hermesandroid.data.sessionCreateParams
import dev.guber.hermesandroid.data.sessionResumeParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAndErrorContractTest {
    @Test
    fun sessionCreateAndResumeKeepDurableSessionIdentifiers() {
        val create = sessionCreateParams(" Morning review ")
        assertEquals("default", create.getString("profile"))
        assertFalse(create.getBoolean("close_on_disconnect"))
        assertEquals("Morning review", create.getString("title"))
        assertEquals("stored-42", sessionResumeParams("stored-42").getString("session_id"))
    }

    @Test
    fun promptAndApprovalPayloadsAreExplicit() {
        val prompt = promptSubmitParams("runtime-1", "hello")
        assertEquals("runtime-1", prompt.getString("session_id"))
        assertEquals("hello", prompt.getString("text"))
        assertFalse(prompt.getBoolean("queued"))
        assertEquals("deny", approvalResponseParams("approval-1", "deny").getString("choice"))
    }

    @Test
    fun expiryHasThirtySecondSafetyWindow() {
        val session = AuthSession("access", "refresh", 1_000)
        assertTrue(session.isExpired(970))
        assertFalse(session.isExpired(900))
    }

    @Test
    fun errorMessageUsesSafeServerMessageAndNeverNeedsCredentialData() {
        assertEquals("unauthorized", AuthApi.safeErrorMessage("{\"error\":\"unauthorized\"}", 401))
        assertEquals("Gateway request failed (HTTP 502)", AuthApi.safeErrorMessage("not-json", 502))
    }
}
