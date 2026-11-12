package com.zerovox

/**
 * Sliding-window speed meter. A single 700 ms sample can swing wildly on
 * mobile networks, which also makes the ETA jump. This keeps the last few
 * seconds so the speed and ETA stay readable without hiding real stalls.
 */
class SpeedMeter(private val windowMs: Long = 3000L) {
    private val times = ArrayList<Long>()
    private val values = ArrayList<Long>()

    fun reset(now: Long, value: Long) {
        times.clear()
        values.clear()
        times.add(now)
        values.add(value)
    }

    fun update(now: Long, value: Long): Double {
        if (times.isEmpty()) {
            reset(now, value)
            return 0.0
        }
        times.add(now)
        values.add(value)
        while (times.size > 2 && now - times[0] > windowMs) {
            times.removeAt(0)
            values.removeAt(0)
        }
        val dt = (now - times[0]) / 1000.0
        if (dt <= 0.0) return 0.0
        val delta = value - values[0]
        return if (delta > 0) delta / dt else 0.0
    }
}
