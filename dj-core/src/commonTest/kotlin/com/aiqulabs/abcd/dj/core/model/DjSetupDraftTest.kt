package com.aiqulabs.abcd.dj.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DjSetupDraftTest {
    @Test
    fun firstRunIsIncompleteAndAsksForLanguageFirst() {
        val draft = DjSetupDraft()
        assertFalse(draft.isComplete)
        assertEquals(DjSetupDraft.Missing.LANGUAGE, draft.missing)
        assertNull(draft.toSettings())
    }

    @Test
    fun needsMoodAfterLanguage() {
        val draft = DjSetupDraft().toggleLanguage(DjLanguage.HINDI)
        assertEquals(DjSetupDraft.Missing.MOOD, draft.missing)
        assertNull(draft.toSettings())
    }

    @Test
    fun completeDraftBecomesSettingsWithV2Defaults() {
        val settings =
            DjSetupDraft(
                languages = setOf(DjLanguage.HINDI, DjLanguage.PUNJABI),
                mood = DjMood.PARTY,
                mixStyle = MixStyle.SMOOTH,
                sessionLengthMinutes = 30,
            ).toSettings()
        assertEquals(setOf(DjLanguage.HINDI, DjLanguage.PUNJABI), settings?.languages)
        assertEquals(30, settings?.sessionLengthMinutes)
        assertTrue(settings?.genres?.isEmpty() == true)
        assertTrue(settings?.seedVideoIds?.isEmpty() == true)
        assertEquals(DjMood.PARTY.defaultArc, settings?.effectiveEnergyArc)
    }

    @Test
    fun tappingAnyClearsSpecificLanguagesAndCountsAsChosen() {
        val draft =
            DjSetupDraft()
                .toggleLanguage(DjLanguage.HINDI)
                .toggleLanguage(DjLanguage.TAMIL)
                .toggleLanguage(DjLanguage.ANY)
        assertEquals(setOf(DjLanguage.ANY), draft.languages)
        assertNull(draft.missing?.takeIf { it == DjSetupDraft.Missing.LANGUAGE })
    }

    @Test
    fun jsonRoundTripKeepsSelection() {
        val draft = DjSetupDraft(setOf(DjLanguage.MARATHI), DjMood.WEDDING, MixStyle.CHORUS_HITS, 120)
        assertEquals(draft, DjSetupDraft.fromJson(draft.toJson()))
    }

    @Test
    fun corruptOrMissingStoredTextGivesEmptyDraft() {
        assertEquals(DjSetupDraft(), DjSetupDraft.fromJson(null))
        assertEquals(DjSetupDraft(), DjSetupDraft.fromJson(""))
        assertEquals(DjSetupDraft(), DjSetupDraft.fromJson("{not json"))
    }

    @Test
    fun storedV2StyleAndOutOfRangeLengthAreClamped() {
        val parsed = DjSetupDraft.fromJson("""{"mixStyle":"CLUB","sessionLengthMinutes":9999}""")
        assertEquals(MixStyle.DEFAULT, parsed.mixStyle)
        assertEquals(DjSessionSettings.MAX_LENGTH_MINUTES, parsed.sessionLengthMinutes)
    }
}
