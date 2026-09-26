package mob.dev.game_dvor_kombat

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.yandex.mobile.ads.common.AdError
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import com.yandex.mobile.ads.common.YandexAds
import com.yandex.mobile.ads.interstitial.InterstitialAd
import com.yandex.mobile.ads.interstitial.InterstitialAdEventListener
import com.yandex.mobile.ads.interstitial.InterstitialAdLoadListener
import com.yandex.mobile.ads.interstitial.InterstitialAdLoader
import com.yandex.mobile.ads.rewarded.Reward
import com.yandex.mobile.ads.rewarded.RewardedAd
import com.yandex.mobile.ads.rewarded.RewardedAdEventListener
import com.yandex.mobile.ads.rewarded.RewardedAdLoadListener
import com.yandex.mobile.ads.rewarded.RewardedAdLoader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Менеджер полноэкранной рекламы (Yandex Mobile Ads SDK 8.4.0).
 *
 * API 8.x: инициализация через YandexAds.initialize(), загрузка через
 * InterstitialAdLoader.loadAd(AdRequest, InterstitialAdLoadListener),
 * показ — interstitialAd.show(activity), события — InterstitialAdEventListener.
 *
 * Используется демо-адмерник полноэкранной рекламы: "demo-interstitial-yandex"
 * (всегда возвращает тестовое рекламное объявление).
 * Когда получите реальный адмерник в кабинете Yandex ADM, замените [AD_UNIT_ID].
 */
object AdsManager {

    private const val TAG = "AdsManager"

    /** Демо ID полноэкранной рекламы Yandex Mobile Ads. */
    const val AD_UNIT_ID = "demo-interstitial-yandex"

    /** Демо ID вознаграждаемой (rewarded) рекламы Yandex Mobile Ads. */
    const val REWARD_AD_UNIT_ID = "demo-rewarded-yandex"

    private var loader: InterstitialAdLoader? = null
    private var interstitialAd: InterstitialAd? = null
    private val loading = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)

    private var rewardLoader: RewardedAdLoader? = null
    private var rewardedAd: RewardedAd? = null
    private val rewardLoading = AtomicBoolean(false)
    private val rewardReady = AtomicBoolean(false)

    /** Инициализация SDK. Вызывать один раз из Activity.onCreate(). */
    fun init(context: Context) {
        YandexAds.initialize(context.applicationContext) {
            Log.d(TAG, "YandexAds initialized")
            load(context)
            loadReward(context)
        }
    }

    /** Загрузка полноэкранной рекламы. */
    fun load(context: Context) {
        if (loading.getAndSet(true)) return
        val l = loader ?: InterstitialAdLoader(context.applicationContext).also { loader = it }
        val request = AdRequest.Builder(AD_UNIT_ID).build()
        l.loadAd(request, object : InterstitialAdLoadListener {
            override fun onAdLoaded(ad: InterstitialAd) {
                Log.d(TAG, "Interstitial loaded")
                interstitialAd = ad
                ready.set(true)
                loading.set(false)
            }

            override fun onAdFailedToLoad(error: AdRequestError) {
                Log.w(TAG, "Interstitial load failed: ${error.description}")
                loading.set(false)
                // повторная попытка через 10 секунд
                Handler(Looper.getMainLooper()).postDelayed({ load(context) }, 10_000)
            }
        })
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
        ad.setAdEventListener(object : InterstitialAdEventListener {
            override fun onAdShown() {
                Log.d(TAG, "Interstitial shown")
            }

            override fun onAdFailedToShow(error: AdError) {
                Log.w(TAG, "Interstitial show failed: ${error.description}")
                interstitialAd = null
                load(activity)
            }

            override fun onAdDismissed() {
                Log.d(TAG, "Interstitial dismissed")
                interstitialAd = null
                // загрузить следующий показ
                load(activity)
            }

            override fun onAdClicked() {
                Log.d(TAG, "Interstitial clicked")
            }

            override fun onAdImpression(impressionData: ImpressionData?) {
                Log.d(TAG, "Interstitial impression")
            }
        })
        return try {
            ad.show(activity)
            true
        } catch (e: Exception) {
            Log.e(TAG, "show() error", e)
            ready.set(true)
            false
        }
    }

    // ==================== REWARDED (награждаемая реклама) ====================

    /** Загрузка rewarded-рекламы для кнопки «Купить монеты за рекламу». */
    fun loadReward(context: Context) {
        if (rewardLoading.getAndSet(true)) return
        val l = rewardLoader ?: RewardedAdLoader(context.applicationContext).also { rewardLoader = it }
        val request = AdRequest.Builder(REWARD_AD_UNIT_ID).build()
        l.loadAd(request, object : RewardedAdLoadListener {
            override fun onAdLoaded(ad: RewardedAd) {
                Log.d(TAG, "Rewarded ad loaded")
                rewardedAd = ad
                rewardReady.set(true)
                rewardLoading.set(false)
            }

            override fun onAdFailedToLoad(error: AdRequestError) {
                Log.w(TAG, "Rewarded load failed: ${error.description}")
                rewardLoading.set(false)
                Handler(Looper.getMainLooper()).postDelayed({ loadReward(context) }, 10_000)
            }
        })
    }

    /**
     * Показать rewarded-рекламу.
     * @param onResult вызывается в UI-потоке: true — пользователь досмотрел и получил награду.
     * @return false, если реклама не готова (награда не начисляется).
     */
    fun showReward(activity: Activity, onResult: (Boolean) -> Unit): Boolean {
        val ad = rewardedAd ?: run {
            Log.w(TAG, "Rewarded ad not ready")
            loadReward(activity)
            return false
        }
        if (!rewardReady.compareAndSet(true, false)) return false
        var delivered = false
        fun deliver(value: Boolean) {
            if (delivered) return
            delivered = true
            Handler(Looper.getMainLooper()).post { onResult(value) }
        }
        rewardEarned = false
        ad.setAdEventListener(object : RewardedAdEventListener {
            override fun onAdShown() {
                Log.d(TAG, "Rewarded shown")
            }

            override fun onAdFailedToShow(error: AdError) {
                Log.w(TAG, "Rewarded show failed: ${error.description}")
                rewardedAd = null
                deliver(false)
                loadReward(activity)
            }

            override fun onRewarded(reward: Reward) {
                Log.d(TAG, "Rewarded: ${reward.amount} x ${reward.type}")
                rewardEarned = true
                deliver(true)
            }

            override fun onAdDismissed() {
                Log.d(TAG, "Rewarded dismissed")
                rewardedAd = null
                // Если onRewarded не сработал — награды нет (пользователь не досмотрел)
                deliver(rewardEarned)
                loadReward(activity)
            }

            override fun onAdClicked() {
                Log.d(TAG, "Rewarded clicked")
            }

            override fun onAdImpression(impressionData: ImpressionData?) {
                Log.d(TAG, "Rewarded impression")
            }
        })
        return try {
            ad.show(activity)
            true
        } catch (e: Exception) {
            Log.e(TAG, "showReward() error", e)
            rewardReady.set(true)
            deliver(false)
            false
        }
    }

    private var rewardEarned = false
}
