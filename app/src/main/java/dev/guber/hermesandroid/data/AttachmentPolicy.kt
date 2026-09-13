package dev.guber.hermesandroid.data

import java.util.Base64

object AttachmentPolicy {
    const val MAX_BYTES = 25L * 1024L * 1024L

    fun validate(size: Long): Result<Unit> = if (size in 1..MAX_BYTES) {
        Result.success(Unit)
    } else if (size <= 0) {
        Result.failure(IllegalArgumentException("The selected attachment is empty"))
    } else {
        Result.failure(IllegalArgumentException("Attachments are limited to 25 MiB"))
    }

    fun isImage(mimeType: String): Boolean = mimeType.startsWith("image/", ignoreCase = true)

    fun dataUrl(mimeType: String, bytes: ByteArray): String =
        "data:${mimeType.ifBlank { "application/octet-stream" }};base64,${Base64.getEncoder().encodeToString(bytes)}"
}
