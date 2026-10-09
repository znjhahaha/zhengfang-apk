package com.tyust.course.academic.plugin

import kotlin.math.*

/** Coordinate conversion is local to a selected plugin location; no Android mock provider is used. */
internal object PluginCoordinates {
    data class Point(val latitude: Double, val longitude: Double)
    private const val A = 6378245.0
    private const val EE = 0.006693421622965943
    private const val X_PI = PI * 3000.0 / 180.0
    fun convert(point: Point, from: String, to: String): Point {
        require(point.latitude.isFinite() && point.longitude.isFinite() && abs(point.latitude) <= 90 && abs(point.longitude) <= 180)
        require(from in setOf("WGS84", "GCJ02", "BD09") && to in setOf("WGS84", "GCJ02", "BD09"))
        if (from == to) return point
        val gcj = when (from) { "WGS84" -> wgsToGcj(point); "BD09" -> bdToGcj(point); else -> point }
        return when (to) { "BD09" -> gcjToBd(gcj); "WGS84" -> gcjToWgs(gcj); else -> gcj }
    }
    private fun outside(p: Point) = p.longitude < 72.004 || p.longitude > 137.8347 || p.latitude < 0.8293 || p.latitude > 55.8271
    private fun wgsToGcj(p: Point): Point {
        if (outside(p)) return p
        val x = p.longitude - 105; val y = p.latitude - 35
        var lat = -100 + 2*x + 3*y + 0.2*y*y + 0.1*x*y + 0.2*sqrt(abs(x))
        var lon = 300 + x + 2*y + 0.1*x*x + 0.1*x*y + 0.1*sqrt(abs(x))
        val wave = (20*sin(6*x*PI) + 20*sin(2*x*PI))*2/3
        lat += wave + (20*sin(y*PI) + 40*sin(y*PI/3))*2/3 + (160*sin(y*PI/12) + 320*sin(y*PI/30))*2/3
        lon += wave + (20*sin(x*PI) + 40*sin(x*PI/3))*2/3 + (150*sin(x*PI/12) + 300*sin(x*PI/30))*2/3
        val rad = p.latitude*PI/180; val magic = 1 - EE*sin(rad).pow(2); val root = sqrt(magic)
        return Point(p.latitude + lat*180/((A*(1-EE)/(magic*root))*PI), p.longitude + lon*180/(A/root*cos(rad)*PI))
    }
    private fun gcjToWgs(p: Point): Point {
        if (outside(p)) return p
        var result = p
        repeat(8) { val test = wgsToGcj(result); result = Point(result.latitude + p.latitude-test.latitude, result.longitude+p.longitude-test.longitude) }
        return result
    }
    private fun gcjToBd(p: Point): Point {
        val z = hypot(p.longitude,p.latitude) + 0.00002*sin(p.latitude*X_PI)
        val theta = atan2(p.latitude,p.longitude) + 0.000003*cos(p.longitude*X_PI)
        return Point(z*sin(theta)+0.006, z*cos(theta)+0.0065)
    }
    private fun bdToGcj(p: Point): Point {
        val x=p.longitude-0.0065; val y=p.latitude-0.006
        val z=hypot(x,y)-0.00002*sin(y*X_PI); val theta=atan2(y,x)-0.000003*cos(x*X_PI)
        return Point(z*sin(theta), z*cos(theta))
    }
}
