package com.mymav.glasses.api

/** "Start recording the meeting" / "stop recording". Recording only starts on an explicit command from the user. */
internal object AssistantMeeting {
    private val START = Regex("^(?:please )?(?:start|begin) (?:recording|a recording)(?: (?:the|this|my|a))?(?: meeting)?$|^(?:please )?record (?:the|this|my|a) meeting$|^(?:start|begin) (?:the |a )?meeting (?:notes|recording|recorder)$")
    private val STOP = Regex("^(?:please )?(?:stop|end|finish) (?:recording|the recording|the meeting|meeting recording|the meeting recording|the recorder)$|^(?:that'?s|that is) the end of the meeting$")

    fun isRequest(c: String) = START.matches(c) || STOP.matches(c)

    fun run(c: String, deliver: (AssistantRouter.Outcome) -> Unit) {
        val b = MymavActions.meeting
        if (b == null) { deliver(AssistantRouter.Outcome("The meeting recorder isn't available in this build.", AssistantLog.Kind.Error, emptyList(), "")); return }
        val cb = { r: MymavActions.SendResult ->
            deliver(AssistantRouter.Outcome(r.text, if (r.ok) AssistantLog.Kind.Local else AssistantLog.Kind.Error, emptyList(), if (r.ok) "ask" else ""))
        }
        if (START.matches(c)) b.start(cb) else b.stop(cb)
    }
}
