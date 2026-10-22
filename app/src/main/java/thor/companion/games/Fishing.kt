package thor.companion.games

import kotlin.random.Random

/**
 * Fishing Bobber's rules: a round of 90 seconds. Tap to cast; after a while the bobber dips,
 * and a tap within the bite's short window lands a fish (its kind and weight by chance, the
 * big ones rarer). Tapping too early reels in an empty hook; missing the bite lets it go.
 */
class Fishing(private val random: Random = Random.Default) {
    enum class State { READY, WAITING, BITE, CAUGHT, LOST, EARLY, OVER }
    class Fish(val name: String, val kg: Float, val rare: Boolean)

    var state = State.READY
        private set
    /** Seconds left in the round, and in the current state. */
    var left = ROUND
        private set
    private var timer = 0f
    val catch = mutableListOf<Fish>()
    val kg get() = catch.sumOf { it.kg.toDouble() }.toFloat()
    val last get() = catch.lastOrNull()
    val started get() = left < ROUND

    fun tap() {
        when (state) {
            State.READY, State.CAUGHT, State.LOST, State.EARLY -> { state = State.WAITING; timer = 1.5f + random.nextFloat() * 3.5f }
            State.WAITING -> { state = State.EARLY; timer = 1.2f }
            State.BITE -> { catch += fish(); state = State.CAUGHT; timer = 1.6f }
            State.OVER -> {}
        }
    }

    fun step(dt: Float) {
        if (state == State.OVER || !started && state == State.READY) return
        left -= dt
        if (left <= 0f) { left = 0f; state = State.OVER; return }
        timer -= dt
        if (timer > 0f) return
        state = when (state) {
            State.WAITING -> { timer = 0.55f + random.nextFloat() * 0.45f; State.BITE }
            State.BITE -> { timer = 1.2f; State.LOST }
            // After a catch or a miss the line is ready again.
            State.CAUGHT, State.LOST, State.EARLY -> State.READY
            else -> state
        }
    }

    private fun fish(): Fish {
        val roll = random.nextInt(100)
        val (name, lo, hi) = when {
            roll < 6 -> Triple("Old boot", 0f, 0f)
            roll < 36 -> Triple("Silver perch", 0.3f, 1.1f)
            roll < 60 -> Triple("Lake trout", 0.8f, 2.2f)
            roll < 78 -> Triple("Mudfin carp", 1.2f, 3f)
            roll < 92 -> Triple("Pike", 2f, 5f)
            else -> Triple("Golden carp", 2.5f, 6f)
        }
        val kg = if (hi == 0f) 0f else ((lo + random.nextFloat() * (hi - lo)) * 10).toInt() / 10f
        return Fish(name, kg, roll >= 92)
    }

    companion object { const val ROUND = 90f }
}
