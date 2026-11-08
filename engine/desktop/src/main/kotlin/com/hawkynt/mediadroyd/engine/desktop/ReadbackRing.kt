package com.hawkynt.mediadroyd.engine.desktop

/**
 * Which of the stage's pixel-buffer objects a picture is read back into, and when one is collected: [buffers] used in
 * turn, at most [lag] read-backs left in flight after a picture is handed out — 0 waits for each as it starts, 1 hands
 * out the picture before while the graphics card reads back the new one. The first picture is always waited for.
 */
class ReadbackRing(private val buffers: Int, private val lag: Int) {
    init {
        require(buffers >= 1 && lag in 0 until buffers) { "$buffers buffers cannot hold a lag of $lag" }
    }

    private var first = 0
    private var handedOut = false

    /** How many read-backs are in flight. */
    var inFlight = 0
        private set

    /** The buffer collected next; null when none is in flight. */
    val oldest: Int? get() = if (inFlight == 0) null else first

    /** Whether the oldest read-back has to be collected before a picture can be handed out. */
    val mustCollect: Boolean get() = inFlight > lag || (inFlight > 0 && !handedOut)

    /** The buffer the next read-back goes into, now in flight. */
    fun start(): Int {
        check(inFlight < buffers) { "every buffer is in flight" }
        return ((first + inFlight) % buffers).also { inFlight++ }
    }

    /** The oldest read-back's buffer, collected now. */
    fun finish(): Int {
        check(inFlight > 0) { "nothing is in flight" }
        val done = first
        first = (first + 1) % buffers
        inFlight--
        handedOut = true
        return done
    }

    /** Nothing in flight any more, and the next picture waited for again (a context lost, a size changed). */
    fun reset() {
        first = 0
        inFlight = 0
        handedOut = false
    }
}
