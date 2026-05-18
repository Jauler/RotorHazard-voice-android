package com.rhvoice.service

import org.json.JSONObject

object RaceEvents {
    // Values per RotorHazard `RHRace.RaceStatus` (RHRace.py:1872).
    const val READY = 0
    const val RACING = 1
    const val DONE = 2
    const val STAGING = 3

    data class Phonetic(
        val phonetic: String,
        val pilot: String?,
        val callsign: String?,
        val lap: Int?,
    )

    data class PhoneticLeader(
        val pilot: String,
        val callsign: String?,
    )

    data class RaceStatusPayload(
        val status: Int,
        val stagingTones: Int,
        val piStartsAtS: Double,
        val piStagingAtS: Double,
        val raceTimeSec: Double,
        val unlimitedTime: Boolean,
    ) {
        val stagingGapSec: Double get() = (piStartsAtS - piStagingAtS).coerceAtLeast(0.0)
    }

    fun parsePhonetic(args: Array<Any?>?): Phonetic? {
        val obj = args?.firstOrNull() as? JSONObject ?: return null
        val phon = obj.optString("phonetic", "").takeIf { it.isNotBlank() } ?: return null
        return Phonetic(
            phonetic = phon,
            pilot = obj.optString("pilot").ifBlank { null },
            callsign = obj.optString("callsign").ifBlank { null },
            lap = if (obj.has("lap") && !obj.isNull("lap")) obj.optInt("lap") else null,
        )
    }

    fun parsePhoneticText(args: Array<Any?>?): String? {
        val obj = args?.firstOrNull() as? JSONObject ?: return null
        return obj.optString("text").ifBlank { null }
    }

    fun parsePhoneticLeader(args: Array<Any?>?): PhoneticLeader? {
        val obj = args?.firstOrNull() as? JSONObject ?: return null
        val pilot = obj.optString("pilot").ifBlank { return null }
        return PhoneticLeader(
            pilot = pilot,
            callsign = obj.optString("callsign").ifBlank { null },
        )
    }

    fun parseRaceStatus(args: Array<Any?>?): RaceStatusPayload? {
        val obj = args?.firstOrNull() as? JSONObject ?: return null
        if (!obj.has("race_status")) return null
        return RaceStatusPayload(
            status = obj.optInt("race_status", -1),
            stagingTones = obj.optInt("staging_tones", 0),
            piStartsAtS = obj.optDouble("pi_starts_at_s", 0.0),
            piStagingAtS = obj.optDouble("pi_staging_at_s", 0.0),
            raceTimeSec = obj.optDouble("race_time_sec", 0.0),
            unlimitedTime = obj.optBoolish("unlimited_time", true),
        )
    }

    /**
     * `stage_ready` (emitted by RotorHazard's `emit_race_stage`, RHUI.py:462) is the
     * authoritative source for the staging schedule. The earlier `race_status` STAGING
     * emit fires *before* the server computes the timestamps, so its
     * pi_staging_at_s / pi_starts_at_s / staging_tones are stale zeros.
     */
    fun parseStageReady(args: Array<Any?>?): RaceStatusPayload? {
        val obj = args?.firstOrNull() as? JSONObject ?: return null
        if (!obj.has("pi_starts_at_s")) return null
        return RaceStatusPayload(
            status = STAGING,
            stagingTones = obj.optInt("staging_tones", 0),
            piStartsAtS = obj.optDouble("pi_starts_at_s", 0.0),
            piStagingAtS = obj.optDouble("pi_staging_at_s", 0.0),
            raceTimeSec = obj.optDouble("race_time_sec", 0.0),
            unlimitedTime = obj.optBoolish("unlimited_time", true),
        )
    }

    // RotorHazard sends `unlimited_time` as integer 0/1 (Python's `False`/`True` over Flask-SocketIO).
    // Android's `JSONObject.optBoolean` only coerces actual JSON booleans and "true"/"false" strings,
    // so an integer 0 silently returns the default. Treat any non-zero number as true.
    private fun JSONObject.optBoolish(name: String, default: Boolean): Boolean = when (val v = opt(name)) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v.equals("true", ignoreCase = true) || v == "1"
        null -> default
        else -> default
    }
}
