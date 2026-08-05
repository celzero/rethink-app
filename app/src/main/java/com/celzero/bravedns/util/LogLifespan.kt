package com.celzero.bravedns.util

enum class LogLifespan (val id: Long, val lifespanName: String, val lifespanHours: Int, val purgeInterval: Long) {
    ONE_HOUR(0, "1 hour", 1, 30L),
    THREE_HOURS(1, "3 hours", 3, 1L),
    SIX_HOURS(2, "6 hours", 6, 3L),
    TWELVE_HOURS(3, "12 hours", 12, 6L),
    ONE_DAY(4, "1 day", 24, 12L),
    THREE_DAYS(5, "3 days", 72, 24L),
    SEVEN_DAYS(6, "7 days", 168, 24L);

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

        fun getLifespanNames(): Array<String> {
            val names = mutableListOf<String>()
            for (entry in entries) {
                names.add(entry.lifespanName)
            }
            return names.toTypedArray()
        }

        fun getLifespanName(id: Long): String {
            return getLogLifespan(id).lifespanName
        }

        fun getLifespanHours(id: Long): Int {
            return getLogLifespan(id).lifespanHours
        }

        fun getPurgeInterval(id: Long): Long {
            return getLogLifespan(id).purgeInterval
        }
    }
}