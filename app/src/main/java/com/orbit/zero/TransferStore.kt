package com.orbit.zero

data class TransferState(
    val id: String = "",
    val title: String = "",
    val filename: String = "",
    val state: String = "idle",
    val received: Long = 0,
    val total: Long = 0,
    val speed: Double = 0.0,
    val message: String = "",
) {
    val percent: Int
        get() = if (total > 0) ((received * 100) / total).coerceIn(0, 100).toInt() else 0
    val running: Boolean
        get() = state in setOf("preparing", "downloading", "verifying")
}

object TransferStore {
    @Volatile
    var current: TransferState = TransferState()

    @Volatile
    var cancelRequested: Boolean = false

    fun reset(title: String, filename: String, total: Long) {
        cancelRequested = false
        current = TransferState(
            title = title,
            filename = filename,
            state = "preparing",
            total = total,
        )
    }

    fun update(
        state: String? = null,
        received: Long? = null,
        speed: Double? = null,
        message: String? = null,
    ) {
        val c = current
        current = c.copy(
            state = state ?: c.state,
            received = received ?: c.received,
            speed = speed ?: c.speed,
            message = message ?: c.message,
        )
    }
}
