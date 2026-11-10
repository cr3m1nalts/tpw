package com.derinliksaati

import android.content.Context
import android.os.Bundle
import android.widget.Button
import androidx.activity.ComponentActivity

/** Full-screen editor for how the photo is framed on the screen. Changes save as you move. */
class FrameEditActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Layers.localized(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_frame_edit)
        val editor = findViewById<FrameEditView>(R.id.frameEditor)
        findViewById<Button>(R.id.frameReset).setOnClickListener {
            Layers.clearFrame(this)
            editor.invalidate()
        }
        findViewById<Button>(R.id.frameDone).setOnClickListener { finish() }
    }
}
