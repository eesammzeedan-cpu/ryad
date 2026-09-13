package com.ryadah.medicaldirectory

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.provider.MediaStore
import android.widget.Toast
import java.io.OutputStream

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingDownloadName = "attachment"
    private var pendingDownloadMime = "application/octet-stream"
    private var pendingDownloadBytes: ByteArray? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            builtInZoomControls = false
            displayZoomControls = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webView.evaluateJavascript("window.RYADAH_ANDROID_APP=true;", null)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePath: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePath
                return try {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                    }
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST)
                    true
                } catch (_: Exception) {
                    this@MainActivity.filePathCallback = null
                    Toast.makeText(this@MainActivity, "تعذر فتح اختيار المرفق", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }

        // Native Android support only: sharing and file handling.
        // Synchronization/server behavior is left entirely to the HTML page.
        webView.addJavascriptInterface(AndroidBridge(), "Android")
        webView.loadUrl("file:///android_asset/الدليل_الطبي.html")
        webView.evaluateJavascript("window.RYADAH_ANDROID_APP=true;", null)
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun shareWhatsApp(text: String) {
            runOnUiThread {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                try {
                    // فتح واتساب مباشرة إن كان مثبتًا، ثم الرجوع لقائمة المشاركة عند عدم توفره.
                    val whatsapp = Intent(send).apply { setPackage("com.whatsapp") }
                    try {
                        startActivity(whatsapp)
                        return@runOnUiThread
                    } catch (_: ActivityNotFoundException) { }
                    val business = Intent(send).apply { setPackage("com.whatsapp.w4b") }
                    try {
                        startActivity(business)
                        return@runOnUiThread
                    } catch (_: ActivityNotFoundException) { }
                    startActivity(Intent.createChooser(send, "مشاركة الخدمة"))
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "لا يوجد تطبيق مناسب للمشاركة", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun sendSms(text: String) {
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("smsto:")
                        putExtra("sms_body", text)
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("sms:")
                            putExtra("sms_body", text)
                        }
                        startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(this@MainActivity, "لا يوجد تطبيق رسائل متاح", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        @JavascriptInterface
        fun downloadBase64File(fileName: String, dataUrl: String, mimeType: String?) {
            // Decode off the UI thread so large attachments do not freeze the app.
            Thread {
                val clean = dataUrl.substringAfter(",", dataUrl)
                val bytes = try {
                    Base64.decode(clean, Base64.DEFAULT)
                } catch (_: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "تعذر قراءة المرفق", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                pendingDownloadName = fileName.ifBlank { "attachment" }
                pendingDownloadMime = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                pendingDownloadBytes = bytes

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, pendingDownloadName)
                            put(MediaStore.Downloads.MIME_TYPE, pendingDownloadMime)
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                            put(MediaStore.Downloads.IS_PENDING, 1)
                        }
                        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        if (uri == null) throw IllegalStateException("تعذر إنشاء ملف التنزيل")
                        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            ?: throw IllegalStateException("تعذر فتح ملف التنزيل")
                        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                        contentResolver.update(uri, done, null, null)
                        pendingDownloadBytes = null
                        runOnUiThread { Toast.makeText(this@MainActivity, "تم تنزيل المرفق إلى مجلد التنزيلات", Toast.LENGTH_SHORT).show() }
                    } catch (_: Exception) {
                        runOnUiThread { Toast.makeText(this@MainActivity, "تعذر تنزيل المرفق", Toast.LENGTH_SHORT).show() }
                    }
                } else {
                    runOnUiThread {
                        try {
                            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = pendingDownloadMime
                                putExtra(Intent.EXTRA_TITLE, pendingDownloadName)
                            }
                            startActivityForResult(intent, CREATE_DOCUMENT_REQUEST)
                        } catch (_: Exception) {
                            Toast.makeText(this@MainActivity, "تعذر فتح نافذة حفظ المرفق", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }.start()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == FILE_CHOOSER_REQUEST) {
            val callback = filePathCallback
            filePathCallback = null
            val result = if (resultCode == RESULT_OK && data?.data != null) {
                arrayOf(data.data!!)
            } else {
                null
            }
            callback?.onReceiveValue(result)
            return
        }

        if (requestCode == CREATE_DOCUMENT_REQUEST) {
            val bytes = pendingDownloadBytes
            pendingDownloadBytes = null
            if (resultCode == RESULT_OK && data?.data != null && bytes != null) {
                Thread {
                    try {
                        val out: OutputStream? = contentResolver.openOutputStream(data.data!!)
                        out.use { stream -> stream?.write(bytes) }
                        runOnUiThread {
                            Toast.makeText(this, "تم حفظ المرفق بنجاح", Toast.LENGTH_SHORT).show()
                        }
                    } catch (_: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, "تعذر حفظ المرفق", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        // Keep the existing page lifecycle behavior unchanged.
        webView.evaluateJavascript(
            "try{if(typeof pollLanServer==='function'){pollLanServer();}}catch(e){}",
            null
        )
    }

    companion object {
        private const val FILE_CHOOSER_REQUEST = 4101
        private const val CREATE_DOCUMENT_REQUEST = 4102
    }
}
