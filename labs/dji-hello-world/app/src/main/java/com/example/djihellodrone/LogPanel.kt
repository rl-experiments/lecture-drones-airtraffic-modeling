package com.example.djihellodrone

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The on-screen log as one component: a scrolling, timestamped list of lines
 * with its own "Share log" button. Call [append] from any thread.
 */
class LogPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    companion object {
        private const val MAX_LINES = 400
    }

    private val lines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val logView: TextView
    private val logScroll: ScrollView

    init {
        orientation = VERTICAL
        inflate(context, R.layout.view_log_panel, this)
        logView = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        findViewById<Button>(R.id.shareLogButton).setOnClickListener { share() }
    }

    fun append(msg: String) {
        val text = synchronized(lines) {
            lines.addLast("${clock.format(Date())}  $msg")
            while (lines.size > MAX_LINES) lines.removeFirst()
            lines.joinToString("\n")
        }
        if (Looper.myLooper() == Looper.getMainLooper()) render(text) else post { render(text) }
    }

    private fun render(text: String) {
        // Follow new lines only while the reader is at the bottom; once they
        // scroll up to read, stay put until they scroll back down.
        val atBottom = !logScroll.canScrollVertically(1)
        logView.text = text
        if (atBottom) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun share() {
        val text = synchronized(lines) { lines.joinToString("\n") }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Share log"))
    }
}
