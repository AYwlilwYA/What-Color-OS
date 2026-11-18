/* DreamGlass contributors, 2026-10-29. GPL-3.0. GPU glyph-mask and glass material. */
package com.coloros16.liquidglass.hook
import android.graphics.*
import android.graphics.drawable.Drawable

/** Does not redraw fonts or alter TextView paint. The actual RenderNode alpha is the glyph mask. */
internal class KeyguardGlassMaterial(val glyph:Boolean) {
    val shader=RuntimeShader(SOURCE)
    // RenderEffect snapshots the shader builder. Create it after update() has bound the image
    // and uniforms, and recreate only when the attachment geometry/material changes.
    val effect:RenderEffect? get()=if(glyph)RenderEffect.createRuntimeShaderEffect(shader,"uContent") else null
    private var image:Bitmap?=null
    init {
        shader.setFloatUniform("uMode",if(glyph)0f else 1f)
        shader.setFloatUniform("uStateTint",0f,0f,0f,0f)
        if(!glyph)shader.setInputShader("uContent",LinearGradient(0f,0f,1f,1f,Color.TRANSPARENT,Color.TRANSPARENT,Shader.TileMode.CLAMP))
    }
    fun clearImage() {
        image=null
        shader.setInputShader("uWallpaper",LinearGradient(0f,0f,1f,1f,Color.TRANSPARENT,Color.TRANSPARENT,Shader.TileMode.CLAMP))
    }
    fun update(bitmap:Bitmap,matrix:FloatArray,width:Float,height:Float,screenW:Int,screenH:Int,strength:Float,corner:Float,
               wallpaperZoom:Float=1f) {
        if(image!==bitmap) { image=bitmap;shader.setInputShader("uWallpaper",BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP).apply { setFilterMode(BitmapShader.FILTER_MODE_LINEAR) }) }
        val map=KeyguardGlassPolicy.mapping(bitmap.width,bitmap.height,screenW,screenH)
        // 壁纸按屏幕中心缩放 s 时，屏幕上 p 显示的是未缩放内容 c+(p-c)/s：
        // 采样坐标先除 s，再补中心偏移（缩放差 = 当前缩放 ÷ 抓屏时的缩放）。
        val s=wallpaperZoom.coerceIn(.4f,3f)
        val k=map[0]/s
        val cx=screenW*.5f*map[0]*(1f-1f/s)
        val cy=screenH*.5f*map[0]*(1f-1f/s)
        shader.setFloatUniform("uMapX",matrix[0]*k,matrix[1]*k,matrix[2]*k+map[1]+cx)
        shader.setFloatUniform("uMapY",matrix[3]*k,matrix[4]*k,matrix[5]*k+map[2]+cy)
        shader.setFloatUniform("uSize",width,height)
        shader.setFloatUniform("uStrength",strength.coerceIn(0f,2f))
        shader.setFloatUniform("uCorner",corner.coerceIn(0f,minOf(width,height)*.5f))
    }
    class Background:Drawable() {
        val material=KeyguardGlassMaterial(false)
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { shader=material.shader }
        var radius=24f
        /** 被替换掉的原生背景，仅在淡入期间垫在玻璃下面。 */
        var native:Drawable?=null
        private var nativeAlpha=255
        private var baseAlpha=255
        private var fadeStart=0L
        private var faded=false
        val fading get()=!faded
        fun restart() { faded=false;fadeStart=0L }
        private fun fade():Float {
            if(faded)return 1f
            val now=android.os.SystemClock.uptimeMillis()
            if(fadeStart==0L) { fadeStart=now;nativeAlpha=native?.alpha ?: 255 }
            val value=(now-fadeStart)/FADE_MS
            if(value>=1f) { faded=true;return 1f }
            return value
        }
        override fun draw(canvas:Canvas) {
            if(!canvas.isHardwareAccelerated)return
            val fade=fade()
            if(fade<1f) {
                native?.let {
                    it.setBounds(bounds)
                    it.alpha=nativeAlpha
                    it.draw(canvas)
                }
                paint.alpha=(baseAlpha*fade).toInt().coerceIn(0,255)
                invalidateSelf()
            } else paint.alpha=baseAlpha
            canvas.drawRect(bounds,paint)
        }
        override fun getOutline(outline:Outline) { outline.setRoundRect(bounds,radius) }
        override fun setAlpha(alpha:Int) { baseAlpha=alpha;paint.alpha=alpha;invalidateSelf() }
        override fun setColorFilter(filter:ColorFilter?) {
            // Preserve native active-state tint as a translucent colour, rather than an opaque fill.
            val colour=when(filter) { is PorterDuffColorFilter->try { filter.javaClass.getMethod("getColor").invoke(filter) as Int } catch(_:Throwable) { Color.TRANSPARENT };is BlendModeColorFilter->filter.color;else->Color.TRANSPARENT }
            material.shader.setFloatUniform("uStateTint",Color.red(colour)/255f,Color.green(colour)/255f,Color.blue(colour)/255f,if(colour==Color.TRANSPARENT)0f else .12f)
            invalidateSelf()
        }
        @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
    companion object {
        const val FADE_MS=520f
        const val SOURCE="""
            uniform shader uContent;
            uniform shader uWallpaper;
            uniform float3 uMapX;
            uniform float3 uMapY;
            uniform float2 uSize;
            uniform float uMode;
            uniform float uStrength;
            uniform float uCorner;
            uniform float4 uStateTint;
            float distanceBox(float2 p) {
                float2 q=abs(p-uSize*0.5)-(uSize*0.5-uCorner);
                return min(max(q.x,q.y),0.0)+length(max(q,0.0))-uCorner;
            }
            float glyphMask(float2 q) { return smoothstep(0.35,0.65,float(uContent.eval(q).a)); }
            half4 main(float2 p) {
                float alpha;
                float2 grad;
                float bevel=clamp(min(uSize.x,uSize.y)*0.022,1.5,7.0);
                if(uMode<0.5) {
                    alpha=glyphMask(p);
                    if(alpha<0.001)return half4(0.0);
                    float2 nearGrad=float2(glyphMask(p+float2(1.25,0.0))-glyphMask(p-float2(1.25,0.0)),
                                glyphMask(p+float2(0.0,1.25))-glyphMask(p-float2(0.0,1.25)));
                    float2 farGrad=float2(glyphMask(p+float2(bevel,0.0))-glyphMask(p-float2(bevel,0.0)),
                                glyphMask(p+float2(0.0,bevel))-glyphMask(p-float2(0.0,bevel)));
                    grad=nearGrad*0.35+farGrad*0.65;
                } else {
                    float d=distanceBox(p);alpha=1.0-smoothstep(-0.75,0.75,d);
                    if(alpha<0.001)return half4(0.0);
                    float2 normal=float2(distanceBox(p+float2(1.0,0.0))-distanceBox(p-float2(1.0,0.0)),
                                         distanceBox(p+float2(0.0,1.0))-distanceBox(p-float2(0.0,1.0)));
                    grad=-normal*exp(-abs(d)/max(bevel,2.2));
                }
                float edge=clamp(length(grad),0.0,1.0);
                float2 normal=-grad/max(length(grad),0.001);
                float2 q=p+normal*edge*(bevel*0.9*uStrength);
                float2 source=float2(dot(float3(q,1.0),uMapX),dot(float3(q,1.0),uMapY));
                half3 glass=uWallpaper.eval(source).rgb;
                float lum=dot(float3(glass),float3(0.2126,0.7152,0.0722));
                half contrast=lum>0.52?half(0.0):half(1.0);
                glass=mix(glass,half3(contrast),half(uMode<0.5?0.09:0.10));
                float facing=dot(normal,normalize(float2(-0.55,-0.83)));
                glass+=half3(max(facing,0.0)*edge*0.66*uStrength);
                glass-=half3(max(-facing,0.0)*edge*0.22*uStrength);
                glass=mix(glass,half3(uStateTint.rgb),half(uStateTint.a));
                return half4(clamp(glass,half3(0.0),half3(1.0))*half(alpha),half(alpha));
            }
        """
    }
}
