package com.example.wgwidget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        setContentView(R.layout.activity_main)

        val status = findViewById<TextView>(R.id.tv_status)

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
            sendBroadcast(Intent(this, WgWidgetProvider::class.java).apply {
                action = WgWidgetProvider.ACTION_REFRESH
                `package` = packageName
            })
            status.text = "已发送刷新广播给 widget。\n如果 widget 还显示无法连接，问题在后台限制。"
        }
    }
}
