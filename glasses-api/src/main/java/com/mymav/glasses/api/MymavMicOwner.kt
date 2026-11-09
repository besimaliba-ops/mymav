package com.mymav.glasses.api

/**
 * One microphone owner at a time across Captions, Meeting recorder, and Conversate.
 * Callers must [release] when they stop. Never asks for secrets.
 */
object MymavMicOwner {
    enum class Owner { None, Captions, Meeting, Conversate }

    @Volatile
    var owner: Owner = Owner.None
        private set

    /** Null = acquired; otherwise an honest reason the mic is busy. */
    fun tryAcquire(who: Owner): String? {
        synchronized(this) {
            if (owner == Owner.None || owner == who) {
                owner = who
                return null
            }
            return when (owner) {
                Owner.Captions -> "Live captions are using the microphone. Say stop captions first."
                Owner.Meeting -> "A meeting is being recorded, and the microphone can't do both. Stop the recording first."
                Owner.Conversate -> "Conversate is listening. Stop Conversate on the phone first."
                Owner.None -> null
            }
        }
    }

    fun release(who: Owner) {
        synchronized(this) {
            if (owner == who) owner = Owner.None
        }
    }

    fun isHeldBy(who: Owner): Boolean = owner == who
}
