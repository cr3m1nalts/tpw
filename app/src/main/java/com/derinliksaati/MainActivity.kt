package com.derinliksaati

import android.app.Activity
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

    private val editSelection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            runStep(R.string.photo_filling, { Layers.finish(applicationContext) }) { updateStatus() }
        } else {
            Layers.pending = null
            updateStatus()
        }
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
        bindClockChoices()
        bindCheck(R.id.clockTilt, Layers.KEY_CLOCK_TILT, true)
        bindCheck(R.id.clockHome, Layers.KEY_CLOCK_HOME, true)
        findViewById<Button>(R.id.apply).setOnClickListener { applyWallpaper() }
        findViewById<Button>(R.id.clockEdit).setOnClickListener { startActivity(Intent(this, ClockEditActivity::class.java)) }
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

    private fun bindClockChoices() {
        val shapes = listOf(R.id.shapeRelief, R.id.shapeVolume)
        findViewById<RadioGroup>(R.id.shape).apply {
            check(shapes[prefs.getInt(Layers.KEY_SHAPE, Layers.SHAPE_RELIEF)])
            setOnCheckedChangeListener { _, id -> prefs.edit().putInt(Layers.KEY_SHAPE, shapes.indexOf(id)).apply() }
        }
        val styles = listOf(R.id.styleClassic, R.id.styleFrosted, R.id.styleLiquid)
        findViewById<RadioGroup>(R.id.clockStyle).apply {
            check(styles[prefs.getInt(Layers.KEY_CLOCK_STYLE, Layers.STYLE_CLASSIC)])
            setOnCheckedChangeListener { _, id -> prefs.edit().putInt(Layers.KEY_CLOCK_STYLE, styles.indexOf(id)).apply() }
        }
        val depths = listOf(R.id.depthAuto, R.id.depthBehind, R.id.depthFront)
        findViewById<RadioGroup>(R.id.clockDepth).apply {
            check(depths[prefs.getInt(Layers.KEY_CLOCK_DEPTH, Layers.DEPTH_AUTO)])
            setOnCheckedChangeListener { _, id -> prefs.edit().putInt(Layers.KEY_CLOCK_DEPTH, depths.indexOf(id)).apply() }
        }
        findViewById<RadioGroup>(R.id.clockLayout).apply {
            check(if (prefs.getBoolean(Layers.KEY_CLOCK_VERTICAL, false)) R.id.layoutVertical else R.id.layoutHorizontal)
            setOnCheckedChangeListener { _, id ->
                prefs.edit().putBoolean(Layers.KEY_CLOCK_VERTICAL, id == R.id.layoutVertical).apply()
                // The two layouts have very different shapes, so the old box would stretch the new one badly.
                Layers.clearClockBox(this@MainActivity)
            }
        }
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
        runStep(R.string.photo_working, { Layers.prepare(applicationContext, uri, w, h) }) {
            editSelection.launch(Intent(this, MaskEditActivity::class.java))
        }
    }

    /** Runs slow photo work off the main thread with the busy state on; [next] runs on the main thread if it worked. */
    private fun runStep(message: Int, work: () -> Unit, next: () -> Unit) {
        setBusy(true, message)
        worker.execute {
            val error = try {
                work()
                null
            } catch (e: Exception) {
                Log.e(TAG, "Could not process the photo", e)
                e
            }
            runOnUiThread {
                setBusy(false, message)
                if (error == null) next() else status.setText(R.string.photo_failed)
            }
        }
    }

    private fun setBusy(busy: Boolean, message: Int) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        pickButton.isEnabled = !busy
        if (busy) status.setText(message)
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
