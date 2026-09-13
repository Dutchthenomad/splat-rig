package com.splatrig.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.splatrig.capture.databinding.ActivityMainBinding
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var binding: ActivityMainBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val session = lazy { SessionWriter(this) }
    private val coverage = Coverage()
    private lateinit var sensors: SensorManager
    private var imageCapture: ImageCapture? = null
    private var recording = false
    private var bursting = false
    private var lastZip: File? = null
    private var yaw = 0f
    private var pitch = 0f
    private var gyro = floatArrayOf(0f, 0f, 0f)
    private var veto: String? = null

    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) startCamera() else toast("Camera permission is required") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.coverageView.coverage = coverage
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        binding.serverUrl.setText(getSharedPreferences("splatrig", MODE_PRIVATE).getString("server", "http://192.168.1.10:8080"))
        binding.btnSession.setOnClickListener { toggleSession() }
        binding.btnShot.setOnClickListener { snap() }
        binding.btnBurst.setOnClickListener { toggleBurst() }
        binding.btnExport.setOnClickListener { exportZip() }
        binding.btnUpload.setOnClickListener { upload() }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        sensors.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val yp = Coverage.yawPitchFromRotationVector(event.values)
                yaw = yp.first; pitch = yp.second
            }
            Sensor.TYPE_GYROSCOPE -> gyro = event.values.copyOf()
        }
        veto = if (Coverage.tooFast(gyro)) "TOO FAST" else null
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = binding.previewView.surfaceProvider }
            imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setTargetResolution(Size(1920, 1080)).build()
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleSession() {
        if (!recording) {
            coverage.reset(); coverage.lockFront(yaw); session.value.start()
            recording = true; lastZip = null
            binding.btnSession.text = "Stop session"
            binding.btnShot.isEnabled = true; binding.btnBurst.isEnabled = true
            toast("Front locked. Walk DS / rear / PS, then tilt up at cabover and rear cap.")
        } else {
            bursting = false; session.value.stop(); recording = false
            binding.btnSession.text = "Start session"
            binding.btnShot.isEnabled = false; binding.btnBurst.isEnabled = false
            binding.btnBurst.text = "Auto 0.7s"
            refreshExport()
        }
        binding.coverageView.invalidate(); renderStatus()
    }

    private fun toggleBurst() {
        bursting = !bursting
        binding.btnBurst.text = if (bursting) "Stop auto" else "Auto 0.7s"
        if (bursting) scheduleBurst()
    }

    private fun scheduleBurst() {
        if (!bursting || !recording) return
        snap()
        mainHandler.postDelayed({ scheduleBurst() }, 700)
    }

    private fun snap() {
        val capture = imageCapture ?: return
        if (!recording) return
        if (veto != null) { session.value.reject(); renderStatus(); return }
        val file = session.value.nextImageFile()
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val stats = Sharpness.stats(file.absolutePath)
                val bad = stats.luma < 28 || stats.luma > 230 || stats.sharpness < 18.0
                if (bad) { file.delete(); session.value.reject() }
                else {
                    val b = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath, b)
                    session.value.accept(file, stats.sharpness, b.outWidth, b.outHeight)
                    coverage.mark(yaw, pitch)
                }
                runOnUiThread { binding.coverageView.invalidate(); refreshExport(); renderStatus() }
            }
            override fun onError(exception: ImageCaptureException) { runOnUiThread { toast(exception.message ?: "capture failed") } }
        })
    }

    private fun refreshExport() {
        binding.btnExport.isEnabled = !recording && coverage.exportReady(session.value.kept)
    }

    private fun exportZip() {
        if (!coverage.exportReady(session.value.kept)) { toast("Missing: " + coverage.missing().joinToString(", ")); return }
        if (recording) session.value.stop()
        session.value.writeCoverage(coverage.snapshot(), coverage.missing())
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir, "SplatRig")
        val zip = File(dir, (session.value.sessionDir?.name ?: "session") + ".zip")
        try { lastZip = session.value.zipTo(zip); binding.btnUpload.isEnabled = true; toast("Saved " + zip.absolutePath) }
        catch (e: Exception) { toast(e.message ?: "zip failed") }
    }

    private fun upload() {
        val zip = lastZip
        if (zip == null || !zip.exists()) { toast("Export a zip first"); return }
        val url = binding.serverUrl.text.toString().trim()
        getSharedPreferences("splatrig", MODE_PRIVATE).edit().putString("server", url).apply()
        binding.btnUpload.isEnabled = false
        cameraExecutor.execute {
            try {
                val id = Uploader.uploadAndRun(url, zip)
                runOnUiThread { binding.status.text = "PC job $id started"; binding.btnUpload.isEnabled = true }
            } catch (e: Exception) {
                runOnUiThread { binding.status.text = "Upload failed: " + e.message; binding.btnUpload.isEnabled = true }
            }
        }
    }

    private fun renderStatus() {
        val s = session.value
        val miss = coverage.missing()
        binding.status.text = when {
            veto != null && recording -> veto!!
            !recording && s.kept == 0 -> "Idle. Face the Ford grille, then Start session."
            miss.isNotEmpty() -> "Kept " + s.kept + "  rej " + s.rejected + "  need " + miss.joinToString(",")
            else -> "Kept " + s.kept + "  rej " + s.rejected + "  coverage OK"
        }
    }

    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    override fun onDestroy() {
        super.onDestroy()
        bursting = false
        cameraExecutor.shutdown()
        if (recording) session.value.stop()
    }
}
