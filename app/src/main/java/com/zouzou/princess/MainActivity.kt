package com.zouzou.princess

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: CustomSwipeRefreshLayout
    private lateinit var errorView: View
    private lateinit var loadingView: View
    private var wakeLock: PowerManager.WakeLock? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null

    companion object {
        const val TARGET_URL = "https://zouzou-princess-fan.replit.app/dashboard"
    }

    /** 文件选择回调 */
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (filePathCallback == null) return@registerForActivityResult

        val uris: Array<Uri>? = when {
            result.resultCode == RESULT_OK && result.data?.data != null -> {
                arrayOf(result.data!!.data!!)
            }
            result.resultCode == RESULT_OK && result.data?.clipData != null -> {
                val clipData = result.data!!.clipData!!
                Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
            }
            result.resultCode == RESULT_OK && cameraPhotoUri != null -> {
                arrayOf(cameraPhotoUri!!)
            }
            else -> null
        }

        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
        cameraPhotoUri = null
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

                override fun onShowFileChooser(
                    webView: WebView?,
                    callback: ValueCallback<Array<Uri>>?,
                    params: FileChooserParams?
                ): Boolean {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = callback

                    val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "image/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }

                    val cameraIntent = createCameraIntent()

                    val chooser = Intent.createChooser(contentIntent, "选择图片").apply {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
                    }

                    fileChooserLauncher.launch(chooser)
                    return true
                }
            }
        }
    }

    /**
     * 注入 JS 滚动检测器（方案1：触摸目标追踪）
     *
     * 核心思路：touchstart 时拿到手指下方的元素 (e.target)，
     * 沿 parent 链向上找到最近的可滚动祖先，只检查它的 scrollTop。
     *
     * - 弹窗打开，手指在弹窗上 → 找到弹窗滚动容器 → scrollTop=0 → 允许刷新
     * - 弹窗内容滚到中间 → scrollTop>0 → 禁止刷新
     * - 弹窗关闭，手指在底层页面 → 找到页面滚动容器 → 正常判断
     *
     * 这样不管弹窗还是底层页面，只关心手指当前触摸的容器，
     * 不受其他容器残留 scrollTop 的影响。
     */
    private fun injectScrollDetector(view: WebView?) {
        val js = """
            (function() {
                if (window.__zouzouScrollInjected) return;
                window.__zouzouScrollInjected = true;

                var currentScrollable = null;
                var currentCanScrollUp = false;

                function isScrollable(el) {
                    if (!el || el === document) return false;
                    var style = window.getComputedStyle(el);
                    if (style.overflowY === 'hidden' || style.overflowY === 'visible') return false;
                    return el.scrollHeight > el.clientHeight && el.clientHeight > 0;
                }

                function findScrollableAncestor(el) {
                    var node = el;
                    while (node && node !== document.body && node !== document.documentElement) {
                        if (isScrollable(node)) return node;
                        node = node.parentElement;
                    }
                    return null;
                }

                function checkElement(el) {
                    if (!el) {
                        currentCanScrollUp = window.scrollY > 0 || window.pageYOffset > 0
                            || (document.documentElement && document.documentElement.scrollTop > 0)
                            || (document.body && document.body.scrollTop > 0);
                    } else {
                        currentCanScrollUp = el.scrollTop > 0;
                    }
                    if (window.AndroidScroll) {
                        window.AndroidScroll.onScrollState(currentCanScrollUp);
                    }
                }

                document.addEventListener('touchstart', function(e) {
                    var target = e.target;
                    currentScrollable = findScrollableAncestor(target);
                    checkElement(currentScrollable);
                }, true);

                document.addEventListener('touchmove', function(e) {
                    checkElement(currentScrollable);
                }, true);

                document.addEventListener('scroll', function(e) {
                    if (e.target === document) {
                        if (!currentScrollable) checkElement(null);
                    } else if (e.target === currentScrollable) {
                        checkElement(currentScrollable);
                    }
                }, true);

                setTimeout(function() { checkElement(null); }, 200);
                setTimeout(function() { checkElement(null); }, 1000);
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

    /**
     * 创建拍照 Intent，生成临时文件供相机写入
     */
    private fun createCameraIntent(): Intent {
        val photoFile = File.createTempFile(
            "camera_",
            ".jpg",
            getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        )
        cameraPhotoUri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            photoFile
        )
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
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
