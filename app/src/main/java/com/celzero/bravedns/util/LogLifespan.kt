package com.celzero.bravedns.util

import android.content.Context
import com.celzero.bravedns.R

enum class LogLifespan (val id: Long, val lifespanResId: Int, val lifespanHours: Int, val purgeInterval: Long) {
    ONE_HOUR(0, R.string.settings_log_lifespan_dialog_option_0, 1, 30L),
    THREE_HOURS(1, R.string.settings_log_lifespan_dialog_option_1, 3, 60L),
    SIX_HOURS(2, R.string.settings_log_lifespan_dialog_option_2, 6, 180L),
    TWELVE_HOURS(3, R.string.settings_log_lifespan_dialog_option_3, 12, 360L),
    ONE_DAY(4, R.string.settings_log_lifespan_dialog_option_4, 24, 720L),
    THREE_DAYS(5, R.string.settings_log_lifespan_dialog_option_5, 72, 1440L),
    SEVEN_DAYS(6, R.string.settings_log_lifespan_dialog_option_6, 168, 1440L);

    companion object {
        fun getLogLifespan(id: Long): LogLifespan {
            for (entry in entries) {
                if (entry.id == id) {
                    return entry
                }
            }
            // defaults to SEVEN_DAYS if lifespanName does not match any of the above
            return SEVEN_DAYS
        }

        fun getLifespanStrings(context: Context): Array<String> {
            val names = mutableListOf<String>()
            for (entry in entries) {
                names.add(context.getString(entry.lifespanResId))
            }
            return names.toTypedArray()
        }

        fun getLifespanString(id: Long, context: Context): String {
            return context.getString(getLogLifespan(id).lifespanResId)
        }

        fun getLifespanHours(id: Long): Int {
            return getLogLifespan(id).lifespanHours
        }

        fun getPurgeInterval(id: Long): Long {
            return getLogLifespan(id).purgeInterval
        }
    }
}
