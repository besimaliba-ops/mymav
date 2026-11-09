package com.mymav.glasses.api

/**
 * "What am I looking at?", "read this sign", "what's in this fridge". These need a frame from the glasses camera.
 * MyMav does not receive camera frames from the glasses in this build, so the honest answer is to say so; a language
 * model must never be asked to guess what the wearer is looking at.
 */
internal object AssistantCamera {
    private val RE = Regex(
        "\\b(what am i looking at|what (?:can|do) you see|what do i see|look at this|read (?:this|that|the) (?:sign|label|menu|text|page|screen|writing)|" +
            "(?:what'?s|what is|whats) in (?:this|my|the) (?:fridge|freezer|cupboard|pantry)|what is (?:this|that) (?:in front of me|object|thing|plant|building)|" +
            "(?:identify|scan) (?:this|that)|translate (?:this|that) (?:sign|menu|label))\\b",
    )

    fun isRequest(c: String): Boolean = RE.containsMatchIn(c) && !Regex("\\b(song|music|track|playing)\\b").containsMatchIn(c)

    /** Grabs one frame from the glasses/simulator camera and describes it; says plainly when no frame can be had. */
    fun run(question: String, post: (AssistantRouter.Outcome) -> Unit) {
        val src = MymavVision.glassesFrame
        if (src == null) { post(outcome()); return }
        val frame = runCatching { src() }.getOrNull()
        if (frame == null) {
            post(AssistantRouter.Outcome(
                "I couldn't get a picture from the glasses camera: ${MymavVision.lastProblem.ifBlank { "no frame arrived" }}. I won't guess what you're looking at.",
                AssistantLog.Kind.Error, emptyList(), "",
            ))
            return
        }
        val r = MymavVision.describe(question, frame)
        post(AssistantRouter.Outcome(r.text, r.kind, r.sources, r.note))
    }

    fun outcome() = AssistantRouter.Outcome(
        "I can't see through the glasses camera yet. MyMav isn't receiving camera frames from the glasses, so I won't guess what you're looking at.",
        AssistantLog.Kind.Error, emptyList(), "",
    )
}
