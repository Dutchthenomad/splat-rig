package com.splatrig.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SessionWriter(context: Context) : SensorEventListener {
    val root: File = File(context.getExternalFilesDir(null), "sessions").apply { mkdirs() }
    var sessionDir: File? = null
        private set
    var kept = 0
        private set
    var rejected = 0
        private set

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val imu = StringBuilder("t_ns,ax,ay,az,gx,gy,gz\n")
    private var acc = floatArrayOf(0f, 0f, 0f)
    private var gyro = floatArrayOf(0f, 0f, 0f)
    private val frames = JSONArray()
    private var startedAt = 0L

    fun start(): File {
        kept = 0
        rejected = 0
        frames.reset()
        imu.setLength(0)
        imu.append("t_ns,ax,ay,az,gx,gy,gz\n")
        startedAt = System.currentTimeMillis()
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        sessionDir = File(root, name).apply {
            mkdirs()
            File(this, "images").mkdirs()
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        return sessionDir!!
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        sessionDir?.let { writeMeta(it) }
    }

    fun nextImageFile(): File {
        val dir = File(sessionDir ?: error("no session"), "images")
        return File(dir, String.format(Locale.US, "%05d.jpg", kept + 1))
    }

    fun accept(file: File, sharpness: Double, width: Int, height: Int) {
        kept += 1
        frames.put(
            JSONObject()
                .put("file", "images/${file.name}")
                .put("sharpness", sharpness)
                .put("width", width)
                .put("height", height)
                .put("t_ms", System.currentTimeMillis() - startedAt),
        )
    }

    fun reject() {
        rejected += 1
    }

    fun zipTo(outFile: File): File {
        val src = sessionDir ?: error("no session")
        writeMeta(src)
        outFile.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(outFile)).use { zip ->
            src.walkTopDown().filter { it.isFile }.forEach { file ->
                val rel = file.relativeTo(src).path.replace("\\", "/")
                zip.putNextEntry(ZipEntry(rel))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return outFile
    }

    fun writeIntrinsics(fx: Double, fy: Double, cx: Double, cy: Double, width: Int, height: Int) {
        val dir = sessionDir ?: return
        File(dir, "intrinsics.json").writeText(
            JSONObject()
                .put("fx", fx)
                .put("fy", fy)
                .put("cx", cx)
                .put("cy", cy)
                .put("width", width)
                .put("height", height)
                .toString(2),
        )
    }

    private fun writeMeta(dir: File) {
        File(dir, "imu.csv").writeText(imu.toString())
        File(dir, "session.json").writeText(
            JSONObject()
                .put("id", dir.name)
                .put("started_ms", startedAt)
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("android", Build.VERSION.RELEASE)
                .put("kept", kept)
                .put("rejected", rejected)
                .put("subject", "rv_camper")
                .put("frames", frames)
                .toString(2),
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> acc = event.values.copyOf()
            Sensor.TYPE_GYROSCOPE -> gyro = event.values.copyOf()
            else -> return
        }
        imu.append(event.timestamp).append(',')
            .append(acc[0]).append(',').append(acc[1]).append(',').append(acc[2]).append(',')
            .append(gyro[0]).append(',').append(gyro[1]).append(',').append(gyro[2]).append('\n')
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

private fun JSONArray.reset() {
    while (length() > 0) remove(0)
}
