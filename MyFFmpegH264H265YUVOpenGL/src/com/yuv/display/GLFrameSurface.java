package com.yuv.display;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;

public class GLFrameSurface extends GLSurfaceView {

    public GLFrameSurface(Context context) {
        super(context);
    }

    public GLFrameSurface(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onAttachedToWindow() {
        Utils.LOGD("surface onAttachedToWindow()");
        super.onAttachedToWindow();
        // setRenderMode() only takes effectd after SurfaceView attached to window!
        // note that on this mode, surface will not render util GLSurfaceView.requestRender() is
        // called, it's good and efficient -v-
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        Utils.LOGD("surface setRenderMode RENDERMODE_WHEN_DIRTY");
    }
}
