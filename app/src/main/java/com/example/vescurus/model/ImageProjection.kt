package com.example.vescurus.model

data class ImageProjection(val width: Float,val height: Float,val offsetX: Float,val offsetY: Float)
fun imageProjection(imageWidth: Int,imageHeight: Int,viewWidth: Float,viewHeight: Float,fit: Boolean): ImageProjection {
    require(imageWidth>0 && imageHeight>0 && viewWidth>=0 && viewHeight>=0)
    val sx=viewWidth/imageWidth;val sy=viewHeight/imageHeight
    val scale=if(fit)minOf(sx,sy) else maxOf(sx,sy)
    val width=imageWidth*scale;val height=imageHeight*scale
    return ImageProjection(width,height,(viewWidth-width)/2,(viewHeight-height)/2)
}
