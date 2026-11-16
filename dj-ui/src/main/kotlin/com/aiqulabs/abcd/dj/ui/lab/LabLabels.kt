package com.aiqulabs.abcd.dj.ui.lab

import androidx.compose.ui.graphics.Color
import com.aiqulabs.abcd.dj.core.analysis.ChorusSignal
import com.aiqulabs.abcd.dj.core.analysis.ChorusSource
import com.aiqulabs.abcd.dj.core.analysis.KeyMode
import com.aiqulabs.abcd.dj.core.analysis.SectionType
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.dj_lab_mode_major
import com.metrolist.shared.generated.resources.dj_lab_mode_minor
import com.metrolist.shared.generated.resources.dj_lab_section_bridge
import com.metrolist.shared.generated.resources.dj_lab_section_chorus
import com.metrolist.shared.generated.resources.dj_lab_section_intro
import com.metrolist.shared.generated.resources.dj_lab_section_outro
import com.metrolist.shared.generated.resources.dj_lab_section_pre_chorus
import com.metrolist.shared.generated.resources.dj_lab_section_verse
import com.metrolist.shared.generated.resources.dj_lab_signal_energy
import com.metrolist.shared.generated.resources.dj_lab_signal_lyrics
import com.metrolist.shared.generated.resources.dj_lab_signal_most_replayed
import com.metrolist.shared.generated.resources.dj_lab_signal_repetition
import com.metrolist.shared.generated.resources.dj_lab_source_auto
import com.metrolist.shared.generated.resources.dj_lab_source_user
import org.jetbrains.compose.resources.StringResource

internal fun SectionType.labelRes(): StringResource =
    when (this) {
        SectionType.INTRO -> Res.string.dj_lab_section_intro
        SectionType.VERSE -> Res.string.dj_lab_section_verse
        SectionType.PRE_CHORUS -> Res.string.dj_lab_section_pre_chorus
        SectionType.CHORUS -> Res.string.dj_lab_section_chorus
        SectionType.BRIDGE -> Res.string.dj_lab_section_bridge
        SectionType.OUTRO -> Res.string.dj_lab_section_outro
    }

/** Fixed colours so the same section looks the same on every song. Alpha is applied where drawn. */
internal fun SectionType.color(): Color =
    when (this) {
        SectionType.INTRO -> Color(0xFF8E9AAF)
        SectionType.VERSE -> Color(0xFF5B8DEF)
        SectionType.PRE_CHORUS -> Color(0xFFB07CE8)
        SectionType.CHORUS -> Color(0xFFEF6F5B)
        SectionType.BRIDGE -> Color(0xFF4FB286)
        SectionType.OUTRO -> Color(0xFFB0A28E)
    }

internal fun KeyMode.labelRes(): StringResource =
    when (this) {
        KeyMode.MAJOR -> Res.string.dj_lab_mode_major
        KeyMode.MINOR -> Res.string.dj_lab_mode_minor
    }

internal fun ChorusSignal.labelRes(): StringResource =
    when (this) {
        ChorusSignal.REPETITION -> Res.string.dj_lab_signal_repetition
        ChorusSignal.LYRICS -> Res.string.dj_lab_signal_lyrics
        ChorusSignal.ENERGY -> Res.string.dj_lab_signal_energy
        ChorusSignal.MOST_REPLAYED -> Res.string.dj_lab_signal_most_replayed
    }

internal fun ChorusSource.labelRes(): StringResource =
    when (this) {
        ChorusSource.AUTO -> Res.string.dj_lab_source_auto
        ChorusSource.USER -> Res.string.dj_lab_source_user
    }
