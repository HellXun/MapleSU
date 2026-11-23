package com.maplesu.core.ui.webui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.core.net.toUri
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode

/** Keep the legacy synchronous KSU bridge on module documents served by the asset loader. */
// AndroidX WebKit 1.17 flags the super constructor even with the lifecycle override below.
internal open class ModuleWebViewClient @SuppressLint("MissingOnRenderProcessGone") constructor(
    private val assets: WebViewAssetLoader,
    private val openExternal: (Uri) -> Unit,
    private val onRendererGone: (WebView) -> Unit,
    normalCompatibility: Boolean = false,
) : WebViewClient() {
    private val documentCsp = moduleDocumentCsp(normalCompatibility)
    companion object {
        private const val MODULE_HOST = "mui.kernelsu.org"

        private fun isModuleOrigin(uri: Uri) =
            uri.scheme.equals("https", true) && uri.host.equals(MODULE_HOST, true) &&
                uri.userInfo == null && (uri.port == -1 || uri.port == 443)

        private fun blocked() = WebResourceResponse(
            "text/plain", "utf-8", 403, "Forbidden", emptyMap(), null)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        if (uri.scheme == "https" && uri.host == BlobDownloadHandler.HOST) return true
        if (isModuleOrigin(uri)) return false
        if (!request.isForMainFrame) return true
        if (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) {
            openExternal(uri)
        }
        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (isModuleOrigin(uri)) {
            if (request.method != "GET") return blocked()
            val response = assets.shouldInterceptRequest(uri)
                ?: WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), null)
            response.responseHeaders = (response.responseHeaders ?: emptyMap()) + mapOf(
                "Content-Security-Policy" to documentCsp,
                "Cache-Control" to "no-store",
                "X-Content-Type-Options" to "nosniff",
            )
            return response
        }
        if (request.isForMainFrame || uri.host.equals(MODULE_HOST, true)) return blocked()
        return null
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        // loadUrl/data/blob navigations do not all pass through the request callbacks.
        if (url == null || !isModuleOrigin(url.toUri())) {
            view.settings.javaScriptEnabled = false
            view.removeJavascriptInterface("ksu")
            view.removeJavascriptInterface("ksu_download")
            view.stopLoading()
        }
        super.onPageStarted(view, url, favicon)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        view.removeJavascriptInterface("ksu")
        view.removeJavascriptInterface("ksu_download")
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
        onRendererGone(view)
        return true
    }
}
