package com.hawkynt.mediadroid.engine.desktop

/**
 * The textures the stage hands to the picture window (PictureWindow), as a mailbox: [slots] written by the stage in
 * turn, the newest posted taken by the window. Neither the one the window shows nor the one waiting for it — which it
 * may take at any moment — is ever written into; a newer picture goes beside the waiting one and replaces it. Shared by
 * the stage's thread and the window's, hence synchronised.
 */
class PresentQueue(private val slots: Int) {
    init {
        require(slots >= 3) { "$slots slots leave none to write while one is shown and one waits" }
    }

    private var next = 0
    private var posted: Int? = null

    /** The slot the window shows; null before it took any. */
    @get:Synchronized
    var shown: Int? = null
        private set

    /** The slot the stage writes its next picture into: the next in turn that is neither on screen nor waiting. */
    @Synchronized
    fun writeSlot(): Int {
        var slot = next
        while (slot == shown || slot == posted) slot = (slot + 1) % slots
        next = (slot + 1) % slots
        return slot
    }

    /** [slot] written: the picture the window takes next, in place of any still waiting. */
    @Synchronized
    fun post(slot: Int) {
        posted = slot
    }

    /** The newest slot posted, on screen from now on (the one before free again); null when nothing new came. */
    @Synchronized
    fun take(): Int? {
        val slot = posted ?: return null
        posted = null
        shown = slot
        return slot
    }
}
