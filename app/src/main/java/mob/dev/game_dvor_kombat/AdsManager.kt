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
 * Используются боевые адмерники: interstitial "R-M-20122412-1", rewarded "R-M-20122412-2".
 */
object AdsManager {

    private const val TAG = "AdsManager"

    /** Колбэки жизненного цикла рекламы (устанавливаются из MainActivity). */
    @Volatile var onAdShown: (() -> Unit)? = null
    @Volatile var onAdClosed: (() -> Unit)? = null

    /** Мост к WebView игры (устанавливается из MainActivity) для JS-колбэков. */
    @Volatile private var webEval: ((String) -> Unit)? = null

    fun setWebViewEvaluator(eval: ((String) -> Unit)?) { webEval = eval }

    private fun evalJs(js: String) {
        try { webEval?.invoke(js) } catch (_: Exception) {}
    }

    /** Демо ID полноэкранной рекламы Yandex Mobile Ads. */
    // Полноэкранная реклама (боевой адмерник)
    const val AD_UNIT_ID = "R-M-20122412-1"

    /** Вознаграждаемая (rewarded) реклама — боевой адмерник. */
    const val REWARD_AD_UNIT_ID = "R-M-20122412-2"

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
        appContext = context.applicationContext
        YandexAds.initialize(context.applicationContext) {
            Log.d(TAG, "YandexAds initialized")
            load(context)
            loadReward(context)
        }
    }

    private var appContext: Context? = null

    /** Текущее состояние rewarded-рекламы для WebView (вызывать при загрузке страницы). */
    fun rewardReadyState(): Boolean = rewardReady.get()

    /** Сообщить WebView о состоянии именно rewarded-рекламы (window.onRewardReady). */
    private fun notifyRewardWeb(ok: Boolean) {
        Handler(Looper.getMainLooper()).post {
            evalJs("(window.onRewardReady && window.onRewardReady($ok)) || void 0")
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
                onAdShown?.invoke()
            }

            override fun onAdFailedToShow(error: AdError) {
                Log.w(TAG, "Interstitial show failed: ${error.description}")
                interstitialAd = null
                onAdClosed?.invoke()
                load(activity)
            }

            override fun onAdDismissed() {
                Log.d(TAG, "Interstitial dismissed")
                interstitialAd = null
                onAdClosed?.invoke()
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
                notifyRewardWeb(true)
            }

            override fun onAdFailedToLoad(error: AdRequestError) {
                Log.w(TAG, "Rewarded load failed: ${error.description}")
                rewardLoading.set(false)
                notifyRewardWeb(false)
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
                onAdShown?.invoke()
            }

            override fun onAdFailedToShow(error: AdError) {
                Log.w(TAG, "Rewarded show failed: ${error.description}")
                rewardedAd = null
                onAdClosed?.invoke()
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
                onAdClosed?.invoke()
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
