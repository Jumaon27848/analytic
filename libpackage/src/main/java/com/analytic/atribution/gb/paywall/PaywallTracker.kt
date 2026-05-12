package com.analytic.atribution.gb.paywall

import android.content.Context
import android.util.Log
import com.analytic.atribution.gb.Constants
import com.analytic.atribution.gb.getFirstOpenTimestamp
import com.analytic.atribution.gb.sharedPreferences

/**
 * Tracks the first-paywall funnel: counts host-app actions, ads shown, and the time to the
 * first paywall, plus the conversion timings from paywall to purchase started and from
 * purchase started to purchase completed. All metrics are first-only (locked once and never
 * overwritten) and persist across process death via [sharedPreferences].
 *
 * The library does not introspect activities or touches; the host app must call the
 * [com.analytic.atribution.gb.GPAnalytic] notify methods at the appropriate points.
 */
internal class PaywallTracker(private val context: Context) {

    private companion object {
        const val ACTIONS_COUNT = "paywall.actions_count"
        const val INTERS_COUNT = "paywall.inters_count"
        const val AOA_COUNT = "paywall.aoa_count"

        const val FIRST_PAYWALL_OPENED_MS = "paywall.first_paywall_opened_ms"
        const val FIRST_PAYWALL_OPENED_NS = "paywall.first_paywall_opened_ns"
        const val FIRST_PURCHASE_STARTED_NS = "paywall.first_purchase_started_ns"

        const val TIME_TO_PAYWALL_MS = "paywall.time_to_paywall_ms"
        const val ACTIONS_BEFORE_PAYWALL = "paywall.actions_before_paywall"
        const val INTERS_BEFORE_PAYWALL = "paywall.inters_before_paywall"
        const val AOA_BEFORE_PAYWALL = "paywall.aoa_before_paywall"
        const val PAYWALL_CONVERSION_US = "paywall.paywall_conversion_us"
        const val CLICK_TO_PAY_S = "paywall.click_to_pay_s"

        const val GDPR_STATUS = "paywall.gdpr_status"

        const val GDPR_ACCEPTED = "accepted"
        const val GDPR_REJECTED = "rejected"
        const val GDPR_UNKNOWN = "unknown"
    }

    fun notifyPaywallOpened() {
        synchronized(this) {
            val prefs = sharedPreferences(context)
            if (prefs.contains(FIRST_PAYWALL_OPENED_NS)) {
                Log.d(Constants.LOG_TAG, "paywall already locked, ignoring notifyPaywallOpened")
                return
            }
            val nowMs = System.currentTimeMillis()
            val nowNs = System.nanoTime()
            val firstOpen = getFirstOpenTimestamp(context)
            val timeToPaywallMs = if (firstOpen != null) (nowMs - firstOpen).coerceAtLeast(0L) else null
            val actions = prefs.getLong(ACTIONS_COUNT, 0L)
            val inters = prefs.getLong(INTERS_COUNT, 0L)
            val aoa = prefs.getLong(AOA_COUNT, 0L)

            val editor = prefs.edit()
                .putLong(FIRST_PAYWALL_OPENED_MS, nowMs)
                .putLong(FIRST_PAYWALL_OPENED_NS, nowNs)
                .putLong(ACTIONS_BEFORE_PAYWALL, actions)
                .putLong(INTERS_BEFORE_PAYWALL, inters)
                .putLong(AOA_BEFORE_PAYWALL, aoa)
            if (timeToPaywallMs != null) editor.putLong(TIME_TO_PAYWALL_MS, timeToPaywallMs)
            editor.apply()
            Log.i(
                Constants.LOG_TAG,
                "paywall locked: time_to=${timeToPaywallMs}ms actions=$actions inters=$inters aoa=$aoa"
            )
        }
    }

    fun notifyPurchaseStarted() {
        synchronized(this) {
            val prefs = sharedPreferences(context)
            val paywallNs = prefs.getLong(FIRST_PAYWALL_OPENED_NS, -1L)
            if (paywallNs < 0L) {
                Log.d(Constants.LOG_TAG, "notifyPurchaseStarted ignored: paywall not opened yet")
                return
            }
            if (prefs.contains(FIRST_PURCHASE_STARTED_NS)) {
                Log.d(Constants.LOG_TAG, "purchase_started already locked, ignoring")
                return
            }
            val nowNs = System.nanoTime()
            val conversionUs = ((nowNs - paywallNs) / 1_000L).coerceAtLeast(0L)
            prefs.edit()
                .putLong(FIRST_PURCHASE_STARTED_NS, nowNs)
                .putLong(PAYWALL_CONVERSION_US, conversionUs)
                .apply()
            Log.i(Constants.LOG_TAG, "purchase_started locked: conversion=${conversionUs}us")
        }
    }

    fun notifyPurchaseCompleted() {
        synchronized(this) {
            val prefs = sharedPreferences(context)
            val startedNs = prefs.getLong(FIRST_PURCHASE_STARTED_NS, -1L)
            if (startedNs < 0L) {
                Log.d(Constants.LOG_TAG, "notifyPurchaseCompleted ignored: purchase not started yet")
                return
            }
            if (prefs.contains(CLICK_TO_PAY_S)) {
                Log.d(Constants.LOG_TAG, "click_to_pay already locked, ignoring")
                return
            }
            val nowNs = System.nanoTime()
            val seconds = ((nowNs - startedNs) / 1_000_000_000L).coerceAtLeast(0L)
            prefs.edit().putLong(CLICK_TO_PAY_S, seconds).apply()
            Log.i(Constants.LOG_TAG, "purchase_completed locked: click_to_pay=${seconds}s")
        }
    }

    fun notifyInterstitialShown() { increment(INTERS_COUNT) }
    fun notifyAoaShown() { increment(AOA_COUNT) }
    fun notifyUserAction() { increment(ACTIONS_COUNT) }

    private fun increment(key: String) {
        synchronized(this) {
            val prefs = sharedPreferences(context)
            val next = prefs.getLong(key, 0L) + 1L
            prefs.edit().putLong(key, next).apply()
        }
    }

    fun setGdprConsent(status: String) {
        val normalized = when (status) {
            GDPR_ACCEPTED, GDPR_REJECTED, GDPR_UNKNOWN -> status
            else -> {
                Log.w(Constants.LOG_TAG, "setGdprConsent ignored: invalid status \"$status\"")
                return
            }
        }
        sharedPreferences(context).edit().putString(GDPR_STATUS, normalized).apply()
    }

    fun getGdprStatus(): String? = sharedPreferences(context).getString(GDPR_STATUS, null)

    fun getPaywallFields(): Map<String, Long?> {
        val prefs = sharedPreferences(context)
        fun read(key: String): Long? = prefs.getLong(key, -1L).takeIf { it >= 0L }
        return mapOf(
            "time_to_paywall" to read(TIME_TO_PAYWALL_MS),
            "actions_before_paywall" to read(ACTIONS_BEFORE_PAYWALL),
            "inters_shown_before_paywall" to read(INTERS_BEFORE_PAYWALL),
            "aoa_shown_before_paywall" to read(AOA_BEFORE_PAYWALL),
            "paywall_conversion_time" to read(PAYWALL_CONVERSION_US),
            "click_to_pay_time" to read(CLICK_TO_PAY_S),
        )
    }
}
