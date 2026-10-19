package com.m57.hermescontrol.ui.cron

/** Unknown gateway states expose no state-changing control. */
enum class CronJobDisplayState {
    ACTIVE,
    PAUSED,
    COMPLETED,
    ERROR,
    UNKNOWN,
    ;

    val canPause: Boolean get() = this == ACTIVE
    val canResume: Boolean get() = this == PAUSED || this == ERROR

    companion object {
        fun from(state: String?): CronJobDisplayState =
            when (state) {
                "scheduled", "active" -> ACTIVE
                "paused" -> PAUSED
                "completed" -> COMPLETED
                "error" -> ERROR
                else -> UNKNOWN
            }
    }
}
