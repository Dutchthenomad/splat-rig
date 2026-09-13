package com.splatrig.capture

import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.roundToInt

class Coverage {
    enum class Band { LOW, MID, HIGH }
    data class Cell(val yaw: Int, val band: Band) {
        val key: String get() = "${band.name}-$yaw"
    }
    private val hits = mutableMapOf<String, Int>()
    var frontYawDeg: Float? = null
        private set
    fun reset() { hits.clear(); frontYawDeg = null }
    fun lockFront(yawDeg: Float) { frontYawDeg = wrap(yawDeg) }
    fun mark(yawDeg: Float, pitchDeg: Float): Cell? {
        val front = frontYawDeg ?: return null
        val rel = wrap(yawDeg - front)
        val yawBin = ((rel / 22.5f).roundToInt()) and 15
        val band = when {
            pitchDeg > 22f -> Band.HIGH
            pitchDeg < -18f -> Band.LOW
            else -> Band.MID
        }
        val cell = Cell(yawBin, band)
        hits[cell.key] = (hits[cell.key] ?: 0) + 1
        return cell
    }
    fun count(cell: Cell) = hits[cell.key] ?: 0
    fun snapshot(): Map<String, Int> = hits.toMap()
    fun sideFilled(side: Side, band: Band) = side.bins.any { count(Cell(it, band)) > 0 }
    fun exportReady(kept: Int): Boolean {
        if (kept < 120) return false
        val midOk = listOf(Side.FRONT, Side.REAR, Side.DS, Side.PS).all { sideFilled(it, Band.MID) }
        val highOk = sideFilled(Side.FRONT, Band.HIGH) && sideFilled(Side.REAR, Band.HIGH)
        return midOk && highOk
    }
    fun missing(): List<String> {
        val out = mutableListOf<String>()
        if (!sideFilled(Side.FRONT, Band.MID)) out += "front/grille"
        if (!sideFilled(Side.REAR, Band.MID)) out += "rear/spare"
        if (!sideFilled(Side.DS, Band.MID)) out += "driver wall"
        if (!sideFilled(Side.PS, Band.MID)) out += "passenger wall"
        if (!sideFilled(Side.FRONT, Band.HIGH)) out += "cabover face"
        if (!sideFilled(Side.REAR, Band.HIGH)) out += "rear cap/roof"
        return out
    }
    enum class Side(val bins: IntArray) {
        FRONT(intArrayOf(15, 0, 1)),
        PS(intArrayOf(3, 4, 5)),
        REAR(intArrayOf(7, 8, 9)),
        DS(intArrayOf(11, 12, 13)),
    }
    companion object {
        fun wrap(deg: Float): Float {
            var d = deg % 360f
            if (d < 0) d += 360f
            return d
        }
        fun yawPitchFromRotationVector(rv: FloatArray): Pair<Float, Float> {
            val rot = FloatArray(9)
            val ori = FloatArray(3)
            SensorManager.getRotationMatrixFromVector(rot, rv)
            SensorManager.getOrientation(rot, ori)
            val yaw = Math.toDegrees(ori[0].toDouble()).toFloat()
            val pitch = Math.toDegrees(ori[1].toDouble()).toFloat()
            return wrap(yaw) to pitch
        }
        fun tooFast(gyroRad: FloatArray): Boolean {
            val mag = abs(gyroRad[0]) + abs(gyroRad[1]) + abs(gyroRad[2])
            return mag > 1.6f
        }
    }
}
