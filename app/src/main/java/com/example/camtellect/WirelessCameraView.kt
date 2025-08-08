package com.example.camtellect

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WirelessCameraView(
    html: String,
    baseUrl: String = "http://192.168.1.55:8080",
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = WebViewClient()
                loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
            }
        }
    )
}
