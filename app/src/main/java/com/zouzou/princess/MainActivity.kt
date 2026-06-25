package com.zouzou.princess

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.PowerManager
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: CustomSwipeRefreshLayout
    private lateinit var errorView: View
    private lateinit var loadingView: View
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val TARGET_URL = "https://zouzou-princess-fan.replit.app/dashboard"
    }

    /** JS 回调接口，接收网页真实滚动状态 */
    inner class ScrollInterface {
        @JavascriptInterface
        fun onScrollState(canScrollUp: Boolean) {
            swipeRefresh.webCanScrollUp = canScrollUp
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        errorView = findViewById(R.id.errorView)
        loadingView = findViewById(R.id.loadingView)

        setupWebView()
        setupSwipeRefresh()
        setupErrorRetry()
        acquireWakeLock()

        if (isNetworkAvailable()) {
            webView.loadUrl(TARGET_URL)
        } else {
            showError()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                setGeolocationEnabled(true)
                mediaPlaybackRequiresUserGesture = false
                javaScriptCanOpenWindowsAutomatically = true
            }

            addJavascriptInterface(ScrollInterface(), "AndroidScroll")

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(this@MainActivity.webView, true)
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    loadingView.visibility = View.VISIBLE
                    errorView.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    loadingView.visibility = View.GONE
                    swipeRefresh.isRefreshing = false
                    CookieManager.getInstance().flush()
                    injectScrollDetector(view)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        showError()
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    return false
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress == 100) {
                        loadingView.visibility = View.GONE
                    }
                }
            }
        }
    }

    /**
     * 注入 JS 滚动检测器
     *
     * 使用 capture 模式监听所有元素的 scroll 事件，
     * 检查 window、document、body 以及所有可滚动 div 的 scrollTop。
     * 只要有任何元素 scrollTop > 0，就通知 Android "可以上滚"（禁用下拉刷新）。
     * 全部为 0 时通知 Android "不可上滚"（启用下拉刷新）。
     */
    private fun injectScrollDetector(view: WebView?) {
        val js = """
            (function() {
                if (window.__zouzouScrollInjected) return;
                window.__zouzouScrollInjected = true;

                function checkScroll() {
                    var canUp = false;

                    if (window.scrollY > 0) canUp = true;
                    if (window.pageYOffset > 0) canUp = true;
                    if (document.documentElement && document.documentElement.scrollTop > 0) canUp = true;
                    if (document.body && document.body.scrollTop > 0) canUp = true;

                    var els = document.querySelectorAll('*');
                    for (var i = 0; i < els.length; i++) {
                        var el = els[i];
                        if (el.scrollTop > 0 && el.scrollHeight > el.clientHeight && el.clientHeight > 0) {
                            canUp = true;
                            break;
                        }
                    }

                    if (window.AndroidScroll) {
                        window.AndroidScroll.onScrollState(canUp);
                    }
                }

                window.addEventListener('scroll', checkScroll, true);
                document.addEventListener('scroll', checkScroll, true);
                window.addEventListener('touchmove', checkScroll, true);
                setTimeout(checkScroll, 200);
                setTimeout(checkScroll, 1000);
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setColorSchemeResources(
            android.R.color.holo_blue_bright,
            android.R.color.holo_purple,
            android.R.color.holo_orange_light
        )
        swipeRefresh.setOnRefreshListener {
            if (isNetworkAvailable()) {
                webView.reload()
            } else {
                swipeRefresh.isRefreshing = false
                Toast.makeText(this, R.string.no_network, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupErrorRetry() {
        errorView.setOnClickListener {
            if (isNetworkAvailable()) {
                webView.visibility = View.VISIBLE
                errorView.visibility = View.GONE
                loadingView.visibility = View.VISIBLE
                webView.reload()
            } else {
                Toast.makeText(this, R.string.no_network, Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ZouzouPrincess::Heartbeat"
        )
        wakeLock?.acquire()
    }

    private fun showError() {
        loadingView.visibility = View.GONE
        swipeRefresh.isRefreshing = false
        webView.visibility = View.GONE
        errorView.visibility = View.VISIBLE
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire()
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onDestroy() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        webView.destroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }
}
