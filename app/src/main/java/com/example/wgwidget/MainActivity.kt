package com.example.wgwidget

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        const val RELEASE_API =
            "https://api.github.com/repos/Sisyphe0202/wg-widget/releases/latest"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        setContentView(R.layout.activity_main)

        val status = findViewById<TextView>(R.id.tv_status)

        val pInfo = packageManager.getPackageInfo(packageName, 0)
        findViewById<TextView>(R.id.tv_version).text =
            "WG 流量监控 v${pInfo.versionName} (#${pInfo.longVersionCode})"

        findViewById<Button>(R.id.btn_test).setOnClickListener {
            status.text = "测试中…\nURL: ${WgWidgetProvider.SNAPSHOT_URL}"
            executor.execute {
                val result = runCatching {
                    val conn = (URL(WgWidgetProvider.SNAPSHOT_URL).openConnection()
                            as HttpURLConnection).apply {
                        connectTimeout = 10_000
                        readTimeout = 10_000
                    }
                    val code = conn.responseCode
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val q = JSONObject(body).getJSONObject("quota")
                    val remGb = q.getLong("remaining") / 1_000_000_000.0
                    "OK  HTTP $code\n响应 ${body.length} 字节\n剩余流量 %.2f GB".format(remGb)
                }.getOrElse { e ->
                    "FAIL ${e.javaClass.simpleName}\n${e.message ?: "(no message)"}"
                }
                handler.post { status.text = result }
            }
        }

        findViewById<Button>(R.id.btn_refresh).setOnClickListener {
            startForegroundService(Intent(this, RefreshService::class.java))
            status.text = "已启动前台服务刷新 widget。"
        }

        findViewById<Button>(R.id.btn_update).setOnClickListener {
            status.text = "检查更新中…"
            executor.execute { checkAndInstallUpdate(status) }
        }
    }

    private fun checkAndInstallUpdate(status: TextView) {
        try {
            val apiConn = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "wg-widget-updater")
            }
            val body = apiConn.inputStream.bufferedReader().use { it.readText() }
            val release = JSONObject(body)
            val assets = release.getJSONArray("assets")
            var apkUrl: String? = null
            var apkUpdated = "?"
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    apkUrl = a.getString("browser_download_url")
                    apkUpdated = a.optString("updated_at", "?")
                    break
                }
            }
            if (apkUrl == null) {
                handler.post { status.text = "FAIL  release 里没有 .apk 文件" }
                return
            }

            handler.post { status.text = "下载中…\nAPK 更新于 $apkUpdated" }

            val apkFile = File(cacheDir, "update.apk")
            if (apkFile.exists()) apkFile.delete()
            (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "wg-widget-updater")
            }.inputStream.use { input ->
                apkFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val sizeKb = apkFile.length() / 1024
            handler.post {
                status.text = "下载完成 ${sizeKb} KB\n准备安装…"
                installApk(apkFile, status)
            }
        } catch (e: Exception) {
            handler.post {
                status.text = "FAIL ${e.javaClass.simpleName}\n${e.message ?: ""}"
            }
        }
    }

    private fun installApk(file: File, status: TextView) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            status.text = "安装触发失败\n${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
