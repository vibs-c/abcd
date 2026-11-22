package com.aiqulabs.abcd.dj.core.tagging

import com.aiqulabs.abcd.dj.core.model.DjLanguage
import com.aiqulabs.abcd.dj.core.model.DjTrack

/**
 * Evidence that a Latin-script title is an Indian song (§6.2).
 *
 * Most Hindi and Punjabi songs on YouTube Music have transliterated titles ("Tum Hi Ho", "Chikni Chameli"),
 * so script detection says nothing about them, and an English-only mix let them through. Three signals:
 * - the film-song marker `(From "Film")` that YouTube Music adds to Indian soundtracks;
 * - distinctive Hindi/Urdu or Punjabi words in the title or album (ambiguous ones such as "na", "ho",
 *   "main" or "man" are left out because they are also English);
 * - well-known Indian singers and composers among the artists.
 *
 * [indianScore] is language-neutral ("Indian, not English"): a Tamil film song scores high too, so it is only
 * used to keep Indian songs out of a mix that has no Indian language selected. [languageWords] is
 * language-specific and may tag a track HINDI or PUNJABI.
 */
object RomanizedIndicHints {
    /** 0..1: how sure we are that [track] is an Indian (not English) song. */
    fun indianScore(track: DjTrack): Float {
        val film = FILM_MARKER.containsMatchIn(textOf(track))
        val words = wordsIn(textOf(track))
        val indicWords = words.count { it in HINDI_WORDS || it in PUNJABI_WORDS }
        val artist = hasIndianArtist(track)
        var score = 0f
        if (film) score = maxOf(score, FILM_SCORE)
        if (indicWords >= 2) score = maxOf(score, TWO_WORDS_SCORE) else if (indicWords == 1) score = maxOf(score, ONE_WORD_SCORE)
        if (artist) score = maxOf(score, ARTIST_SCORE)
        // Two independent signals together are near certain.
        if (listOf(film, indicWords >= 1, artist).count { it } >= 2) score = maxOf(score, COMBINED_SCORE)
        return score
    }

    /** Language tags from distinctive transliterated words: needs at least two such words. */
    fun languageWords(track: DjTrack): Map<DjLanguage, Float> {
        val words = wordsIn(textOf(track))
        val hindi = words.count { it in HINDI_WORDS }
        val punjabi = words.count { it in PUNJABI_WORDS }
        val tags = LinkedHashMap<DjLanguage, Float>()
        if (punjabi >= 1 && hindi + punjabi >= 2) tags[DjLanguage.PUNJABI] = WORDS_CONFIDENCE
        if (hindi >= 2) tags[DjLanguage.HINDI] = WORDS_CONFIDENCE
        return tags
    }

    private fun textOf(track: DjTrack): String = listOfNotNull(track.title, track.album).joinToString(" ")

    internal fun wordsIn(text: String): Set<String> =
        text
            .lowercase()
            .split(Regex("[^\\p{L}]+"))
            .filter { it.length >= 2 }
            .toSet()

    private fun hasIndianArtist(track: DjTrack): Boolean {
        val names = track.artistKeys
        return names.any { name ->
            val padded = " " + name.replace(Regex("""[^\p{L}.\- ]"""), " ") + " "
            INDIAN_ARTISTS.any { padded.contains(" $it ") } || name.split(' ', '-').any { it in INDIAN_NAME_TOKENS }
        }
    }

    private val FILM_MARKER = Regex("""\(\s*from\s+["“”']""", RegexOption.IGNORE_CASE)

    const val FILM_SCORE = 0.75f
    const val TWO_WORDS_SCORE = 0.7f
    const val ONE_WORD_SCORE = 0.4f
    const val ARTIST_SCORE = 0.65f
    const val COMBINED_SCORE = 0.9f
    const val WORDS_CONFIDENCE = 0.65f

    /** Distinctive Hindi/Urdu words common in song titles; none is an ordinary English word. */
    internal val HINDI_WORDS =
        setOf(
            "dil", "pyaar", "pyar", "ishq", "mohabbat", "tera", "teri", "tere", "mera", "meri", "mere", "tum", "tujhe",
            "mujhe", "hai", "hain", "nahi", "nahin", "kya", "kyun", "yeh", "woh", "jaana", "jaane", "sajna", "sajni",
            "sanam", "saiyaan", "saiyan", "piya", "zindagi", "duniya", "khuda", "rabba", "mahi", "chhod", "aaja",
            "aaye", "jaaye", "raatein", "baatein", "aankhon", "aankhein", "chal", "chalo", "saath", "sath", "kabhi",
            "pehli", "pehla", "dekha", "dekho", "laga", "lagi", "lage", "hoon", "humko", "tumko", "tumse", "humse",
            "deewana", "deewani", "dilbar", "jaanam", "sanu", "tenu", "naach", "nachle", "nache",
            "balam", "dhadkan", "nazar", "nazrein", "barsaat", "bheegi", "mehbooba", "kudiye", "pardesi", "bole",
            "chudiyan", "jhumka", "lehenga", "dulhan", "shaadi", "baraat", "mehndi", "dholna", "sohneya", "soniye",
        )

    /** Distinctive Punjabi words. */
    internal val PUNJABI_WORDS =
        setOf("kudi", "kudiyan", "munda", "mundeya", "jatt", "jatti", "gallan", "putt", "tusi", "assi", "kithe", "balle", "sohni", "yaariyan")

    /** Well-known Indian singers and composers (full or distinctive names). */
    private val INDIAN_ARTISTS =
        listOf(
            "arijit singh", "shreya ghoshal", "sonu nigam", "kumar sanu", "udit narayan", "alka yagnik", "lata mangeshkar",
            "kishore kumar", "mohammed rafi", "asha bhosle", "neha kakkar", "badshah", "honey singh", "diljit dosanjh",
            "guru randhawa", "b praak", "jubin nautiyal", "atif aslam", "pritam", "vishal-shekhar", "shankar mahadevan",
            "sunidhi chauhan", "mika singh", "sukhwinder", "armaan malik", "darshan raval", "tanishk bagchi", "sachet tandon",
            "mohit chauhan", "rahat fateh ali khan", "nusrat fateh ali khan", "a.r. rahman", "ar rahman", "anirudh",
            "sid sriram", "karan aujla", "ap dhillon", "sidhu moose wala", "harrdy sandhu", "jasleen royal",
            "vishal mishra", "amit trivedi", "salim-sulaiman", "himesh reshammiya", "javed ali",
            "yuvan shankar raja", "devi sri prasad", "thaman", "ilaiyaraaja", "shilpa rao", "sunidhi", "palak muchhal",
            // Stage 12 (owner report: Hindi songs in an English mix).
            "vishal dadlani", "benny dayal", "raftaar", "emiway bantai", "jasmine sandlas", "dhvani bhanushali",
            "asees kaur", "tulsi kumar", "millind gaba", "parmish verma", "ammy virk", "jassie gill", "gippy grewal",
            "sharry mann", "mankirt aulakh", "shalmali kholgade", "neeti mohan", "monali thakur", "kanika kapoor",
            "papon", "lucky ali", "ankit tiwari", "mithoon", "sajid-wajid", "shankar-ehsaan-loy", "yasser desai",
            "raj shekhar", "stebin ben", "anu malik", "nadeem-shravan", "jatin-lalit", "kailash kher", "meet bros",
            "sachin-jigar", "amaal mallik", "akhil sachdeva", "payal dev", "harshdeep kaur", "nikhita gandhi",
            "rashmeet kaur", "zaeden", "mc stan", "krsna", "seedhe maut", "hanumankind", "yo yo honey singh",
        )

    /** Name parts that are near-certainly Indian. */
    private val INDIAN_NAME_TOKENS =
        setOf(
            "singh", "kumar", "sharma", "kapoor", "chauhan", "ghoshal", "kakkar", "dosanjh", "randhawa", "nautiyal", "bhosle",
            "mangeshkar", "yagnik", "rahman", "raval", "aujla", "dhillon", "sandhu", "sriram", "kaur", "bhanushali",
            "dadlani", "kholgade", "virk", "grewal", "aulakh", "reshammiya", "mallik", "sachdeva",
        )
}
