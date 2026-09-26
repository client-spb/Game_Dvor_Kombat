package mob.dev.game_dvor_kombat

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Полноценное Android-приложение для игры «Двор-Комбат».
 * Игра (HTML5 Canvas) полностью офлайн, лежит в assets/web/index.html.
 * Платформенный SDK (Макс), лидерборды и метрика из игры удалены.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Игра всегда горизонтальная — фиксируем ландшафт нативно
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        enableEdgeToEdge()
        // Не даём экрану гаснуть во время боя
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Инициализация Yandex Mobile Ads SDK + предзагрузка полноэкранной рекламы
        AdsManager.init(this)
        // Во время показа рекламы ставим игру/музыку на паузу, после закрытия — снимаем
        AdsManager.onAdShown = { pauseForAd() }
        AdsManager.onAdClosed = { resumeAfterAd() }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GameWebView()
                }
            }
        }
    }

    /** Мост JS -> Android: нативный шеринг, вибрация, реклама. */
    inner class AndroidBridge {

        @JavascriptInterface
        fun share(text: String) {
            // К сообщению всегда добавляем ссылку на приложение в RuStore
            val full = text + "\nhttps://www.rustore.ru/catalog/app/mob.dev.game_dvor_kombat"
            runOnUiThread {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, full)
                }
                startActivity(Intent.createChooser(intent, "Поделиться"))
            }
        }

        @JavascriptInterface
        fun vibrate(ms: Long) {
            val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator?.vibrate(
                VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        }

        /** Вызывается из игры при поражении — показываем полноэкранную рекламу. */
        @JavascriptInterface
        fun onGameLose() {
            runOnUiThread { AdsManager.show(this@MainActivity) }
        }

        /**
         * Запрос показа вознаграждаемой рекламы (кнопка «Монеты за рекламу» в качалке).
         * Результат возвращается в JS колбэком window.onRewardResult(true|false).
         */
        @JavascriptInterface
        fun showRewardAd() {
            runOnUiThread {
                val shown = AdsManager.showReward(this@MainActivity) { earned ->
                    webView?.evaluateJavascript(
                        "window.onRewardResult && window.onRewardResult($earned)", null
                    )
                }
                if (!shown) {
                    webView?.evaluateJavascript(
                        "window.onRewardResult && window.onRewardResult(false)", null
                    )
                }
            }
        }

        /** Открыть внешнюю ссылку во внешнем браузере/приложении (канал Макс и т.п.). */
        @JavascriptInterface
        fun openUrl(url: String) {
            runOnUiThread {
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                    Log.w("MainActivity", "openUrl failed", e)
                }
            }
        }
    }

    private var webView: WebView? = null

    /** Пауза игры и музыки на время показа рекламы (вызывается из UI-потока). */
    private fun pauseForAd() {
        runOnUiThread {
            webView?.evaluateJavascript(
                "(window.onAdShown && window.onAdShown()) || void 0", null
            )
        }
    }

    /** Снятие паузы после закрытия рекламы. */
    private fun resumeAfterAd() {
        runOnUiThread {
            webView?.evaluateJavascript(
                "(window.onAdClosed && window.onAdClosed()) || void 0", null
            )
            // Синхронизируем состояние rewarded-рекламы с кнопкой в качалке
            val ok = AdsManager.rewardReadyState()
            webView?.evaluateJavascript(
                "(window.onRewardReady && window.onRewardReady($ok)) || void 0", null
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun GameWebView() {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(Color.parseColor("#121314"))
                    systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        )
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true // сохранения игры (localStorage)
                        allowFileAccess = false
                        allowContentAccess = false
                        mediaPlaybackRequiresUserGesture = false
                    }
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    addJavascriptInterface(this@MainActivity.AndroidBridge(), "AndroidBridge")
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            // Игра полностью офлайн: внешние http(s)-ссылки не открываем
                            return request.url.scheme?.startsWith("http") == true
                        }
                        override fun onPageFinished(view: WebView, url: String) {
                            // Сообщаем игре текущее состояние рекламы сразу после загрузки страницы
                            val ok = AdsManager.rewardReadyState()
                            view.evaluateJavascript(
                                "(window.onRewardReady && window.onRewardReady($ok)) || void 0", null
                            )
                        }
                    }
                    loadUrl("file:///android_asset/web/index.html")
                }.also { wv ->
                    webView = wv
                    // AdsManager сможет слать события о готовности рекламы прямо в игру
                    AdsManager.setWebViewEvaluator { js ->
                        runOnUiThread { webView?.evaluateJavascript(js, null) }
                    }
                }
            }
        )
    }
}
