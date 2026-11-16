package com.aiqulabs.abcd.dj.core.analysis

/** One line of time-synced lyrics. */
data class LyricLine(
    val timeMs: Long,
    val text: String,
)

/**
 * Combines the audio evidence for the chorus with the lyrics (brief §5.5).
 *
 * Idea: a chorus is the part of a song whose words come back. We count how often each lyric line
 * occurs in the whole song; lines that occur two or more times are "repeated lines". A candidate
 * whose time window is full of repeated lines is very likely the chorus. That is blended with the
 * audio score (repetition and loudness found by the analyzer), and the best candidate becomes the
 * main chorus.
 *
 * With no lyrics the audio result is returned unchanged.
 */
object ChorusRanker {
    /** Weight of the lyrics evidence in the blended score. */
    const val LYRICS_WEIGHT = 0.5

    /** A candidate gets the LYRICS signal when at least this share of its lines repeat. */
    const val LYRICS_SIGNAL_THRESHOLD = 0.5

    private const val MIN_LINES_IN_WINDOW = 2

    fun withLyrics(
        result: AnalysisResult,
        lyrics: List<LyricLine>,
    ): AnalysisResult {
        val lines = lyrics.filter { normalise(it.text).isNotEmpty() }
        if (lines.size < 4 || result.chorusCandidates.isEmpty()) return result

        val counts = lines.groupingBy { normalise(it.text) }.eachCount()
        val reranked =
            result.chorusCandidates.map { candidate ->
                val inside = lines.filter { it.timeMs >= candidate.startMs && it.timeMs < candidate.endMs }
                if (inside.size < MIN_LINES_IN_WINDOW) {
                    candidate
                } else {
                    val repeatedShare = inside.count { (counts[normalise(it.text)] ?: 0) >= 2 }.toDouble() / inside.size
                    val blended = (1 - LYRICS_WEIGHT) * candidate.score + LYRICS_WEIGHT * repeatedShare
                    candidate.copy(
                        score = blended.coerceIn(0.0, 1.0),
                        signals =
                            if (repeatedShare >= LYRICS_SIGNAL_THRESHOLD) {
                                (candidate.signals + ChorusSignal.LYRICS).distinct()
                            } else {
                                candidate.signals
                            },
                    )
                }
            }
        // Best score wins; on a tie the later occurrence (usually the fullest) is the main one.
        val best = reranked.withIndex().maxWithOrNull(compareBy({ it.value.score }, { it.index }))?.index
        val marked = reranked.mapIndexed { index, candidate -> candidate.copy(isMain = index == best) }
        return result.copy(chorusCandidates = marked)
    }

    /** Lower-case letters and digits only, so "Hello, World!" and "hello world" count as the same line. */
    internal fun normalise(text: String): String =
        text
            .lowercase()
            .filter { it.isLetterOrDigit() || it.isWhitespace() }
            .trim()
            .replace(Regex("\\s+"), " ")
}
