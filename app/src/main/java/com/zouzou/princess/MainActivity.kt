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
import android.view.ViewTreeObserver
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var errorView: View
    private lateinit var loadingView: View
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val TARGET_URL = "https://zouzou-princess-fan.replit.app/dashboard"
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
        setupScrollListener()
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

            // Cookie 管理 - 心跳检测依赖 session cookie
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
                    // 确保 Cookie 同步
                    CookieManager.getInstance().flush()
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

    private fun setupSwipeRefresh() {
        swipeRefresh.setColorSchemeResources(
            android.R.color.holo_blue_bright,
            android.R.color.holo_purple,
            android.R.color.holo_orange_light
        )
        // 默认禁用，只在页面滚动到顶部时才启用
        swipeRefresh.isEnabled = false
        swipeRefresh.setOnRefreshListener {
            if (isNetworkAvailable()) {
                webView.reload()
            } else {
                swipeRefresh.isRefreshing = false
                Toast.makeText(this, R.string.no_network, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 监听 WebView 滚动位置：
     * 只有当页面滚动到最顶部 (scrollY == 0) 时才启用下拉刷新，
     * 页面在中间或底部滚动时不触发刷新，避免误触。
     */
    private fun setupScrollListener() {
        webView.viewTreeObserver.addOnScrollChangedListener(
            ViewTreeObserver.OnScrollChangedListener {
                swipeRefresh.isEnabled = (webView.scrollY == 0)
            }
        )
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

    /**
     * 获取 WakeLock 防止 CPU 休眠导致心跳检测中断
     */
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
        // 保持 wakeLock，心跳检测在后台也需要运行
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
