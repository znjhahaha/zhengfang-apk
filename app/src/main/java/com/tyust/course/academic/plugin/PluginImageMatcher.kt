package com.tyust.course.academic.plugin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import org.json.JSONObject
import java.io.File
import kotlin.math.*

/** On-device recognition: bundled neural model for supported strips, pixel matching otherwise. */
internal object PluginImageMatcher {
    data class Image(val bitmap: Bitmap, val width: Int, val height: Int)
    fun load(file: File): Image {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192 || bounds.outWidth.toLong()*bounds.outHeight > 24_000_000)
            throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "图片尺寸不支持本地处理")
        var sample = 1
        while (max(bounds.outWidth,bounds.outHeight)/sample > 1024) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: throw PluginException(PluginErrorCode.VALIDATION_FAILED, "无法读取验证图片")
        return Image(bitmap, bounds.outWidth, bounds.outHeight)
    }
    fun match(background: File, piece: File, expectedY: Double?, app: Context? = null): JSONObject {
        val bg=load(background)
        try {
            val cut=load(piece)
            try {
                val prediction = app?.let { PluginSliderModel.match(it, bg, cut, expectedY) }
                return prediction ?: match(bg.bitmap, cut.bitmap, bg.width, bg.height, cut.width, cut.height, expectedY)
            }
            finally { cut.bitmap.recycle() }
        } finally { bg.bitmap.recycle() }
    }
    internal fun match(background: Bitmap, piece: Bitmap, originalWidth: Int = background.width, originalHeight: Int = background.height,
        pieceWidth: Int = piece.width, pieceHeight: Int = piece.height, expectedY: Double? = null): JSONObject {
        val scale = min(1.0,512.0/max(originalWidth,originalHeight))
        val width=max(1,(originalWidth*scale).roundToInt()); val height=max(1,(originalHeight*scale).roundToInt())
        val pw=max(1,(pieceWidth*scale).roundToInt()); val ph=max(1,(pieceHeight*scale).roundToInt())
        if (pw>=width || ph>height) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "拼图尺寸大于背景")
        val bg=Bitmap.createScaledBitmap(background,width,height,true); val cut=Bitmap.createScaledBitmap(piece,pw,ph,true)
        val pixels=IntArray(width*height); bg.getPixels(pixels,0,width,0,0,width,height)
        val part=IntArray(pw*ph); cut.getPixels(part,0,pw,0,0,pw,ph)
        if (bg!==background) bg.recycle(); if (cut!==piece) cut.recycle()
        fun gray(c:Int)=((c shr 16 and 255)*0.299+(c shr 8 and 255)*0.587+(c and 255)*0.114)
        val luminance=DoubleArray(pixels.size){gray(pixels[it])}
        val edge=DoubleArray(pixels.size)
        for(y in 1 until height-1)for(x in 1 until width-1){val n=y*width+x;edge[n]=hypot(luminance[n+1]-luminance[n-1],luminance[n+width]-luminance[n-width])}
        val mask=BooleanArray(part.size){(part[it] ushr 24)>=128}
        val border=mutableListOf<Pair<Int,Int>>(); val inside=mutableListOf<Triple<Int,Int,Double>>()
        for(y in 0 until ph)for(x in 0 until pw)if(mask[y*pw+x]){
            if(x==0||y==0||x==pw-1||y==ph-1||!mask[y*pw+x-1]||!mask[y*pw+x+1]||!mask[(y-1)*pw+x]||!mask[(y+1)*pw+x])border+=x to y
            if(x%3==0&&y%3==0)inside+=Triple(x,y,gray(part[y*pw+x]))
        }
        if(border.size<12 || inside.size<12)throw PluginException(PluginErrorCode.VALIDATION_FAILED,"拼图遮罩无法识别，请手动拖动")
        // Compare centered pixel values plus mask-edge alignment. Centering tolerates a dark gap.
        val pieceMean=inside.map{it.third}.average()
        val pieceVariance=inside.sumOf{(it.third-pieceMean).pow(2)}/inside.size
        fun score(x:Int,y:Int):Double {
            val mean=inside.sumOf{luminance[(y+it.second)*width+x+it.first]}/inside.size
            val error=inside.sumOf{(luminance[(y+it.second)*width+x+it.first]-mean-(it.third-pieceMean)).pow(2)}/inside.size
            val contour=border.sumOf{edge[(y+it.second)*width+x+it.first]}/border.size
            return (1.0/(1+error/(pieceVariance+64)))*0.75 + min(1.0,contour/100)*0.25
        }
        val fixedY=expectedY?.let{(it*scale).roundToInt().coerceIn(0,height-ph)}
        val ys=if(fixedY!=null) fixedY..fixedY else 0..height-ph
        val candidates=mutableListOf<Triple<Int,Int,Double>>()
        for(y in ys step if(fixedY==null)2 else 1)for(x in 0..width-pw step 2)candidates+=Triple(x,y,score(x,y))
        val coarse=candidates.maxByOrNull{it.third}!!
        for(y in max(0,coarse.second-2)..min(height-ph,coarse.second+2))for(x in max(0,coarse.first-2)..min(width-pw,coarse.first+2)){
            if(fixedY==null||y==fixedY)candidates+=Triple(x,y,score(x,y))
        }
        val best=candidates.maxBy{it.third}
        val second=candidates.filter{abs(it.first-best.first)>max(5,pw/4)||abs(it.second-best.second)>max(5,ph/4)}.maxOfOrNull{it.third}?:0.0
        val confidence=((best.third-second)*6).coerceIn(0.0,1.0)
        val ambiguous=best.third<0.68||confidence<0.32||pieceVariance<8
        return JSONObject().put("x",best.first/scale).put("y",best.second/scale).put("width",originalWidth).put("height",originalHeight)
            .put("confidence",confidence).put("ambiguous",ambiguous)
    }
}
