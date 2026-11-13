package ir.example.fieldtracker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("fieldtracker", MODE_PRIVATE) }
    private lateinit var server: EditText
    private lateinit var workerId: EditText
    private lateinit var status: TextView
    private val requestCode = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 24)
            setBackgroundColor(0xFFF4F6F9.toInt())
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text; textSize = 16f; setTextColor(0xFF182536.toInt())
            setPadding(0, 14, 0, 6)
        }
        server = EditText(this).apply {
            hint = "نشانی سرور؛ مثال http://192.168.1.10:8000"
            setText(prefs.getString("server", "http://10.0.2.2:8000"))
            textSize = 14f
        }
        workerId = EditText(this).apply {
            hint = "شناسه نیرو (از پنل مدیر)"
            inputType = 2
            setText(prefs.getInt("worker_id", 0).takeIf { it > 0 }?.toString() ?: "")
        }
        status = label("وضعیت: آماده")
        val save = Button(this).apply { text = "ذخیره تنظیمات" }
        val start = Button(this).apply { text = "شروع شیفت و ثبت موقعیت" }
        val stop = Button(this).apply { text = "پایان شیفت" }
        layout.addView(label("سامانه رصد نیروهای پروژه"))
        layout.addView(label("آدرس سرور"))
        layout.addView(server)
        layout.addView(label("شناسه پرسنلی"))
        layout.addView(workerId)
        layout.addView(save)
        layout.addView(start)
        layout.addView(stop)
        layout.addView(status)
        setContentView(ScrollView(this).apply { addView(layout) })

        save.setOnClickListener {
            prefs.edit().putString("server", server.text.toString().trim())
                .putInt("worker_id", workerId.text.toString().toIntOrNull() ?: 0).apply()
            status.text = "تنظیمات ذخیره شد"
        }
        start.setOnClickListener {
            save.performClick()
            if ((prefs.getInt("worker_id", 0)) <= 0 || server.text.isBlank()) {
                status.text = "آدرس سرور و شناسه نیرو را وارد کنید"; return@setOnClickListener
            }
            requestPermissionsIfNeeded()
        }
        stop.setOnClickListener {
            stopService(Intent(this, TrackingService::class.java))
            status.text = "درخواست پایان شیفت ارسال شد"
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), requestCode)
        else beginShift()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == this.requestCode && results.isNotEmpty() &&
            results.any { it == PackageManager.PERMISSION_GRANTED } &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            beginShift()
        } else status.text = "برای ثبت مسیر، مجوز موقعیت دقیق لازم است"
    }

    private fun beginShift() {
        val i = Intent(this, TrackingService::class.java)
        if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(this, i) else startService(i)
        status.text = "شیفت فعال شد؛ ثبت موقعیت در حال انجام است"
    }
}