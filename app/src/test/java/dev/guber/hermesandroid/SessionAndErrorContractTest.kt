package dev.guber.hermesandroid

import dev.guber.hermesandroid.data.AuthApi
import dev.guber.hermesandroid.data.AuthSession
import dev.guber.hermesandroid.data.approvalPendingParams
import dev.guber.hermesandroid.data.approvalResponseParams
import dev.guber.hermesandroid.data.promptSubmitParams
import dev.guber.hermesandroid.data.promptPendingParams
import dev.guber.hermesandroid.data.sessionIdentity
import dev.guber.hermesandroid.data.sessionInterruptParams
import dev.guber.hermesandroid.data.sessionCreateParams
import dev.guber.hermesandroid.data.sessionResumeParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

class SessionAndErrorContractTest {
    @Test
    fun sessionCreateAndResumeKeepDurableSessionIdentifiers() {
        val create = sessionCreateParams(" Morning review ")
        assertEquals("default", create.getString("profile"))
        assertEquals("android", create.getString("source"))
        assertFalse(create.getBoolean("close_on_disconnect"))
        assertEquals("Morning review", create.getString("title"))
        val resume = sessionResumeParams("stored-42")
        assertEquals("stored-42", resume.getString("session_id"))
        assertEquals("android", resume.getString("source"))
    }

    @Test
    fun sessionIdentityKeepsDurableAndRuntimeIdsSeparate() {
        val identity = JSONObject()
            .put("session_id", "runtime-7")
            .put("session_key", "stored-42")
            .sessionIdentity()
        assertEquals("stored-42", identity?.storedSessionId)
        assertEquals("runtime-7", identity?.runtimeSessionId)
    }

    @Test
    fun promptAndApprovalPayloadsAreExplicit() {
        val prompt = promptSubmitParams("runtime-1", "hello")
        assertEquals("runtime-1", prompt.getString("session_id"))
        assertEquals("hello", prompt.getString("text"))
        assertFalse(prompt.getBoolean("queued"))
        assertEquals("deny", approvalResponseParams("approval-1", "deny").getString("choice"))
        assertEquals("runtime-1", sessionInterruptParams("runtime-1").getString("session_id"))
        assertEquals("runtime-1", approvalPendingParams("runtime-1").getString("session_id"))
        assertEquals("runtime-1", promptPendingParams("runtime-1").getString("session_id"))
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
