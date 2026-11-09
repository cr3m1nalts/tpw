package com.derinliksaati

import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.Executors

/** Setup screen: pick a photo, tune the depth, apply the wallpaper, and the Samsung-specific hints. */
class MainActivity : ComponentActivity() {

    private val prefs by lazy { Layers.prefs(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var pickButton: Button
    private lateinit var batteryButton: Button

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) buildLayers(uri)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Layers.localized(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindLanguage()
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        pickButton = findViewById(R.id.pickPhoto)
        batteryButton = findViewById(R.id.battery)

        pickButton.setOnClickListener {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        bindSlider(R.id.depth, R.id.depthValue, Layers.KEY_DEPTH, Layers.DEFAULT_DEPTH) {
            "${(Layers.MAX_VIEW_DEG * it / 100).toInt()}°"
        }
        bindSlider(R.id.volume, R.id.volumeValue, Layers.KEY_VOLUME, Layers.DEFAULT_VOLUME) { "%$it" }
        bindSlider(R.id.subjectTurn, R.id.subjectTurnValue, Layers.KEY_SUBJECT_TURN, Layers.DEFAULT_SUBJECT_TURN) { "%$it" }
        bindSlider(R.id.clockAlpha, R.id.clockAlphaValue, Layers.KEY_CLOCK_ALPHA, Layers.DEFAULT_CLOCK_ALPHA) { "%$it" }
        bindCheck(R.id.clockFront, Layers.KEY_CLOCK_FRONT, false)
        bindCheck(R.id.clockHome, Layers.KEY_CLOCK_HOME, true)
        findViewById<Button>(R.id.apply).setOnClickListener { applyWallpaper() }
        findViewById<Button>(R.id.clockHelp).setOnClickListener { showClockHint() }
        batteryButton.setOnClickListener { requestBatteryExemption() }
        updateStatus()

        if (!prefs.getBoolean(Layers.KEY_CLOCK_HINT_SHOWN, false)) showClockHint()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the battery settings: hide the button once the exemption is granted.
        val exempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        batteryButton.visibility = if (exempt) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun bindSlider(seekId: Int, labelId: Int, key: String, default: Int, label: (Int) -> String) {
        val seek = findViewById<SeekBar>(seekId)
        val text = findViewById<TextView>(labelId)
        seek.progress = prefs.getInt(key, default)
        text.text = label(seek.progress)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, value: Int, fromUser: Boolean) {
                text.text = label(value)
                if (fromUser) prefs.edit().putInt(key, value).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar) = Unit
            override fun onStopTrackingTouch(s: SeekBar) = Unit
        })
    }

    private fun bindLanguage() {
        val group = findViewById<RadioGroup>(R.id.language)
        group.check(if (Layers.locale(this).language == "tr") R.id.langTr else R.id.langEn)
        group.setOnCheckedChangeListener { _, id ->
            prefs.edit().putString(Layers.KEY_LANG, if (id == R.id.langTr) "tr" else "en").apply()
            recreate() // reload every text in the new language
        }
    }

    private fun bindCheck(id: Int, key: String, default: Boolean) {
        findViewById<CheckBox>(id).apply {
            isChecked = prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
        }
    }

    private fun buildLayers(uri: Uri) {
        val bounds = windowManager.maximumWindowMetrics.bounds
        val w = minOf(bounds.width(), bounds.height())
        val h = maxOf(bounds.width(), bounds.height())
        setBusy(true)
        worker.execute {
            val error = try {
                Layers.build(applicationContext, uri, w, h)
                null
            } catch (e: Exception) {
                Log.e(TAG, "Could not build layers", e)
                e
            }
            runOnUiThread {
                setBusy(false)
                if (error == null) updateStatus() else status.setText(R.string.photo_failed)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        pickButton.isEnabled = !busy
        if (busy) status.setText(R.string.photo_working)
    }

    private fun updateStatus() {
        status.setText(if (Layers.subjectFile(this).exists()) R.string.photo_ready else R.string.photo_none)
    }

    private fun applyWallpaper() {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, DepthWallpaperService::class.java)
        )
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }

    private fun showClockHint() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clock_hint_title)
            .setMessage(R.string.clock_hint)
            .setPositiveButton(R.string.ok, null)
            // Mark as seen only once the user closes it; if the screen is recreated first, it shows again.
            .setOnDismissListener { prefs.edit().putBoolean(Layers.KEY_CLOCK_HINT_SHOWN, true).apply() }
            .show()
    }

    // Samsung's battery saver can stop background work; this opens the system's own exemption prompt.
    private fun requestBatteryExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private companion object {
        const val TAG = "DerinlikSaati"
    }
}
