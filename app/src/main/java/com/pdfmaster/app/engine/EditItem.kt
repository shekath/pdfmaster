package com.pdfmaster.app.engine

import java.io.File

/**
 * An overlay placed in the page editor. Coordinates are normalised to the displayed
 * (rotation-applied) page: 0..1 from the top-left corner.
 */
sealed class EditItem {
    abstract val page: Int

    /** [size] is the font size as a fraction of the page width. */
    data class Text(override val page: Int, val x: Float, val y: Float, val text: String, val size: Float, val color: Int) : EditItem()

    /** [width] is the stroke width as a fraction of the page width; points are (x, y) pairs. */
    data class Ink(override val page: Int, val points: List<Pair<Float, Float>>, val width: Float, val color: Int) : EditItem()

    data class Box(override val page: Int, val l: Float, val t: Float, val r: Float, val b: Float, val color: Int, val alpha: Float) : EditItem()

    data class Picture(override val page: Int, val x: Float, val y: Float, val w: Float, val h: Float, val file: File) : EditItem()
}
