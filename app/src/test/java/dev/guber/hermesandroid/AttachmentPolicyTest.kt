package dev.guber.hermesandroid

import dev.guber.hermesandroid.data.AttachmentPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentPolicyTest {
    @Test
    fun dataUrlHasMimeAndBase64Payload() {
        assertEquals("data:text/plain;base64,aGk=", AttachmentPolicy.dataUrl("text/plain", "hi".toByteArray()))
    }

    @Test
    fun imageDetectionIsCaseInsensitive() {
        assertTrue(AttachmentPolicy.isImage("IMAGE/PNG"))
        assertFalse(AttachmentPolicy.isImage("application/pdf"))
    }

    @Test
    fun emptyAndOversizedAttachmentsAreRejected() {
        assertTrue(AttachmentPolicy.validate(0).isFailure)
        assertTrue(AttachmentPolicy.validate(AttachmentPolicy.MAX_BYTES + 1).isFailure)
        assertTrue(AttachmentPolicy.validate(AttachmentPolicy.MAX_BYTES).isSuccess)
    }
}
