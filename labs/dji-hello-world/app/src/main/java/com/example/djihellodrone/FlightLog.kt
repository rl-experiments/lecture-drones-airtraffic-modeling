package com.example.djihellodrone

import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Flight data too fast for the on-screen log: control steps and obstacle readings,
 * one text file per mission, each line starting with the milliseconds since the start.
 * Call [line] from any thread. A file that cannot be written never stops the flight:
 * [path] is then null and the lines are dropped.
 */
class FlightLog(dir: File?, name: String) {

    private val startNs = System.nanoTime()
    private val file = dir?.let {
        File(it, "$name-${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.log")
    }
    private var out: PrintWriter? = try {
        file?.let { PrintWriter(it.bufferedWriter(), true) }   // every line reaches the file, even if the app dies
    } catch (e: Exception) {
        null
    }

    val path: String? = if (out != null) file?.absolutePath else null

    @Synchronized fun line(msg: String) {
        out?.println("${(System.nanoTime() - startNs) / 1_000_000} $msg")
    }

    @Synchronized fun close() {
        out?.close()
        out = null
    }
}
