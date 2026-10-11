package dev.zanderp.opencfmoto

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GPU fit/scale compositor for the Android Auto → bike video path.
 *
 * The AA video decoder renders into [inputSurface] (backed by a [SurfaceTexture]). Each decoded
 * frame is drawn — centered, on a black background — into the encoder's input surface (set later via
 * [setOutput], once the bike tells us its canvas size). This decouples the AA source resolution
 * (a fixed 16:9-ish landscape/portrait) from the differently-shaped bike canvas. The user's
 * [ScreenFit] preference chooses how: FILL (cover/crop), FIT (letterbox), or STRETCH (distort).
 *
 * [inputSurface] exists immediately (before the bike connects) so AA can reach steady video, which
 * is what triggers the bike hand-off in the first place. Until [setOutput] is called the render
 * thread just drains decoded frames (keeps AA flowing) without drawing anywhere.
 *
 * All GL work happens on a dedicated thread with the EGL context current. Based on the standard
 * SurfaceTexture→encoder pattern (Grafika).
 */
class AaCompositor(private val log: (String) -> Unit) {

    private val thread = HandlerThread("aa-compositor").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE   // keeps a current surface before output exists
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // Optional second render target: an in-app phone preview (HudViewActivity). The same decoded AA
    // frame is drawn — aspect-fit — into this surface so the rider can watch/drive the dash from the
    // phone. It's independent of the bike encoder output and works even before the bike links.
    private var previewSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    @Volatile private var previewW = 0
    @Volatile private var previewH = 0
    private var ppX = 0
    private var ppY = 0
    private var ppW = 0
    private var ppH = 0
    // AA source (decoder buffer) dims — the preview aspect-fits these; known from start(), independent
    // of whether the bike canvas (setOutput) is configured yet.
    @Volatile private var bufW = 0
    @Volatile private var bufH = 0

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private var uCrop = 0
    private var textureId = 0

    // Fraction of the coded AA frame that holds content (1.0 = whole frame). <1 when match-panel-aspect
    // margins are advertised: AA draws its UI into a sub-rect, so we sample only that region.
    @Volatile private var cropU = 1f
    @Volatile private var cropV = 1f
    private lateinit var surfaceTexture: SurfaceTexture

    /** Where the AA decoder renders. Valid after [start]. */
    @Volatile var inputSurface: Surface? = null
        private set

    // Output canvas (bike) + source (AA) dims; viewport is derived from these.
    @Volatile private var canvasW = 0
    @Volatile private var canvasH = 0
    @Volatile private var srcW = 0
    @Volatile private var srcH = 0
    @Volatile private var vpX = 0
    @Volatile private var vpY = 0
    @Volatile private var vpW = 0
    @Volatile private var vpH = 0
    @Volatile private var fitMode: ScreenFit = ScreenFit.FILL
    // Usable area after ScreenMargins, top-left origin (same convention as vpX/vpY and touch).
    @Volatile private var areaX = 0
    @Volatile private var areaY = 0
    @Volatile private var areaWpx = 0
    @Volatile private var areaHpx = 0
    /** "Fit to dash" screen open: draw a yellow frame around the usable area on the bike. */
    @Volatile private var calibrating = false

    private val texMatrix = FloatArray(16)

    // Frame pacing (battery): the compositor throttles live AA frames to [minFrameIntervalMs] (set
    // from the user's PowerMode) so we don't GPU-composite + hardware-encode + Wi-Fi-transmit more
    // often than needed. A frame that arrives too soon is coalesced (its texture is still latched)
    // and flushed by the keep-alive tick, so no motion is lost — we just cap the rate.
    @Volatile private var hasContent = false
    @Volatile private var pendingFrame = false
    private var lastDrawMs = 0L
    @Volatile private var minFrameIntervalMs = 42L  // default 24 fps; overridden by setFrameCap()

    // Keep-alive cadence. The tick is fast enough to promptly flush a coalesced trailing frame after
    // motion stops; the idle re-draw is deliberately slow — the bike only needs a frame every ~9s
    // (CLIENT_INFO socketTimeoutPeriodWifi) to stay connected, so re-encoding a *static* screen more
    // than ~twice a second is wasted heat. This is the big idle-power win over the old 15fps floor.
    private val KEEPALIVE_TICK_MS = 150L
    private val IDLE_REDRAW_MS = 2000L

    // Full-screen quad (triangle strip): pos.xy + tex.uv interleaved.
    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f,
            ))
            position(0)
        }

    /**
     * @param bufferW/bufferH when >0, size the SurfaceTexture for that capture buffer (mirror /
     *   single-app MediaProjection). When both are 0, use the active AA video spec + match-aspect
     *   crop (Android Auto path).
     */
    fun start(bufferW: Int = 0, bufferH: Int = 0) {
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post {
            try {
                initEgl()
                initGl()
                val mirrorBuf = bufferW > 0 && bufferH > 0
                if (mirrorBuf) {
                    bufW = bufferW; bufH = bufferH
                    cropU = 1f; cropV = 1f
                } else {
                    val spec = BikeProfileHolder.aaVideo
                    bufW = spec.width; bufH = spec.height
                    // Match-aspect margins are known before AA starts, so the in-app Dash view preview is
                    // aspect-correct immediately (even before the bike connects / sets the bike canvas).
                    val m = BikeProfileHolder.aaContentMargins
                    cropU = ((spec.width - m.marginW).toFloat() / spec.width).coerceIn(0.05f, 1f)
                    cropV = ((spec.height - m.marginH).toFloat() / spec.height).coerceIn(0.05f, 1f)
                }
                surfaceTexture = SurfaceTexture(textureId)
                surfaceTexture.setDefaultBufferSize(bufW, bufH)
                surfaceTexture.setOnFrameAvailableListener({ handler.post { onFrame() } }, handler)
                inputSurface = Surface(surfaceTexture)
                handler.postDelayed(keepAlive, KEEPALIVE_TICK_MS)
                val kind = if (mirrorBuf) "mirror capture" else "AA decoder"
                log("[COMPOSITOR] ready (buffer size ${bufW}x${bufH}) — $kind input surface up (no output canvas yet)")
            } catch (e: Exception) {
                log("[COMPOSITOR] init failed: $e")
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    }

    /** Mirror / single-app capture: captured content resized — update the SurfaceTexture + fit math. */
    fun updateCaptureSize(w: Int, h: Int) {
        val cw = w.coerceAtLeast(2)
        val ch = h.coerceAtLeast(2)
        handler.post {
            try {
                bufW = cw; bufH = ch
                if (::surfaceTexture.isInitialized) surfaceTexture.setDefaultBufferSize(cw, ch)
                if (canvasW > 0 && canvasH > 0) {
                    srcW = cw; srcH = ch
                    computeViewport()
                    log("[COMPOSITOR] capture size → ${cw}x$ch → draw rect=${vpW}x$vpH @($vpX,$vpY)")
                    if (hasContent) drawFrame()
                }
            } catch (e: Exception) {
                log("[COMPOSITOR] updateCaptureSize failed: $e")
            }
        }
    }

    /** Point the compositor at the encoder's input surface, sized to the bike canvas. */
    fun setOutput(encoderSurface: Surface, cw: Int, ch: Int, sw: Int, sh: Int, fit: ScreenFit) {
        handler.post {
            try {
                if (windowSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, windowSurface)
                    windowSurface = EGL14.EGL_NO_SURFACE
                }
                val attrs = intArrayOf(EGL14.EGL_NONE)
                windowSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, encoderSurface, attrs, 0)
                canvasW = cw; canvasH = ch; srcW = sw; srcH = sh; fitMode = fit
                computeViewport()
                log("[COMPOSITOR] output set canvas=${cw}x$ch src=${sw}x$sh fit=$fit → draw rect=${vpW}x$vpH @($vpX,$vpY)")
            } catch (e: Exception) {
                log("[COMPOSITOR] setOutput failed: $e")
            }
        }
    }

    /**
     * Attach an in-app phone preview surface (from [dev.zanderp.opencfmoto.HudViewActivity]'s
     * SurfaceView). The decoded AA frame is drawn aspect-fit into it. Forces one immediate redraw so
     * the preview shows the current (possibly static) frame right away instead of waiting for motion.
     */
    fun setPreview(surface: Surface, w: Int, h: Int) {
        handler.post {
            try {
                if (previewSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
                    EGL14.eglDestroySurface(eglDisplay, previewSurface)
                    previewSurface = EGL14.EGL_NO_SURFACE
                }
                previewSurface = EGL14.eglCreateWindowSurface(
                    eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0,
                )
                previewW = w; previewH = h
                computePreviewRect()
                log("[COMPOSITOR] preview attached ${w}x$h → draw rect=${ppW}x$ppH @($ppX,$ppY)")
                if (hasContent) drawFrame()
            } catch (e: Exception) {
                log("[COMPOSITOR] setPreview failed: $e")
            }
        }
    }

    /** The preview SurfaceView changed size (rotation / layout). Recompute the fit rect. */
    fun updatePreviewSize(w: Int, h: Int) {
        handler.post {
            previewW = w; previewH = h
            computePreviewRect()
            if (hasContent && previewSurface != EGL14.EGL_NO_SURFACE) drawFrame()
        }
    }

    /** Detach the phone preview. Blocks until the GL thread has destroyed the EGL surface, so the
     *  caller (surfaceDestroyed) can safely let Android release the underlying Surface afterwards. */
    fun clearPreview() {
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post {
            try {
                if (previewSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
                    EGL14.eglDestroySurface(eglDisplay, previewSurface)
                }
            } catch (_: Exception) {
            } finally {
                previewSurface = EGL14.EGL_NO_SURFACE
                previewW = 0; previewH = 0
                latch.countDown()
            }
        }
        try { latch.await(1, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
    }

    /**
     * Aspect-fit the AA source into the preview surface ([previewW]x[previewH]). Uses the *usable*
     * area (coded frame minus match-aspect margins, i.e. scaled by [cropU]/[cropV]) so the in-app
     * Dash view matches what the compositor actually samples and isn't stretched.
     */
    private fun computePreviewRect() {
        if (previewW == 0 || previewH == 0 || bufW == 0 || bufH == 0) return
        val srcAspect = (bufW * cropU) / (bufH * cropV)
        val viewAspect = previewW.toFloat() / previewH
        if (srcAspect < viewAspect) {           // source narrower → fit height, bars left/right
            ppH = previewH; ppW = Math.round(previewH * srcAspect)
        } else {                                // source wider → fit width, bars top/bottom
            ppW = previewW; ppH = Math.round(previewW / srcAspect)
        }
        ppX = (previewW - ppW) / 2
        ppY = (previewH - ppH) / 2
    }

    /**
     * Inverse of the letterbox: map a point in the bike canvas (the surface whose size we reported to
     * the bike, [canvasW]x[canvasH]) to Android Auto source coordinates ([srcW]x[srcH]). Returns null
     * if the point falls in a black bar (outside the drawn AA rect).
     *
     * Note: a prior experiment added a coded-buffer center offset (buf−src)/2 for match-aspect
     * panels; that broke NK Adv touch (~+Y) even with Match off. Keep the v2.0.6 linear map until
     * FILL+margins gets a dedicated, gated fix.
     */
    fun mapCanvasToSource(cx: Int, cy: Int): Pair<Int, Int>? {
        if (vpW == 0 || vpH == 0 || srcW == 0 || srcH == 0) return null
        val rx = cx - vpX
        val ry = cy - vpY
        if (rx < 0 || ry < 0 || rx >= vpW || ry >= vpH) return null
        val sx = (rx.toLong() * srcW / vpW).toInt().coerceIn(0, srcW - 1)
        val sy = (ry.toLong() * srcH / vpH).toInt().coerceIn(0, srcH - 1)
        return sx to sy
    }

    /**
     * Derive the draw rectangle from the current [fitMode]. All three modes centre the rect, so the
     * inverse map in [mapCanvasToSource] stays a single linear transform regardless of mode:
     *  - [ScreenFit.FIT]     shrink to fully contain src (letterbox; rect ≤ canvas, black bars).
     *  - [ScreenFit.FILL]    grow to fully cover the canvas (rect ≥ canvas; edges clipped by GL).
     *  - [ScreenFit.STRETCH] rect = canvas exactly (ignores aspect).
     */
    private fun computeViewport() {
        if (canvasW == 0 || canvasH == 0 || srcW == 0 || srcH == 0) return
        // Inset by rider/profile screen margins so chrome (e.g. 800NK pull-down) stays black.
        val mT = ScreenMargins.top.coerceIn(0, canvasH)
        val mB = ScreenMargins.bottom.coerceIn(0, canvasH - mT)
        val mL = ScreenMargins.left.coerceIn(0, canvasW)
        val mR = ScreenMargins.right.coerceIn(0, canvasW - mL)
        val areaW = (canvasW - mL - mR).coerceAtLeast(1)
        val areaH = (canvasH - mT - mB).coerceAtLeast(1)
        val srcAspect = srcW.toFloat() / srcH
        val areaAspect = areaW.toFloat() / areaH
        when (fitMode) {
            ScreenFit.STRETCH -> {
                vpW = areaW; vpH = areaH
            }
            ScreenFit.FIT -> {
                if (srcAspect < areaAspect) {
                    vpH = areaH; vpW = Math.round(areaH * srcAspect)
                } else {
                    vpW = areaW; vpH = Math.round(areaW / srcAspect)
                }
            }
            ScreenFit.FILL -> {
                if (srcAspect < areaAspect) {
                    vpW = areaW; vpH = Math.round(areaW / srcAspect)
                } else {
                    vpH = areaH; vpW = Math.round(areaH * srcAspect)
                }
            }
        }
        vpX = mL + (areaW - vpW) / 2
        vpY = mT + (areaH - vpH) / 2
        areaX = mL; areaY = mT; areaWpx = areaW; areaHpx = areaH
    }

    /** Live from the "Fit to dash" screen: change [ScreenFit] without reconnecting. */
    fun setFit(fit: ScreenFit) {
        handler.post {
            fitMode = fit
            computeViewport()
            if (hasContent) drawFrame()
            log("[COMPOSITOR] fit → $fit (live) draw rect=${vpW}x$vpH @($vpX,$vpY)")
        }
    }

    /** Show / hide the yellow calibration frame on the dash. */
    fun setCalibration(on: Boolean) {
        handler.post {
            calibrating = on
            if (hasContent) drawFrame()
        }
    }

    /** GL (bottom-left origin) frame around the usable area, drawn with scissored clears. */
    private fun drawCalibrationFrame() {
        if (canvasW == 0 || canvasH == 0 || areaWpx <= 0 || areaHpx <= 0) return
        val t = 4
        val x = areaX
        val y = canvasH - areaY - areaHpx
        val w = areaWpx
        val h = areaHpx
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(1f, 0.82f, 0.1f, 1f)
        fun bar(bx: Int, by: Int, bw: Int, bh: Int) {
            if (bw <= 0 || bh <= 0) return
            GLES20.glScissor(bx, by, bw, bh)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        bar(x, y + h - t, w, t)          // top
        bar(x, y, w, t)                  // bottom
        bar(x, y, t, h)                  // left
        bar(x + w - t, y, t, h)          // right
        // Arrow-ish tabs at each edge's midpoint so a partly hidden edge is still obvious.
        val tab = 24
        bar(x + w / 2 - tab, y + h - t - 10, tab * 2, 10)
        bar(x + w / 2 - tab, y + t, tab * 2, 10)
        bar(x + t, y + h / 2 - tab, 10, tab * 2)
        bar(x + w - t - 10, y + h / 2 - tab, 10, tab * 2)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    /**
     * Sample only [cu]x[cv] (fractions) of the coded AA frame — the usable content area when
     * match-panel-aspect margins are advertised. Crop is **centered** (see `uCrop` shader).
     * 1,1 = whole frame (default).
     */
    fun setSourceCrop(cu: Float, cv: Float) {
        handler.post {
            cropU = cu.coerceIn(0.05f, 1f)
            cropV = cv.coerceIn(0.05f, 1f)
            computePreviewRect()
            if (hasContent) drawFrame()
        }
    }

    /** Re-apply [ScreenMargins] after the rider changes them mid-session. */
    fun refreshMargins() {
        handler.post {
            computeViewport()
            if (hasContent) drawFrame()
            if (ScreenMargins.any) {
                log("[COMPOSITOR] screen margins: ${ScreenMargins.summary()}")
            }
        }
    }

    /** Set the live-frame cap (frames/sec) from the user's [dev.zanderp.opencfmoto.PowerMode]. */
    fun setFrameCap(fps: Int) {
        val clamped = fps.coerceIn(10, 60)
        minFrameIntervalMs = (1000L / clamped)
        log("[COMPOSITOR] frame cap → ${clamped}fps (min ${minFrameIntervalMs}ms/frame)")
    }

    private fun onFrame() {
        try {
            surfaceTexture.updateTexImage()
        } catch (e: Exception) {
            return
        }
        hasContent = true
        // Throttle live frames to the power-mode cap. A too-soon frame is kept (texture already
        // latched above) and drawn by the keep-alive tick, so we cap the rate without losing motion.
        val idleMs = android.os.SystemClock.uptimeMillis() - lastDrawMs
        if (idleMs >= minFrameIntervalMs) drawFrame() else pendingFrame = true
    }

    /**
     * Re-emit the last decoded frame to the encoder if the Android Auto decoder has gone quiet, so the
     * bike keeps receiving video and never hits its media-socket timeout (CLIENT_INFO
     * socketTimeoutPeriodWifi, ~9s). AA legitimately pauses video during UI transitions (opening the
     * app launcher, an incoming call) and while the decoder restarts/recovers — without this the
     * encoder starves, the bike disconnects, and the whole projection drops (looks like a crash).
     * The dash instead shows a frozen last frame until live video resumes. Runs on the GL thread.
     */
    private val keepAlive = object : Runnable {
        override fun run() {
            if (hasContent && (windowSurface != EGL14.EGL_NO_SURFACE || previewSurface != EGL14.EGL_NO_SURFACE)) {
                val idleMs = android.os.SystemClock.uptimeMillis() - lastDrawMs
                if (pendingFrame && idleMs >= minFrameIntervalMs) {
                    // Flush the last coalesced live frame so motion doesn't stall at the cap.
                    drawFrame()
                } else if (idleMs >= IDLE_REDRAW_MS) {
                    // Static screen: a rare re-emit purely to hold the bike's media socket open.
                    drawFrame()
                }
            }
            handler.postDelayed(this, KEEPALIVE_TICK_MS)
        }
    }

    /**
     * Draw the current SurfaceTexture content (last decoded frame) into whichever targets exist: the
     * bike encoder ([windowSurface]) and/or the in-app phone preview ([previewSurface]). Each is
     * letterboxed into its own viewport.
     */
    private fun drawFrame() {
        if (windowSurface == EGL14.EGL_NO_SURFACE && previewSurface == EGL14.EGL_NO_SURFACE) return
        surfaceTexture.getTransformMatrix(texMatrix)
        // vpY is measured from the top (touch mapping); GL viewports are from the bottom. Same
        // result for symmetric margins, but a top-only inset used to land at the bottom.
        if (windowSurface != EGL14.EGL_NO_SURFACE) renderTo(windowSurface, vpX, canvasH - vpY - vpH, vpW, vpH)
        if (previewSurface != EGL14.EGL_NO_SURFACE) renderTo(previewSurface, ppX, ppY, ppW, ppH)
        lastDrawMs = android.os.SystemClock.uptimeMillis()
        pendingFrame = false
    }

    /** Render the latched frame into [target], letterboxed to viewport ([vx],[vy],[vw],[vh]). */
    private fun renderTo(target: EGLSurface, vx: Int, vy: Int, vw: Int, vh: Int) {
        EGL14.eglMakeCurrent(eglDisplay, target, target, eglContext)

        // Black background (the letterbox bars).
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // FILL may compute a viewport taller/wider than the canvas (center-crop). Scissor so we
        // never paint outside the bike encoder surface.
        val clipW = if (target === windowSurface) canvasW else previewW
        val clipH = if (target === windowSurface) canvasH else previewH
        if (clipW > 0 && clipH > 0) {
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            GLES20.glScissor(0, 0, clipW, clipH)
        }

        GLES20.glViewport(vx, vy, vw, vh)
        GLES20.glUseProgram(program)

        quad.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPosition)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTexCoord)

        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glUniform2f(uCrop, cropU, cropV)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        if (calibrating && target === windowSurface) drawCalibrationFrame()

        // Monotonic presentation time so repeated (keep-alive) frames aren't dropped as duplicate PTS.
        EGLExt.eglPresentationTimeANDROID(eglDisplay, target, System.nanoTime())
        EGL14.eglSwapBuffers(eglDisplay, target)
    }

    fun release() {
        handler.removeCallbacks(keepAlive)
        handler.post {
            try { inputSurface?.release() } catch (_: Exception) {}
            try { if (::surfaceTexture.isInitialized) surfaceTexture.release() } catch (_: Exception) {}
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (windowSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, windowSurface)
                if (previewSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, previewSurface)
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbuffer)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        thread.quitSafely()
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        val cfgAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfig = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, cfgAttrs, 0, configs, 0, 1, numConfig, 0)
        eglConfig = configs[0]
        val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttrs, 0)
        // 1x1 pbuffer so the context can be current before we have an output window surface.
        pbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
    }

    private fun initGl() {
        val vs = """
            uniform mat4 uTexMatrix;
            uniform vec2 uCrop;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                // AA splits margin_width/height evenly (DHU: marginwidth 280 → 140+140), so its UI is
                // CENTERED inside the coded frame. Sample the centered usable region on both axes; the
                // (1-crop)*0.5 offset is symmetric, so SurfaceTexture's y-flip needs no special case.
                float u = (1.0 - uCrop.x) * 0.5 + aTexCoord.x * uCrop.x;
                float v = (1.0 - uCrop.y) * 0.5 + aTexCoord.y * uCrop.y;
                vTexCoord = (uTexMatrix * vec4(u, v, aTexCoord.z, aTexCoord.w)).xy;
            }
        """.trimIndent()
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() { gl_FragColor = texture2D(sTexture, vTexCoord); }
        """.trimIndent()
        program = linkProgram(vs, fs)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uCrop = GLES20.glGetUniformLocation(program, "uCrop")
        Matrix.setIdentityM(texMatrix, 0)

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun linkProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val err = GLES20.glGetProgramInfoLog(p)
            throw RuntimeException("program link failed: $err")
        }
        return p
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val err = GLES20.glGetShaderInfoLog(s)
            throw RuntimeException("shader compile failed: $err")
        }
        return s
    }
}
