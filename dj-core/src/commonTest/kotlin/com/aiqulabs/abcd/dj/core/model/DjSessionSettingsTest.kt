package com.aiqulabs.abcd.dj.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DjSessionSettingsTest {
    @Test
    fun jsonRoundTripKeepsV2Fields() {
        val settings =
            DjSessionSettings(
                languages = setOf(DjLanguage.HINDI, DjLanguage.PUNJABI),
                mood = DjMood.WEDDING,
                mixStyle = MixStyle.CHORUS_HITS,
                sessionLengthMinutes = 90,
                genres = setOf(DjGenre.BOLLYWOOD),
                seedVideoIds = listOf("abc"),
            )
        assertEquals(settings, DjSessionSettings.fromJson(settings.toJson()))
    }

    @Test
    fun normalizedClampsLengthAndRejectsV2Style() {
        val settings = DjSessionSettings(mixStyle = MixStyle.CLUB, sessionLengthMinutes = 5, endless = true).normalized()
        assertEquals(MixStyle.DEFAULT, settings.mixStyle)
        assertEquals(DjSessionSettings.MIN_LENGTH_MINUTES, settings.sessionLengthMinutes)
        assertFalse(settings.endless)
        assertEquals(DjSessionSettings.MAX_LENGTH_MINUTES, DjSessionSettings(sessionLengthMinutes = 999).normalized().sessionLengthMinutes)
    }

    @Test
    fun energyArcDefaultsFromMood() {
        assertEquals(DjMood.PARTY.defaultArc, DjSessionSettings(mood = DjMood.PARTY).effectiveEnergyArc)
        assertEquals(EnergyArc.WAVE, DjSessionSettings(mood = DjMood.PARTY, energyArc = EnergyArc.WAVE).effectiveEnergyArc)
    }

    @Test
    fun unknownJsonFieldsAreIgnored() {
        val parsed = DjSessionSettings.fromJson("""{"mood":"CHILL","futureField":1}""")
        assertEquals(DjMood.CHILL, parsed.mood)
    }

    @Test
    fun languageToggleRules() {
        val any = setOf(DjLanguage.ANY)
        assertEquals(setOf(DjLanguage.HINDI), DjLanguage.toggle(any, DjLanguage.HINDI))
        assertEquals(any, DjLanguage.toggle(setOf(DjLanguage.HINDI), DjLanguage.HINDI))
        assertEquals(any, DjLanguage.toggle(setOf(DjLanguage.HINDI, DjLanguage.TAMIL), DjLanguage.ANY))
        assertEquals(
            setOf(DjLanguage.HINDI, DjLanguage.TAMIL),
            DjLanguage.toggle(setOf(DjLanguage.HINDI), DjLanguage.TAMIL),
        )
    }
}
