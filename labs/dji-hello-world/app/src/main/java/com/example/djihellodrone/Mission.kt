package com.example.djihellodrone

/** One flight the app can run. [run] blocks on the mission thread until the drone has landed. */
interface Mission {
    /** "Mission 1" and so on: the status line, and the flight log's file name. */
    val name: String
    /**
     * What to do when [run] fails: land on the spot, or with true stop and hover, because
     * the drone may be far from the pilot and should not land blind.
     */
    val hoverOnFailure: Boolean
    /** What the drone will do and what the pilot has to prepare, for the start dialog. */
    val briefing: String
    fun run()
}

/** The plane a drawing lies in: standing across the nose, or lying flat. */
enum class Plane { UPRIGHT, FLAT }

/**
 * The drone's own light that draws mission 2: none (an external light draws, and the
 * drone's lights stay off during the figure), the white light under the drone, or the
 * green status lights.
 */
enum class Pen { NONE, WHITE, GREEN }
