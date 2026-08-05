package com.celzero.bravedns.util

enum class LogLifespan (val lifespanName: String, val lifespanHours: Int, val purgeInterval: Long) {
    ONE_HOUR("1 hour", 1, 30L),
    THREE_HOURS("3 hours", 3, 1L),
    SIX_HOURS("6 hours", 6, 3L),
    TWELVE_HOURS("12 hours", 12, 6L),
    ONE_DAY("1 day", 24, 12L),
    THREE_DAYS("3 days", 72, 24L),
    SEVEN_DAYS("7 days", 168, 24L);

    companion object {

        fun getLogLifespan(lifespanName: String): LogLifespan {
            for (entry in entries) {
                if (entry.lifespanName == lifespanName) {
                    return entry
                }
            }
            // defaults to SEVEN_DAYS if lifespanName does not match any of the above
            return SEVEN_DAYS
        }

        fun getLifespanHours(lifespanName: String): Int {
            return getLogLifespan(lifespanName).lifespanHours
        }

        fun getPurgeInterval(lifespanName: String): Long {
            return getLogLifespan(lifespanName).purgeInterval
        }
    }
}