package com.analytic.atribution.gb

import android.os.Bundle
import android.view.View
import com.analytic.atribution.gb.appsflyer.AppsFlyerInfo
import com.analytic.atribution.gb.clarity.ClarityInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/**
 * Caller friendly API of the analytic library. All methods can be called from any context,
 * not blocking and never throws.
 *
 * Initialization is automatic, but you must call [setHost]
 * with the ip address or domain name of the server for it to be possible.
 *
 * Other methods are optional.
 */
object GPAnalytic {
    /**
     * Hot flow that emits each AppsFlyer conversion payload as a [Bundle]. Carries every
     * entry from the AppsFlyer conversion map, untouched — apps read whichever keys they
     * care about (`deep_link_value`, `deep_link_sub2`, `WebUserID`, etc.). Replays the
     * most recent payload to new subscribers, including the cached payload from a previous
     * process if present.
     */
    val appsFlyerConversionFlow: Flow<Bundle> = flow {
        emitAll(Locator.resolve<StatisticService>().appsFlyerConversionFlow())
    }

    /**
     * Sets the hostname of the analytic server. Note that you have
     * 5 seconds to call this function, otherwise initialization process will be aborted.
     *
     * @param host Hostname of the server. For example: `analytic.com`, `127.0.0.1`.
     * @param secure Whether to use secure protocols (https instead of http etc.)
     */
    fun setHost(host: String, secure: Boolean = false) {
        scope.launch {
            Locator.resolve<StatisticService>().setHost(host, secure)
        }
    }

    /**
     * Saves additional data locally and triggers http request if called with new data, so
     * it's better to provide all additional data in one call if possible.
     *
     * Nulls treated as "no value" and will not delete previously set values.
     */
    fun setAdditionalUserData(
        affiseClickId: String? = null,
        affisePromoCode: String? = null,
        webCustomerId: String? = null,
    ) {
        scope.launch {
            Locator.resolve<StatisticService>().setAdditionalUserData(
                affiseClickId = affiseClickId,
                affisePromoCode = affisePromoCode,
                webCustomerId = webCustomerId,
            )
        }
    }

    /**
     * Adds custom event to the queue. They'll be sent to the server once there's more than 10
     * events in the queue or more than 10 seconds passed from oldest event in the queue. Currently
     * there's no way to trigger flush externally.
     *
     * The same event is also forwarded to AppsFlyer when the SDK is initialized
     * (i.e. the console returned a non-empty `devKey` and the package check passed). When
     * AppsFlyer is not initialized this side-effect is a no-op.
     *
     * Note that all not custom events are managed by SDK
     * automatically, only use this for custom events like `screen_open`, `button_pressed` etc.
     *
     * @param name Name of the event.
     * @param parameters Parameters of the event. Provided bundle must contain only json
     * compatible primitives types (e.g. one of [Int], [Float], [String], [Boolean], null).
     * Defaults to empty bundle
     * @param timestamp Timestamp of when event occur. Defaults to [System.currentTimeMillis]
     */
    fun sendEvent(
        name: String,
        parameters: Bundle? = null,
        timestamp: Long? = null
    ) {
        val jsonObject = JSONObject()
        val afParams = mutableMapOf<String, Any>()
        parameters?.keySet()?.forEach { key ->
            val value = parameters.get(key)
            jsonObject.put(key, value)
            if (value != null) afParams[key] = value
        }

        val event = Event(
            UUID.randomUUID().toString(),
            timestamp ?: System.currentTimeMillis(),
            name,
            jsonObject
        )

        scope.launch {
            val service = Locator.resolve<StatisticService>()
            service.enqueueEvent(event)
            service.logAppsFlyerEvent(name, afParams)
        }
    }

    /**
     * Returns the AppsFlyer configuration delivered by the analytics admin console. Suspends
     * until the configuration has been fetched. If the fetch fails, returns
     * [AppsFlyerInfo.EMPTY] (devKey null, empty subscription id list).
     *
     * Apps typically use this to read [AppsFlyerInfo.web2AppSubscriptionIds] and decide which
     * keys to react to in the conversion broadcast. The library itself uses devKey,
     * isDebugMode, and isLoggingEnabled to drive AppsFlyer SDK initialization.
     */
    suspend fun getAppsFlyerInfo(): AppsFlyerInfo {
        return Locator.resolve<StatisticService>().awaitAppsFlyerInfo()
    }

    /**
     * Returns the most recent AppsFlyer conversion payload as a [Bundle], or null if no
     * conversion has been received yet. Useful for receivers that registered after the
     * initial broadcast was emitted (e.g., on process restart).
     */
    suspend fun getLastAppsFlyerConversion(): Bundle? {
        return Locator.resolve<StatisticService>().lastAppsFlyerConversionBundle()
    }

    /**
     * Forwards GDPR consent state to the AppsFlyer SDK. Pass `true` for GDPR-region users
     * who have granted consent, `false` for GDPR-region users who declined, and `null` for
     * non-GDPR users.
     */
    fun setAppsFlyerGdprConsent(hasGdpr: Boolean?) {
        scope.launch {
            Locator.resolve<StatisticService>().setAppsFlyerGdprConsent(hasGdpr)
        }
    }

    /**
     * Returns the Microsoft Clarity configuration delivered by the analytics admin console.
     * Suspends until the configuration has been fetched. If the fetch fails, returns
     * [ClarityInfo.EMPTY] (projectId null) and Clarity is not initialized.
     */
    suspend fun getClarityInfo(): ClarityInfo {
        return Locator.resolve<StatisticService>().awaitClarityInfo()
    }

    /**
     * Forwards GDPR consent state to the Clarity SDK so session recording respects the
     * user's choice. May be called before Clarity has initialized — the value is buffered
     * and applied as soon as init completes. No-op if `projectId` was empty (Clarity
     * disabled).
     */
    fun setClarityConsent(hasConsent: Boolean) {
        scope.launch {
            Locator.resolve<StatisticService>().setClarityConsent(hasConsent)
        }
    }

    /**
     * Masks an XML/Android View from Clarity session recordings. No-op when Clarity is
     * not initialized.
     */
    fun maskClarityView(view: View) {
        scope.launch {
            Locator.resolve<StatisticService>().maskClarityView(view)
        }
    }

    /**
     * Removes a previously applied [maskClarityView] directive. No-op when Clarity is
     * not initialized.
     */
    fun unmaskClarityView(view: View) {
        scope.launch {
            Locator.resolve<StatisticService>().unmaskClarityView(view)
        }
    }

    /**
     * Signals that the user has just landed on a paywall screen. The first call ever locks
     * in `time_to_paywall_ms` (millis since install), `actions_before_paywall`,
     * `inters_shown_before_paywall`, `aoa_shown_before_paywall`. Subsequent calls are
     * no-ops. Locked values persist across process death and ship on the next user-data
     * POST (triggered automatically by this call).
     */
    fun notifyPaywallOpened() {
        scope.launch {
            Locator.resolve<StatisticService>().notifyPaywallOpened()
        }
    }

    /**
     * Signals that the user pressed the "Buy" button. The first call after the first
     * [notifyPaywallOpened] locks in `paywall_conversion_time_us`. No-op if called before
     * a paywall_opened signal or if already locked.
     */
    fun notifyPurchaseStarted() {
        scope.launch {
            Locator.resolve<StatisticService>().notifyPurchaseStarted()
        }
    }

    /**
     * Signals that the billing flow has finished (subscription granted / purchase
     * completed). The first call after the first [notifyPurchaseStarted] locks in
     * `click_to_pay_time_s`. No-op if called before a purchase_started signal or if
     * already locked.
     */
    fun notifyPurchaseCompleted() {
        scope.launch {
            Locator.resolve<StatisticService>().notifyPurchaseCompleted()
        }
    }

    /**
     * Increments the count of interstitial ads shown. Frozen into
     * `inters_shown_before_paywall` on the first [notifyPaywallOpened].
     */
    fun notifyInterstitialShown() {
        scope.launch {
            Locator.resolve<StatisticService>().notifyInterstitialShown()
        }
    }

    /**
     * Increments the count of App Open Ads shown. Frozen into
     * `aoa_shown_before_paywall` on the first [notifyPaywallOpened].
     */
    fun notifyAoaShown() {
        scope.launch {
            Locator.resolve<StatisticService>().notifyAoaShown()
        }
    }

    /**
     * Increments the count of arbitrary user actions (taps / clicks / interactions) before
     * the first paywall. Frozen into `actions_before_paywall` on the first
     * [notifyPaywallOpened]. Typical wiring: call from a base Activity / Fragment in the
     * "user did something" hook.
     */
    fun notifyUserAction() {
        scope.launch {
            Locator.resolve<StatisticService>().notifyUserAction()
        }
    }

    /**
     * Stores the user's GDPR consent status as a user property (`gdpr_consent_status`).
     * Accepts only `"accepted"`, `"rejected"`, `"unknown"`. Other values are ignored with
     * a warning log. Defaults to `"unknown"` until set.
     *
     * Independent of [setAppsFlyerGdprConsent] / [setClarityConsent], which forward consent
     * to the respective SDKs.
     */
    fun setGdprConsent(status: String) {
        scope.launch {
            Locator.resolve<StatisticService>().setGdprConsent(status)
        }
    }
}
