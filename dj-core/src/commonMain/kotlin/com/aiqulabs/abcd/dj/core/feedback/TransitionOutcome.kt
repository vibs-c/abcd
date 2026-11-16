package com.aiqulabs.abcd.dj.core.feedback

/** How a transition between two tracks turned out (brief §6.1 `TransitionFeedback.outcome`). */
enum class TransitionOutcome {
    THUMBS_UP,
    THUMBS_DOWN,
    SKIPPED,
    COMPLETED,
}
