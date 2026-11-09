package com.derinliksaati

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.ComponentActivity

/** Lets the user mark what stands out: the automatic selection comes pre-painted, brush and eraser fix it. */
class MaskEditActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Layers.localized(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pending = Layers.pending
        if (pending == null) {
            finish() // the app was killed while in the background and the photo is gone
            return
        }
        setContentView(R.layout.activity_mask_edit)
        val editor = findViewById<MaskEditView>(R.id.editor)
        editor.setImage(pending.photo, pending.mask)

        findViewById<RadioGroup>(R.id.tool).setOnCheckedChangeListener { _, id -> editor.erase = id == R.id.toolErase }
        findViewById<SeekBar>(R.id.brushSize).apply {
            progress = editor.brushDp.toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, value: Int, fromUser: Boolean) {
                    editor.brushDp = value.toFloat()
                }
                override fun onStartTrackingTouch(s: SeekBar) = Unit
                override fun onStopTrackingTouch(s: SeekBar) = Unit
            })
        }
        findViewById<Button>(R.id.undo).setOnClickListener { editor.undo() }
        findViewById<Button>(R.id.clear).setOnClickListener { editor.clear() }
        findViewById<Button>(R.id.done).setOnClickListener {
            if (editor.isEmpty()) {
                Toast.makeText(this, R.string.edit_empty, Toast.LENGTH_SHORT).show()
            } else {
                setResult(Activity.RESULT_OK)
                finish()
            }
        }
    }
}
