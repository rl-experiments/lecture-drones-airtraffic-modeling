package com.example.djihellodrone

/** A point of the figure: x to the right as the camera sees it, y up from the figure's bottom edge. */
data class PathPoint(val x: Double, val y: Double)

/**
 * The Mission 2 (light painting) figure, three centred lines:
 *
 *     Albstadt
 *     Informatik
 *         ❤
 *
 * The two words are traced from a rendering of the script font Agatha Bergman
 * as pixel positions along the middle of the pen line. Where the pen
 * of the font lifts, the trace has a [LIFT]: it splits a word into pieces, each
 * drawn with the lights on, and the drone flies from one piece to the next with
 * the lights off. A point listed twice is a sharp corner; everything else is
 * rounded by [smooth].
 *
 * The unit is the height of a capital letter: baseline = 0, top of the A = 1.0.
 */
object FigurePath {

    // The screenshot: the baseline is at pixel row 140, a capital is 86 pixels tall.
    private const val TRACE_BASELINE_PX = 140.0
    private const val TRACE_CAPITAL_PX = 86.0

    /** The pen leaves the paper here: the next point starts a new piece. */
    private val LIFT = -1 to -1

    /** Pixel positions in the screenshot (x right, y down) to pieces in letter heights, starting at x = 0. */
    private fun traced(vararg pixels: Pair<Int, Int>): List<List<PathPoint>> {
        val left = pixels.filter { it != LIFT }.minOf { it.first }
        val pieces = mutableListOf(mutableListOf<PathPoint>())
        for (pixel in pixels) {
            if (pixel == LIFT) pieces.add(mutableListOf())
            else pieces.last().add(PathPoint((pixel.first - left) / TRACE_CAPITAL_PX, (TRACE_BASELINE_PX - pixel.second) / TRACE_CAPITAL_PX))
        }
        return pieces
    }

    private val albstadt = traced(
        // A, and the l joined to it: up the right leg to the apex, down the left leg into the
        // big loop, back along its top, which crosses both legs as the bar, and round the l
        138 to 164, 143 to 153, 154 to 126, 164 to 104, 175 to 83, 178 to 73, 190 to 56, 190 to 56,
        178 to 72, 168 to 82, 123 to 138, 109 to 154, 90 to 169, 79 to 176, 68 to 181, 59 to 185, 48 to 187,
        38 to 187, 31 to 185, 24 to 182, 19 to 178, 14 to 171, 13 to 166, 12 to 161, 13 to 147, 16 to 134,
        22 to 122, 29 to 113, 39 to 104, 49 to 98, 60 to 94, 71 to 91, 80 to 91, 90 to 91, 101 to 94,
        116 to 99, 155 to 116, 173 to 121, 184 to 121, 187 to 120, 192 to 112, 200 to 103, 211 to 86,
        213 to 83, 213 to 80, 213 to 80, 208 to 82, 202 to 91, 196 to 101, 192 to 111, 185 to 124,
        182 to 134, 182 to 139, 183 to 141, 185 to 141, 190 to 139,
        LIFT,
        // b, s, and the stroke that runs up into the stem of the t
        226 to 79, 213 to 103, 207 to 114, 203 to 124, 196 to 137, 196 to 137, 202 to 126, 205 to 123,
        216 to 115, 219 to 114, 221 to 114, 223 to 116, 224 to 118, 224 to 122, 223 to 125, 220 to 132,
        218 to 135, 213 to 140, 209 to 143, 205 to 144, 204 to 142, 205 to 138, 210 to 133, 217 to 129,
        229 to 122, 238 to 119, 241 to 112, 244 to 108, 246 to 107, 248 to 108, 250 to 110, 250 to 110,
        248 to 108, 245 to 107, 243 to 109, 240 to 112, 239 to 117, 238 to 135, 237 to 139, 235 to 141,
        231 to 143, 229 to 141, 229 to 138, 231 to 134, 271 to 101, 276 to 97, 294 to 71, 294 to 71,
        286 to 82, 275 to 99, 263 to 127, 258 to 143, 257 to 148,
        LIFT,
        // bar of the t
        241 to 90, 249 to 91, 271 to 88, 297 to 86, 316 to 86, 322 to 87, 325 to 88,
        LIFT,
        // a and d: round the bowl of the a, along the foot of the d, up its stem and back down,
        // round its bowl, along the foot again and down the tail
        293 to 123, 298 to 113, 298 to 110, 294 to 109, 289 to 113, 282 to 123, 280 to 127, 280 to 130,
        284 to 130, 289 to 125, 292 to 123, 292 to 123, 294 to 132, 295 to 134, 298 to 135, 305 to 133,
        320 to 127, 326 to 118, 333 to 106, 347 to 81, 347 to 81, 330 to 110, 323 to 112, 317 to 116,
        312 to 123, 308 to 132, 308 to 132, 319 to 128, 321 to 128, 321 to 132, 319 to 142, 319 to 145,
        LIFT,
        // bar of the last t
        329 to 92, 372 to 90, 385 to 91,
        LIFT,
        // stem of the last t, down to its curl
        364 to 80, 348 to 107, 341 to 121, 335 to 135, 334 to 144, 334 to 147, 336 to 148, 339 to 148,
        343 to 146, 356 to 132, 362 to 125, 365 to 119,
    )

    private val informatik = traced(
        // I: from the hook on the left over the round top, then the long stem
        404 to 104, 407 to 97, 417 to 83, 424 to 74, 437 to 62, 446 to 54, 452 to 50, 457 to 50, 459 to 53,
        459 to 56, 458 to 60, 455 to 72, 418 to 157,
        LIFT,
        // n and f: the stem of the n, its arch, up to the top of the f and down its long stem
        454 to 112, 450 to 123, 442 to 136, 442 to 136, 445 to 131, 450 to 124, 459 to 116, 463 to 115,
        464 to 118, 461 to 128, 461 to 132, 463 to 135, 466 to 135, 478 to 125, 494 to 99, 497 to 97,
        498 to 102, 496 to 106, 496 to 106, 498 to 102, 498 to 98, 494 to 99, 478 to 126, 470 to 148,
        468 to 155,
        LIFT,
        // bar of the f
        454 to 141, 463 to 139, 477 to 138, 496 to 138,
        LIFT,
        // o and r
        501 to 120, 499 to 122, 497 to 128, 498 to 131, 499 to 133, 502 to 133, 505 to 130, 511 to 118,
        511 to 114, 509 to 111, 506 to 110, 504 to 112, 502 to 116, 502 to 120, 507 to 123, 510 to 123,
        510 to 123, 512 to 124, 520 to 122, 522 to 120, 524 to 114, 524 to 114, 518 to 127, 514 to 134,
        514 to 134, 519 to 125, 525 to 119, 531 to 115, 536 to 112, 540 to 110, 543 to 111, 545 to 113,
        LIFT,
        // m, a, and the t: up to its top and down the stem to its hook
        552 to 112, 547 to 124, 542 to 133, 540 to 135, 540 to 135, 542 to 133, 547 to 124, 560 to 114,
        562 to 114, 563 to 116, 561 to 123, 558 to 128, 558 to 128, 563 to 122, 570 to 116, 573 to 115,
        573 to 118, 570 to 127, 570 to 132, 571 to 136, 574 to 136, 587 to 126, 593 to 118, 596 to 115,
        599 to 113, 603 to 114, 603 to 114, 599 to 122, 595 to 126, 590 to 130, 588 to 130, 587 to 128,
        587 to 128, 588 to 130, 595 to 127, 597 to 127, 599 to 134, 600 to 136, 602 to 137, 606 to 136,
        612 to 133, 620 to 125, 630 to 114, 637 to 108, 649 to 89, 664 to 68, 664 to 68, 649 to 89,
        638 to 107, 630 to 126, 626 to 139, 625 to 144, 626 to 149, 627 to 151, 628 to 153, 634 to 152,
        LIFT,
        // the long bar of the t
        602 to 88, 609 to 89, 627 to 86, 657 to 84, 683 to 84, 700 to 86,
        LIFT,
        // stem of the k
        688 to 82, 672 to 109, 667 to 120, 663 to 125, 656 to 140,
        LIFT,
        // i, without its dot, into the loop and the leg of the k
        655 to 114, 652 to 119, 649 to 127, 649 to 131, 652 to 131, 664 to 124, 682 to 107, 685 to 106,
        687 to 106, 687 to 109, 684 to 113, 678 to 118, 672 to 122, 674 to 134, 676 to 137, 678 to 138,
        681 to 137, 687 to 132, 693 to 126, 698 to 119,
    )

    // Starts and ends at the bottom tip; the short tail past it makes sure the outline closes.
    private val heart = listOf(listOf(
        0.42 to 0.0, 0.04 to 0.46, 0.0 to 0.78, 0.20 to 0.94, 0.42 to 0.70, 0.42 to 0.70,
        0.64 to 0.94, 0.84 to 0.78, 0.80 to 0.46, 0.42 to 0.0, 0.42 to 0.0, 0.34 to -0.10,
    ).map { (x, y) -> PathPoint(x, y) })

    /** Top line first; each line is its pieces in the order they are flown. */
    private val lines = listOf(albstadt, informatik, heart)

    /** Distance from one baseline to the next, in letter heights: the letter, the loop under the A, and a gap. */
    private const val LINE_PITCH = 1.75

    private fun widthOf(line: List<List<PathPoint>>): Double = line.maxOf { piece -> piece.maxOf { it.x } }

    val widthInLetterHeights: Double = lines.maxOf { widthOf(it) }
    val heightInLetterHeights: Double = (lines.size - 1) * LINE_PITCH + 1.0

    /** The lines of the figure, top line first, each centred; a line is its pieces. In metres. */
    fun lines(letterHeightM: Double): List<List<List<PathPoint>>> = lines.mapIndexed { index, line ->
        val offset = (widthInLetterHeights - widthOf(line)) / 2   // centre the line
        val baseline = (lines.size - 1 - index) * LINE_PITCH
        line.map { piece ->
            val control = piece.map { PathPoint((offset + it.x) * letterHeightM, (baseline + it.y) * letterHeightM) }
            // A doubled control point leaves repeated points behind; the flight wants each once.
            smooth(control, rounds = 3).let { path -> path.filterIndexed { n, p -> n == 0 || p != path[n - 1] } }
        }
    }

    /** Chaikin corner cutting: each round replaces every corner with two points on its sides. */
    private fun smooth(points: List<PathPoint>, rounds: Int): List<PathPoint> {
        var line = points
        repeat(rounds) {
            val next = ArrayList<PathPoint>(line.size * 2)
            next.add(line.first())
            line.zipWithNext { p, q ->
                next.add(PathPoint(0.75 * p.x + 0.25 * q.x, 0.75 * p.y + 0.25 * q.y))
                next.add(PathPoint(0.25 * p.x + 0.75 * q.x, 0.25 * p.y + 0.75 * q.y))
            }
            next.add(line.last())
            line = next
        }
        return line
    }
}
