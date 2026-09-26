package mob.dev.game_dvor_kombat

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.yandex.mobile.ads.common.AdError
import com.yandex.mobile.ads.common.InitializationListener
import com.yandex.mobile.ads.common.MobileAds
import com.yandex.mobile.ads.interstitial.InterstitialAd
import com.yandex.mobile.ads.interstitial.InterstitialAdLoadingListener
import com.yandex.mobile.ads.interstitial.InterstitialAdShowListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Менеджер полноэкранной рекламы (Yandex Mobile Ads SDK 8.0.0).
 *
 * Используется демо-адмерник полноэкранной рекламы: "demo-interstitial-yandex"
 * (всегда возвращает тестовое рекламное объявление).
 * Когда получите реальный адмерник в кабинете Yandex ADM, замените [AD_UNIT_ID].
 */
object AdsManager {

    private const val TAG = "AdsManager"

    /** Демо ID полноэкранной рекламы Yandex Mobile Ads. */
    const val AD_UNIT_ID = "demo-interstitial-yandex"

    private var interstitialAd: InterstitialAd? = null
    private val loading = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)

    /** Инициализация SDK. Вызывать один раз из Activity.onCreate(). */
    fun init(activity: Activity) {
        MobileAds.initialize(
            activity.applicationContext,
            InitializationListener {
                Log.d(TAG, "MobileAds initialized")
                load(activity)
            }
        )
    }

    /** Загрузка полноэкранной рекламы. */
    fun load(activity: Activity) {
        if (loading.getAndSet(true)) return
        val ad = InterstitialAd()
        ad.adUnitId = AD_UNIT_ID
        ad.setInterstitialAdLoadingListener(object : InterstitialAdLoadingListener {
            override fun onInterstitialAdLoaded(interstitial: InterstitialAd) {
                Log.d(TAG, "Interstitial loaded")
                interstitialAd = interstitial
                ready.set(true)
                loading.set(false)
            }

            override fun onInterstitialAdLoadFailed(adError: AdError) {
                Log.w(TAG, "Interstitial load failed: ${adError.message}")
                loading.set(false)
                // повторная попытка через 10 секунд
                Handler(Looper.getMainLooper()).postDelayed({ load(activity) }, 10_000)
            }
        })
        ad.loadAd(ad.requestParams())
    }

    /**
     * Показать полноэкранную рекламу (например, при поражении в бою).
     * @return true, если реклама была показана; false — если ещё не загружена.
     */
    fun show(activity: Activity): Boolean {
        val ad = interstitialAd ?: run {
            Log.w(TAG, "Interstitial not ready")
            load(activity)
            return false
        }
        if (!ready.compareAndSet(true, false)) return false
        ad.setInterstitialAdShowListener(object : InterstitialAdShowListener {
            override fun onAdShown() {
                Log.d(TAG, "Interstitial shown")
            }

            override fun onAdDismissed() {
                Log.d(TAG, "Interstitial dismissed")
                interstitialAd = null
                load(activity) // сразу грузим следующую
            }

            override fun onAdFailedToShow(adError: AdError) {
                Log.w(TAG, "Interstitial show failed: ${adError.message}")
                interstitialAd = null
                load(activity)
            }

            override fun onAdClicked() {
                Log.d(TAG, "Interstitial clicked")
            }
        })
        ad.show(activity, null)
        return true
    }
}
