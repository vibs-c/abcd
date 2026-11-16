package com.aiqulabs.abcd.dj.ui.setup

import androidx.compose.ui.graphics.vector.ImageVector
import com.aiqulabs.abcd.dj.core.model.DjLanguage
import com.aiqulabs.abcd.dj.core.model.DjMood
import com.aiqulabs.abcd.dj.core.model.MixStyle
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.dj_language_any
import com.metrolist.shared.generated.resources.dj_language_bengali
import com.metrolist.shared.generated.resources.dj_language_english
import com.metrolist.shared.generated.resources.dj_language_gujarati
import com.metrolist.shared.generated.resources.dj_language_hindi
import com.metrolist.shared.generated.resources.dj_language_kannada
import com.metrolist.shared.generated.resources.dj_language_malayalam
import com.metrolist.shared.generated.resources.dj_language_marathi
import com.metrolist.shared.generated.resources.dj_language_punjabi
import com.metrolist.shared.generated.resources.dj_language_tamil
import com.metrolist.shared.generated.resources.dj_language_telugu
import com.metrolist.shared.generated.resources.dj_mood_chill
import com.metrolist.shared.generated.resources.dj_mood_devotional
import com.metrolist.shared.generated.resources.dj_mood_party
import com.metrolist.shared.generated.resources.dj_mood_retro
import com.metrolist.shared.generated.resources.dj_mood_road_trip
import com.metrolist.shared.generated.resources.dj_mood_romantic
import com.metrolist.shared.generated.resources.dj_mood_sad
import com.metrolist.shared.generated.resources.dj_mood_wedding
import com.metrolist.shared.generated.resources.dj_mood_workout
import com.metrolist.shared.generated.resources.dj_style_chorus_hits
import com.metrolist.shared.generated.resources.dj_style_chorus_hits_desc
import com.metrolist.shared.generated.resources.dj_style_radio
import com.metrolist.shared.generated.resources.dj_style_radio_desc
import com.metrolist.shared.generated.resources.dj_style_smooth
import com.metrolist.shared.generated.resources.dj_style_smooth_desc
import com.metrolist.shared.ui.icons.MetrolistIcons
import org.jetbrains.compose.resources.StringResource

/** Chip label for a language. */
internal fun DjLanguage.labelRes(): StringResource =
    when (this) {
        DjLanguage.HINDI -> Res.string.dj_language_hindi
        DjLanguage.ENGLISH -> Res.string.dj_language_english
        DjLanguage.PUNJABI -> Res.string.dj_language_punjabi
        DjLanguage.MARATHI -> Res.string.dj_language_marathi
        DjLanguage.TAMIL -> Res.string.dj_language_tamil
        DjLanguage.TELUGU -> Res.string.dj_language_telugu
        DjLanguage.KANNADA -> Res.string.dj_language_kannada
        DjLanguage.MALAYALAM -> Res.string.dj_language_malayalam
        DjLanguage.BENGALI -> Res.string.dj_language_bengali
        DjLanguage.GUJARATI -> Res.string.dj_language_gujarati
        DjLanguage.ANY -> Res.string.dj_language_any
    }

/** Card label for a mood. */
internal fun DjMood.labelRes(): StringResource =
    when (this) {
        DjMood.PARTY -> Res.string.dj_mood_party
        DjMood.CHILL -> Res.string.dj_mood_chill
        DjMood.ROMANTIC -> Res.string.dj_mood_romantic
        DjMood.ROAD_TRIP -> Res.string.dj_mood_road_trip
        DjMood.WORKOUT -> Res.string.dj_mood_workout
        DjMood.SAD -> Res.string.dj_mood_sad
        DjMood.RETRO -> Res.string.dj_mood_retro
        DjMood.DEVOTIONAL -> Res.string.dj_mood_devotional
        DjMood.WEDDING -> Res.string.dj_mood_wedding
    }

/** Card icon for a mood. */
internal fun DjMood.icon(): ImageVector =
    when (this) {
        DjMood.PARTY -> MetrolistIcons.Outlined.AutoAwesome
        DjMood.CHILL -> MetrolistIcons.Outlined.Bedtime
        DjMood.ROMANTIC -> MetrolistIcons.Outlined.FavoriteBorder
        DjMood.ROAD_TRIP -> MetrolistIcons.Outlined.DirectionsCar
        DjMood.WORKOUT -> MetrolistIcons.Outlined.Equalizer
        DjMood.SAD -> MetrolistIcons.Outlined.Lyrics
        DjMood.RETRO -> MetrolistIcons.Outlined.MusicHistory
        DjMood.DEVOTIONAL -> MetrolistIcons.Outlined.Star
        DjMood.WEDDING -> MetrolistIcons.Outlined.PeopleOutline
    }

/** Segment label for a mix style, or null for styles not offered in v1. */
internal fun MixStyle.labelRes(): StringResource? =
    when (this) {
        MixStyle.SMOOTH -> Res.string.dj_style_smooth
        MixStyle.RADIO -> Res.string.dj_style_radio
        MixStyle.CHORUS_HITS -> Res.string.dj_style_chorus_hits
        MixStyle.CLUB -> null
    }

/** One-line description shown under the style control, or null for styles not offered in v1. */
internal fun MixStyle.descriptionRes(): StringResource? =
    when (this) {
        MixStyle.SMOOTH -> Res.string.dj_style_smooth_desc
        MixStyle.RADIO -> Res.string.dj_style_radio_desc
        MixStyle.CHORUS_HITS -> Res.string.dj_style_chorus_hits_desc
        MixStyle.CLUB -> null
    }
