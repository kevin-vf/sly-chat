package io.slychat.messenger.android

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v7.app.AppCompatActivity
import android.view.KeyEvent
import android.view.animation.Animation
import android.view.animation.AlphaAnimation
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.google.android.gms.common.GoogleApiAvailability
import com.vfpowertech.jsbridge.androidwebengine.AndroidWebEngineInterface
import com.vfpowertech.jsbridge.core.dispatcher.Dispatcher
import io.slychat.messenger.core.BuildConfig
import io.slychat.messenger.services.ui.js.NavigationService
import io.slychat.messenger.services.ui.js.javatojs.NavigationServiceToJSProxy
import io.slychat.messenger.services.ui.registerCoreServicesOnDispatcher
import nl.komponents.kovenant.Deferred
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import org.slf4j.LoggerFactory
import rx.Subscription
import java.util.*

class MainActivity : AppCompatActivity() {
    companion object {
        val ACTION_VIEW_MESSAGES = "com.vfpowertech.keytap.android.action.VIEW_MESSAGES"

        val EXTRA_PENDING_MESSAGES_TYPE = "pendingMessagesType"
        val EXTRA_PENDING_MESSAGES_TYPE_SINGLE = "single"
        val EXTRA_PENDING_MESSAGES_TYPE_MULTI = "multi"

        val EXTRA_USERID = "username"
    }

    private val log = LoggerFactory.getLogger(javaClass)

    //this is set whether or not initialization was successful
    //since we always quit the application on successful init, there's no need to retry it
    private var isInitialized = false
    private var isActive = false
    private var hadSavedBundle: Boolean = false

    private var loadCompleteSubscription: Subscription? = null

    var navigationService: NavigationService? = null
    private lateinit var webView: WebView

    private var nextPermRequestCode = 0
    private val permRequestCodeToDeferred = HashMap<Int, Deferred<Boolean, Exception>>()

    /** Returns the initial page to launch after login, if any. Used when invoked via a notification intent. */
    private fun getInitialPage(intent: Intent): String? {
        if (intent.action != ACTION_VIEW_MESSAGES)
            return null

        val messagesType = intent.getStringExtra(EXTRA_PENDING_MESSAGES_TYPE)
        val page = when (messagesType) {
            null -> return null
            EXTRA_PENDING_MESSAGES_TYPE_SINGLE -> {
                val username = intent.getStringExtra(EXTRA_USERID) ?: throw RuntimeException("Missing EXTRA_USERNAME")
                "user/$username"
            }
            EXTRA_PENDING_MESSAGES_TYPE_MULTI -> "contacts"
            else -> throw RuntimeException("Unexpected value for EXTRA_PENDING_MESSAGES_TYPE: $messagesType")
        }

        return page
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val page = getInitialPage(intent) ?: return

        val navigationService = navigationService ?: return

        navigationService.goTo(page) fail { e ->
            log.error("navigationService.goTo failed: {}", e.message, e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //hide titlebar
        supportActionBar?.hide()

        //display loading screen and wait for app to finish loading
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView) as WebView

        hadSavedBundle = savedInstanceState != null

        setAppActivity()
    }

    private fun subToLoadComplete() {
        if (loadCompleteSubscription != null)
            return

        val app = AndroidApp.get(this)
        loadCompleteSubscription = app.loadComplete.subscribe { loadError ->
            isInitialized = true

            if (loadError == null)
                init(hadSavedBundle)
            else
                handleLoadError(loadError)
        }
    }

    private fun handleLoadError(loadError: LoadError) {
        val dialog = when (loadError.type) {
            LoadErrorType.NO_PLAY_SERVICES -> handlePlayServicesError(loadError.errorCode)
            LoadErrorType.SSL_PROVIDER_INSTALLATION_FAILURE -> handleSslProviderInstallationFailure(loadError.errorCode)
            LoadErrorType.UNKNOWN -> handleUnknownLoadError(loadError.cause)
        }

        dialog.setOnDismissListener {
            finish()
        }

        dialog.show()
    }

    private fun getInitFailureDialog(message: String): AlertDialog {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Initialization Failure")
        builder.setPositiveButton("Close Application", { dialog, id ->
            finish()
        })

        builder.setMessage(message)

        return builder.create()
    }

    private fun handleUnknownLoadError(cause: Throwable?): AlertDialog {
        val message = if (cause != null)
            "An unexpected error occured: ${cause.message}"
        else
            //XXX shouldn't happen
            "An unknown error occured but not information is available"

        return getInitFailureDialog(message)
    }

    private fun handleSslProviderInstallationFailure(errorCode: Int): Dialog {
        return GoogleApiAvailability.getInstance().getErrorDialog(this, errorCode, 0)
    }

    private fun handlePlayServicesError(errorCode: Int): Dialog {
        val apiAvailability = GoogleApiAvailability.getInstance()

        return if (apiAvailability.isUserResolvableError(errorCode))
            apiAvailability.getErrorDialog(this, errorCode, 0)
        else
            getInitFailureDialog("Unsupported device")
    }

    private fun init(hadSavedBundle: Boolean) {
        val app = AndroidApp.get(this)

        app.appComponent.stateService.initialPage = getInitialPage(intent)

        loadCompleteSubscription?.unsubscribe()
        loadCompleteSubscription = null

        if (BuildConfig.DEBUG)
            WebView.setWebContentsDebuggingEnabled(true)

        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccessFromFileURLs = true
        webView.settings.blockNetworkLoads = true
        
        //Allow javascript to push history state to the webview
        webView.settings.allowUniversalAccessFromFileURLs = true

        initJSLogging(webView)

        val webEngineInterface = AndroidWebEngineInterface(webView)

        val dispatcher = Dispatcher(webEngineInterface)

        registerCoreServicesOnDispatcher(dispatcher, AndroidApp.get(this).appComponent)

        //TODO should init this only once the webview has loaded the page
        webView.setWebViewClient(object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                navigationService = NavigationServiceToJSProxy(dispatcher)
            }
        })

        if (!hadSavedBundle)
            webView.loadUrl("file:///android_asset/ui/index.html")
    }

    fun hideSplashImage() {
        val splashView = findViewById(R.id.splashImageView)

        val animation = AlphaAnimation(1f, 0f)
        animation.duration = 500
        animation.startOffset = 500
        animation.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationEnd(animation: Animation) {
                val layout = findViewById(R.id.frameLayout) as FrameLayout
                layout.removeViewInLayout(splashView)
            }

            override fun onAnimationRepeat(animation: Animation) {}

            override fun onAnimationStart(animation: Animation) {}
        })

        splashView.startAnimation(animation)
    }

    override fun onSaveInstanceState(outState: Bundle?) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle?) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }

    private fun setAppActivity() {
        isActive = true
        AndroidApp.get(this).currentActivity = this
    }

    private fun clearAppActivity() {
        isActive = false
        AndroidApp.get(this).currentActivity = null
    }

    override fun onPause() {
        clearAppActivity()
        super.onPause()

        val sub = loadCompleteSubscription
        if (sub != null) {
            sub.unsubscribe()
            loadCompleteSubscription = null
        }
    }

    override fun onDestroy() {
        clearAppActivity()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        setAppActivity()

        if (!isInitialized)
            subToLoadComplete()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (navigationService != null)
                navigationService!!.goBack()
            //if we haven't loaded the web ui, we're either still in the loading screen or on some error dialog that'll
            //terminate the app anyways
            else
                finish()

            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    fun requestPermission(permission: String): Promise<Boolean, Exception> {
        val requestCode = nextPermRequestCode
        nextPermRequestCode += 1

        val deferred = deferred<Boolean, Exception>()
        permRequestCodeToDeferred[requestCode] = deferred

        ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)

        return deferred.promise
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val deferred = permRequestCodeToDeferred[requestCode]

        if (deferred == null) {
            log.error("Got response for unknown request code ({}); permissions={}", requestCode, Arrays.toString(permissions))
            return
        }

        permRequestCodeToDeferred.remove(requestCode)

        val granted = grantResults[0] == PackageManager.PERMISSION_GRANTED

        deferred.resolve(granted)
    }

    /** Capture console.log output into android's log */
    private fun initJSLogging(webView: WebView) {
        val jsLog = LoggerFactory.getLogger("Javascript")
        webView.setWebChromeClient(object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val msg = "[${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}] ${consoleMessage.message()}"
                when (consoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.DEBUG -> jsLog.debug(msg)
                    ConsoleMessage.MessageLevel.ERROR -> jsLog.error(msg)
                    ConsoleMessage.MessageLevel.LOG -> jsLog.info(msg)
                    ConsoleMessage.MessageLevel.TIP -> jsLog.info(msg)
                    ConsoleMessage.MessageLevel.WARNING -> jsLog.warn(msg)
                }
                return true;
            }
        })
    }
}