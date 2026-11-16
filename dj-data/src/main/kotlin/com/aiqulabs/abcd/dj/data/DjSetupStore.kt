package com.aiqulabs.abcd.dj.data

import android.content.Context
import com.aiqulabs.abcd.dj.core.model.DjSetupDraft

/**
 * Remembers the last setup selection (brief §4.2: "Remember the last selection").
 *
 * Kept in a small private preferences file, not in Metrolist's settings and not yet in `abcd_dj.db`
 * (stage 5). Only the four v1 choices are stored; no audio or account data.
 */
class DjSetupStore(
    context: Context,
) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** The stored draft, or an empty one on first run or if the stored text cannot be read. */
    fun load(): DjSetupDraft = DjSetupDraft.fromJson(preferences.getString(KEY_DRAFT, null))

    fun save(draft: DjSetupDraft) {
        preferences.edit().putString(KEY_DRAFT, draft.toJson()).apply()
    }

    private companion object {
        const val FILE_NAME = "abcd_dj_setup"
        const val KEY_DRAFT = "draft"
    }
}
