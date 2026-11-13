package ir.example.fieldtracker

import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

class TrackingService : Service() {
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var callback: LocationCallback
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val prefs by lazy { getSharedPreferences("fieldtracker", MODE_PRIVATE) }
    private var shiftId = 0
    private val pending = JSONArray()

    override fun onCreate() {
        super.onCreate()
        createNotification()
        fused = LocationServices.getFusedLocationProviderClient(this)
        scope.launch { startShiftOnServer() }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15000)
            .setMinUpdateIntervalMillis(10000).setMinUpdateDistanceMeters(5f).build()
        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { l ->
                    val speed = if (l.hasSpeed()) l.speed * 3.6 else 0.0
                    val p = JSONObject().put("lat", l.latitude).put("lon", l.longitude)
                        .put("accuracy_m", l.accuracy.toDouble())
                        .put("recorded_at", Instant.ofEpochMilli(l.time).toString())
                        .put("speed_kmh", speed)
                    synchronized(pending) { pending.put(p) }
                }
                scope.launch { flushPoints() }
            }
        }
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
        } else stopSelf()
    }

    private fun createNotification() {
        val channel = NotificationChannel("tracking", "ثبت موقعیت شیفت", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val n = Notification.Builder(this, "tracking")
            .setContentTitle("ثبت موقعیت فعال است")
            .setContentText("ردیابی فقط در شیفت کاری انجام می‌شود")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).build()
        startForeground(42, n)
    }

    private fun startShiftOnServer() {
        val worker = prefs.getInt("worker_id", 0)
        val body = JSONObject().put("worker_id", worker).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(prefs.getString("server", "") + "/shifts/start").post(body).build()
        try {
            http.newCall(req).execute().use { response ->
                if (response.isSuccessful) shiftId = JSONObject(response.body!!.string()).getInt("id")
                else stopSelf()
            }
        } catch (_: Exception) { stopSelf() }
    }

    private fun flushPoints() {
        if (shiftId <= 0) return
        val batch = JSONArray()
        synchronized(pending) { for (i in 0 until pending.length()) batch.put(pending.getJSONObject(i)) }
        if (batch.length() == 0) return
        val data = JSONObject().put("worker_id", prefs.getInt("worker_id", 0))
            .put("shift_id", shiftId).put("points", batch).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(prefs.getString("server", "") + "/locations").post(data).build()
        try {
            http.newCall(req).execute().use { response ->
                if (response.isSuccessful) synchronized(pending) {
                    // فقط پس از پاسخ موفق، صف حافظه پاک می‌شود؛ ذخیره پایدار Room در نسخه بعدی لازم است.
                    while (pending.length() > 0) pending.remove(0)
                }
            }
        } catch (_: Exception) { /* در این نمونه نقاط تا زمان اتصال مجدد در حافظه می‌مانند */ }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onDestroy() {
        if (::callback.isInitialized) fused.removeLocationUpdates(callback)
        scope.cancel()
        scope.launch {
            if (shiftId > 0) {
                val req = Request.Builder().url(prefs.getString("server", "") + "/shifts/$shiftId/end")
                    .post("".toRequestBody(null)).build()
                try { http.newCall(req).execute().close() } catch (_: Exception) {}
            }
        }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}