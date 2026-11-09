package com.derinliksaati

import android.content.Context
import android.os.Bundle
import android.widget.Button
import androidx.activity.ComponentActivity

/** Full-screen editor for the clock's position and size. Changes save as you drag. */
class ClockEditActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Layers.localized(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clock_edit)
        val editor = findViewById<ClockEditView>(R.id.clockEditor)
        findViewById<Button>(R.id.clockReset).setOnClickListener {
            Layers.clearClockBox(this)
            editor.invalidate()
        }
        findViewById<Button>(R.id.clockDone).setOnClickListener { finish() }
    }
}
