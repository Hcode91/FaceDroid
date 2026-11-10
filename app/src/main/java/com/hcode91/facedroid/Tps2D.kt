package com.hcode91.facedroid

import kotlin.math.abs
import kotlin.math.ln

/**
 * Thin-plate spline: a smooth 2D warp that sends each source point exactly (or, with
 * [smoothing] > 0, nearly) onto its target point and bends everything else as little as possible.
 */
class Tps2D(src: FloatArray, dst: FloatArray, smoothing: Double = 0.0) {
    private val n = src.size / 2
    private val sx = DoubleArray(n) { src[it * 2].toDouble() }
    private val sy = DoubleArray(n) { src[it * 2 + 1].toDouble() }
    private val wx: DoubleArray
    private val wy: DoubleArray

    init {
        require(n >= 3) { "Need at least 3 points" }
        val m = n + 3
        val a = Array(m) { DoubleArray(m) }
        for (i in 0 until n) {
            for (j in 0 until n) a[i][j] = u(sx[i] - sx[j], sy[i] - sy[j])
            a[i][i] += smoothing
            a[i][n] = 1.0; a[i][n + 1] = sx[i]; a[i][n + 2] = sy[i]
            a[n][i] = 1.0; a[n + 1][i] = sx[i]; a[n + 2][i] = sy[i]
        }
        wx = solve(a, DoubleArray(m) { if (it < n) dst[it * 2].toDouble() else 0.0 })
        wy = solve(a, DoubleArray(m) { if (it < n) dst[it * 2 + 1].toDouble() else 0.0 })
    }

    fun map(x: Float, y: Float, out: FloatArray, offset: Int = 0) {
        var rx = wx[n] + wx[n + 1] * x + wx[n + 2] * y
        var ry = wy[n] + wy[n + 1] * x + wy[n + 2] * y
        for (i in 0 until n) {
            val k = u(x - sx[i], y - sy[i])
            rx += wx[i] * k; ry += wy[i] * k
        }
        out[offset] = rx.toFloat(); out[offset + 1] = ry.toFloat()
    }

    fun map(x: Float, y: Float): FloatArray = FloatArray(2).also { map(x, y, it) }

    private fun u(dx: Double, dy: Double): Double {
        val r2 = dx * dx + dy * dy
        return if (r2 < 1e-12) 0.0 else 0.5 * r2 * ln(r2)
    }

    companion object {
        fun solve(src: Array<DoubleArray>, rhs: DoubleArray): DoubleArray {
            val m = rhs.size
            val a = Array(m) { src[it].copyOf() }
            val x = rhs.copyOf()
            for (col in 0 until m) {
                var piv = col
                for (r in col + 1 until m) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
                val t = a[col]; a[col] = a[piv]; a[piv] = t
                val tx = x[col]; x[col] = x[piv]; x[piv] = tx
                val d = a[col][col]
                if (abs(d) < 1e-12) continue
                for (r in col + 1 until m) {
                    val f = a[r][col] / d
                    if (f == 0.0) continue
                    for (c in col until m) a[r][c] -= f * a[col][c]
                    x[r] -= f * x[col]
                }
            }
            for (r in m - 1 downTo 0) {
                var s = x[r]
                for (c in r + 1 until m) s -= a[r][c] * x[c]
                x[r] = if (abs(a[r][r]) < 1e-12) 0.0 else s / a[r][r]
            }
            return x
        }
    }
}
