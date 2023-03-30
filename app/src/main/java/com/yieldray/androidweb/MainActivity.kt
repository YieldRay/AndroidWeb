package com.yieldray.androidweb

import android.content.Intent
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.webkit.*
import android.webkit.WebView.HitTestResult
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.just.agentweb.AgentWeb
import com.just.agentweb.AgentWebUIControllerImplBase
import com.just.agentweb.MiddlewareWebChromeBase
import com.just.agentweb.MiddlewareWebClientBase
import com.yieldray.androidweb.databinding.ActivityMainBinding
import java.io.InputStream


class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var mAgentWeb: AgentWeb

    private lateinit var webServer: WebServer
    private lateinit var origin: String
    private var port: Int? = null // this is available only after the http server starts

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

        webServer = WebServer(fun(path: String): InputStream {
            return this.assets.open(path)
        })
        webServer.start()

        port = webServer.listeningPort
        origin = "http://localhost:$port"

        val context = this
        mAgentWeb = AgentWeb.with(this)
            .setAgentWebParent(binding.view, LinearLayout.LayoutParams(-1, -1))
            .useDefaultIndicator()
            .setAgentWebUIController(object : AgentWebUIControllerImplBase() {
                // agentWeb use a snackbar by default, here modify to a alert dialog
                override fun onJsAlert(view: WebView?, url: String?, message: String?) {
                    AlertDialog.Builder(context).setMessage(message)
                        .setPositiveButton("确定") { _, _ -> }
                        .show()
                }
            })
            .useMiddlewareWebChrome(object : MiddlewareWebChromeBase() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    mAgentWeb.webCreator.webView.evaluateJavascript(
                        """
                        var style=document.createElement('style')
                        style.innerHTML="*{-webkit-tap-highlight-color:rgba(255,255,255,0);-webkit-focus-ring-color:rgba(0,0,0,0);-webkit-touch-callout:none;}";
                        document.head.appendChild(style)
                    """.trimIndent()

                    ) {}
                    super.onReceivedTitle(view, title)
                }
            })
            .useMiddlewareWebClient(object : MiddlewareWebClientBase() {
                // if origin is not localhost:port, open in external browser
                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest
                ): Boolean {
                    if (request.url.toString().startsWith(origin)) return false
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    return true
                }

                override fun onReceivedSslError(
                    view: WebView?, handler: SslErrorHandler?, error: SslError?
                ) = handler?.proceed() ?: Unit
            })
            .createAgentWeb()
            .ready()
            .go(origin)


        mAgentWeb.agentWebSettings.webSettings.mediaPlaybackRequiresUserGesture = false
        mAgentWeb.agentWebSettings.webSettings.setGeolocationEnabled(true)
        mAgentWeb.agentWebSettings.webSettings.safeBrowsingEnabled = false


        val webView = mAgentWeb.webCreator.webView
        webView.setOnLongClickListener {
            // disable long press drag effect (which shows a draggable link)
            // if this is not a trouble, remove this code
            webView.hitTestResult.type == HitTestResult.SRC_ANCHOR_TYPE
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        webServer.stop()
        //TODO:
        // this app starts a http server when it fires onCreate
        // and stop the server when it fires onDestroy
        // so this will causes network error when the webview fetching things from server
        // while the app fires onDestroy

    }


    private var exitTime: Long = 0
    override fun onBackPressed() {
        if (mAgentWeb.webCreator.webView.canGoBack()) {
            mAgentWeb.webCreator.webView.goBack()
        } else {
            if (System.currentTimeMillis() - exitTime > 2000) {
                Toast.makeText(
                    applicationContext, "再按一次退出程序",
                    Toast.LENGTH_SHORT
                ).show()
                exitTime = System.currentTimeMillis()
            } else {
                super.onBackPressed()
            }
        }
    }
}