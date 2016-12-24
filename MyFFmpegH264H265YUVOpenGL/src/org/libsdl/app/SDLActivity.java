package org.libsdl.app;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import android.app.*;
import android.content.*;
import android.view.*;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsoluteLayout;
import android.os.*;
import android.util.Log;
import android.graphics.*;
import android.media.*;
import android.hardware.*;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;


/**
    SDL Activity
*/
public class SDLActivity extends Activity {
    private static final String TAG = "SDL";

    // Keep track of the paused state
    public static boolean mIsPaused = false, mIsSurfaceReady = false, mHasFocus = true;

    // Main components
    protected static SDLActivity mSingleton;
    static SDLSurface mSurface;
    protected static View mTextEdit;
    protected static ViewGroup mLayout;

    // This is what SDL runs in. It invokes SDL_main(), eventually
    protected static Thread mSDLThread;

    // Audio
    protected static Thread mAudioThread;
    protected static AudioTrack mAudioTrack;
    
    public  static BufferedOutputStream outputStream; 
    public  int width=640, height=480;
    public  static byte[] buf;
	Camera camera = null;
	MediaCodec mediaCodec;
	
	SDLActivity mySDLActivity;
	static boolean run_showing;
	
	static int y_size = 640*480*3/2;
    public static  byte[] h264 = new byte[y_size]; 
    
    // Load the .so
    static {
        System.loadLibrary("mp4v2");
		System.loadLibrary("faac");
		System.loadLibrary("mp3lame");
		System.loadLibrary("x264");
		System.loadLibrary("rtmp");
		System.loadLibrary("ffmpeg");
		System.loadLibrary("live555");
		System.loadLibrary("SDL2");
		System.loadLibrary("myx265");
    }

    // C functions we call
    public static native void nativeInit();
    public static native void nativeLowMemory();
    public static native void nativeQuit();
    public static native void nativePause();
    public static native void nativeResume();
    public static native void onNativeResize(int x, int y, int format);
    public static native void onNativeKeyDown(int keycode);
    public static native void onNativeKeyUp(int keycode);
    public static native void onNativeKeyboardFocusLost();
    public static native void onNativeTouch(int touchDevId, int pointerFingerId,
                                            int action, float x, 
                                            float y, float p);
    public static native void onNativeAccel(float x, float y, float z);
    public static native void onNativeSurfaceChanged();
    public static native void onNativeSurfaceDestroyed();
    public static native void nativeFlipBuffers();

    public static native int nativeTest0();
    public static native int nativeTest();
    public static native int nativeTestStart();
    public static native void nativeTestEnc(byte[] data, int size);
    public static native void nativeTestEnc0(byte[] data, int size);
    public static native void nativeTestEnd0();
    public static native void nativeTestEnd();
    
    public static native int nativeDecode(String filename);
    public static native void nativeDecodeEnd();
    
    // Setup
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        //Log.v("SDL", "onCreate()");
        super.onCreate(savedInstanceState);
        // So we can call stuff from static callbacks
        mSingleton = this;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
				WindowManager.LayoutParams.FLAG_FULLSCREEN);
        
        Display display = getWindowManager().getDefaultDisplay();
        Point size = new Point();
    	display.getSize(size);
    	
    	Log.i("SDLActivity", "                 onCreate             ");
    	
    	Log.i("SDLActivity", "size.x = "+size.x+", size.y = "+size.y);
    	Log.i("SDLActivity", "size.x = "+display.getWidth()+", size.y = "+display.getHeight());
    	  
        // Set up the surface
        mSurface = new SDLSurface(getApplication());

        mLayout = new AbsoluteLayout(this);
        mLayout.addView(mSurface);

        setContentView(mLayout);
        
        File f0 = new File(Environment.getExternalStorageDirectory(), "out0.yuv");//"mediacodec_r0.264");
	    if(!f0.exists()){	
	    	try {
				f0.createNewFile();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
	    }
	    Log.i("SDLActivity", "f0.getPath() = "+f0.getPath());
	    /*
        File f = new File(Environment.getExternalStorageDirectory(), "h264_20.yuv");//"mediacodec_r0.264");
	    if(!f.exists()){	
	    	try {
				f.createNewFile();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
	    }
	    try {
	    	Log.i("SDLActivity", "f.getPath() = "+f.getPath());
	        outputStream = new BufferedOutputStream(new FileOutputStream(f));
	        Log.i("SDLActivity", "outputStream initialized");
	    } catch (Exception e){ 
	        e.printStackTrace();
	    }
	    */
	    
	    mySDLActivity = this;
	    SDLActivity.run_showing = false;
	    new Thread() {
	        public void run() {
					try {
						Thread.sleep(1500);
						openCamera(mSurface.getHolder());
						//Mp4FpgDCE();
						//Mp4Mkv(mp4Path, mkvPath);
						//Message message = new Message();   
		                //message.what = 2;     
		                //myHandler.sendMessage(message);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
	        }
	    }.start();
	   
    }
    
    @Override
	public boolean onKeyDown(int keyCode, KeyEvent event)  {
	    if (keyCode == KeyEvent.KEYCODE_BACK) { 
	      //do something here
	      finish();   
          //System.exit(0); 
	      return true;
	    }
	    return super.onKeyDown(keyCode, event);
	} 
    
    int yuv_size = width*height*3/2;

	private Camera getCamera(int cameraType) {
	    Camera camera = null;
	    try {
	    	Log.i("Encoder", "--------------getCamera start--------------");
	        camera = Camera.open(Camera.getNumberOfCameras()-1);
	        Log.i("Encoder", "--------------getCamera end--------------");	        
	        //printSupportPreviewSize(camera.getParameters());
	    } catch (Exception e) {
	        e.printStackTrace();
	    }
	    return camera; // returns null if camera is unavailable
	}
	
	private void openCamera(SurfaceHolder holder) {
	    releaseCamera();
	    try {
	            camera = getCamera(Camera.CameraInfo.CAMERA_FACING_BACK); // 閿熸枻鎷烽敓鏂ゆ嫹閿熸枻鎷烽敓鏂ゆ嫹閫夐敓鏂ゆ嫹鍓�/閿熸枻鎷烽敓鏂ゆ嫹閿熸枻鎷烽敓鏂ゆ嫹澶�
	        } catch (Exception e) {
	            camera = null;
	            e.printStackTrace();
	        }
	    if(camera != null){

	        //camera.setPreviewDisplay(holder);
			camera.setDisplayOrientation(90); // 閿熷壙鍑ゆ嫹閿熸枻鎷蜂负閿熷姭鍑ゆ嫹閿熺粨渚涢敓鏂ゆ嫹閿熸枻鎷疯浆閿熸枻鎷风ず閿熸枻鎷烽敓琛楃殑鍑ゆ嫹閿熸枻鎷烽敓鏂ゆ嫹閿熸枻鎷烽敓鏂ゆ嫹閿熸枻鎷峰奖閿熸枻鎷穙nPreviewFrame閿熸枻鎷烽敓鏂ゆ嫹閿熷彨纰夋嫹鍘熷閿熸枻鎷烽敓鎹凤綇鎷�
			
			Camera.Parameters parameters = camera.getParameters();
			
			parameters.setPreviewSize(width, height); // 閿熸枻鎷烽敓鏂ゆ嫹閿熸枻鎷烽敓鏂ゆ嫹閿熺煫寰堣鎷烽敓鏂ゆ嫹閿熸枻鎷风墴閿熸枻鎷烽敓鏂ゆ嫹閿熸枻鎷烽敓鏂ゆ嫹澧欓敓鏂ゆ嫹閿熸枻鎷风己閿熸枻鎷烽敓鏂ゆ嫹閿熻甯嫹閿熸枻鎷烽敓瑙掑嚖鎷锋敮閿熻闈╂嫹閿熸枻鎷烽敓鐭綇鎷烽敓鏂ゆ嫹鐒堕敓鏂ゆ嫹閿熸澃浼氬閿熼摪绛规嫹閿熸枻鎷�
			//parameters.getSupportedPreviewSizes();
			parameters.setFlashMode("off"); // 閿熸枻鎷烽敓鏂ゆ嫹閿熸枻鎷烽敓锟�  
			parameters.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO); 
			parameters.setPreviewFormat(ImageFormat.YV12); // 閿熸枻鎷烽敓鐭潻鎷峰紡閿熸枻鎷種V21 / YV12
			parameters.setSceneMode(Camera.Parameters.SCENE_MODE_AUTO);  
			//parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO); 
			//parameters.set("orientation", "portrait");
			//parameters.set("orientation", "landscape");
			camera.setParameters(parameters);
         
			
            /*
			 camera.setPreviewDisplay(holder);  
			 Camera.Parameters parameters = camera.getParameters();  
			 parameters.setPreviewSize(width, height);  
			 // parameters.setPictureSize(width, height);  
			 parameters.setPreviewFormat(ImageFormat.YV12);  
			 camera.setParameters(parameters);   
			 camera.setPreviewCallback(this);  
			 camera.startPreview();  
         */
			
			buf = new byte[width*height*3/2];
			camera.addCallbackBuffer(buf);
			camera.setPreviewCallbackWithBuffer(mSurface);
			
			List<int[]> fpsRange = parameters.getSupportedPreviewFpsRange();
			for (int[] temp3 : fpsRange) {
			     System.out.println(Arrays.toString(temp3));
			}
			
			parameters.setPreviewFpsRange(15000, 15000);    
			//parameters.setPreviewFpsRange(4000,60000);//this one results fast playback when I use the FRONT CAMERA 
			
			camera.startPreview();
			
			Log.i("Encoder", "--------------openCamera--------------");
	    }
	}
	
	private synchronized void releaseCamera() {
	    if (camera != null) {
	        try {
	            camera.setPreviewCallback(null);
	            camera.stopPreview();
	            camera.release();
	            camera = null;
	            Log.i("Encoder", "--------------releaseCamera--------------");
	        } catch (Exception e) {
	            e.printStackTrace();
	        }
	    }
	}
	/**
     * Returns a color format that is supported by the codec and by this test code.  If no
     * match is found, this throws a test failure -- the set of formats known to the test
     * should be expanded for new platforms.
     */
    private static int selectColorFormat(MediaCodecInfo codecInfo, String mimeType) {
        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
        for (int i = 0; i < capabilities.colorFormats.length; i++) {
            int colorFormat = capabilities.colorFormats[i];
            if (isRecognizedFormat(colorFormat)) {
            	Log.i("Encoder", "selectColorFormat = "+colorFormat);
                return colorFormat;
            }
        }
        Log.e("Encoder","couldn't find a good color format for " + codecInfo.getName() + " / " + mimeType);
        return 0;   // not reached
    }

    /**
     * Returns true if this is a color format that this test code understands (i.e. we know how
     * to read and generate frames in this format).
     */
    private static boolean isRecognizedFormat(int colorFormat) {
        switch (colorFormat) {
            // these are the formats we know how to handle for this test
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar:
                return true;
            default:
                return false;
        }
    }
    
    /**
     * Returns the first codec capable of encoding the specified MIME type, or null if no
     * match was found.
     */
	private static MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);

            if (!codecInfo.isEncoder()) {
                continue;
            }

            String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(mimeType)) {
                	Log.i("Encoder", "selectCodec = "+codecInfo.getName());
                    return codecInfo;
                }
            }
        }
        return null;
    }
    
	public void MediaCodecEncodeInit(){
		String type = "video/avc";
		/*
		File f = new File(Environment.getExternalStorageDirectory(), "h264.yuv");//"mediacodec_r0.264");
	    if(!f.exists()){	
	    	try {
				f.createNewFile();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
	    }
	    try {
	    	Log.i("Encoder", "f.getPath() = "+f.getPath());
	        outputStream = new BufferedOutputStream(new FileOutputStream(f));
	        Log.i("Encoder", "outputStream initialized");
	    } catch (Exception e){ 
	        e.printStackTrace();
	    }
	    */
		
	    int colorFormat = selectColorFormat(selectCodec("video/avc"), "video/avc");
		try {
			mediaCodec = MediaCodec.createEncoderByType(type);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}  
		MediaFormat mediaFormat = MediaFormat.createVideoFormat(type, width, height);  
		mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 250000);//125kbps  
		mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 15);  
		mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
		//mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, 
		//		MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);  //COLOR_FormatYUV420Planar
		mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5); 
		mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);  
		mediaCodec.start();	
		Log.i("Encoder", "--------------MediaCodecEncodeInit--------------");
	}
	
	public void close() {
	    try {
	        mediaCodec.stop();
	        mediaCodec.release();
	        //outputStream.flush();
	        //outputStream.close();
	        Log.i("Encoder", "--------------close--------------");
	    } catch (Exception e){ 
	        e.printStackTrace();
	    }
	}
	
    // Events
    @Override
    protected void onPause() {
        Log.v("SDL", "onPause()");
        super.onPause();
        SDLActivity.handlePause();
    }

    @Override
    protected void onResume() {
        Log.v("SDL", "onResume()");
        super.onResume();
        SDLActivity.handleResume();
    }


    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        Log.v("SDL", "onWindowFocusChanged(): " + hasFocus);

        SDLActivity.mHasFocus = hasFocus;
        if (hasFocus) {
            SDLActivity.handleResume();
        }
    }

    @Override
    public void onLowMemory() {
        Log.v("SDL", "onLowMemory()");
        super.onLowMemory();
        SDLActivity.nativeLowMemory();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.v("SDL", "onDestroy()");
        // Send a quit message to the application
        
        SDLActivity.nativeDecodeEnd();
        //SDLActivity.nativeTestEnd0();
        SDLActivity.nativeQuit();
        
        releaseCamera();
        
        // Now wait for the SDL thread to quit
        if (mSDLThread != null) {
            try {
                mSDLThread.join();
            } catch(Exception e) {
                Log.v("SDL", "Problem stopping thread: " + e);
            }
            mSDLThread = null;

            //Log.v("SDL", "Finished waiting for SDL thread");
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        // Ignore certain special keys so they're handled by Android
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_CAMERA ||
            keyCode == 168 || /* API 11: KeyEvent.KEYCODE_ZOOM_IN */
            keyCode == 169 /* API 11: KeyEvent.KEYCODE_ZOOM_OUT */
            ) {
            return false;
        }
        return super.dispatchKeyEvent(event);
    }

    /** Called by onPause or surfaceDestroyed. Even if surfaceDestroyed
     *  is the first to be called, mIsSurfaceReady should still be set
     *  to 'true' during the call to onPause (in a usual scenario).
     */
    public static void handlePause() {
        if (!SDLActivity.mIsPaused && SDLActivity.mIsSurfaceReady) {
            SDLActivity.mIsPaused = true;
            SDLActivity.nativePause();
            mSurface.enableSensor(Sensor.TYPE_ACCELEROMETER, false);
        }
    }

    /** Called by onResume or surfaceCreated. An actual resume should be done only when the surface is ready.
     * Note: Some Android variants may send multiple surfaceChanged events, so we don't need to resume
     * every time we get one of those events, only if it comes after surfaceDestroyed
     */
    public static void handleResume() {
        if (SDLActivity.mIsPaused && SDLActivity.mIsSurfaceReady && SDLActivity.mHasFocus) {
            SDLActivity.mIsPaused = false;
            SDLActivity.nativeResume();
            mSurface.enableSensor(Sensor.TYPE_ACCELEROMETER, true);
        }
    }


    // Messages from the SDLMain thread
    static final int COMMAND_CHANGE_TITLE = 1;
    static final int COMMAND_UNUSED = 2;
    static final int COMMAND_TEXTEDIT_HIDE = 3;

    protected static final int COMMAND_USER = 0x8000;

    /**
     * This method is called by SDL if SDL did not handle a message itself.
     * This happens if a received message contains an unsupported command.
     * Method can be overwritten to handle Messages in a different class.
     * @param command the command of the message.
     * @param param the parameter of the message. May be null.
     * @return if the message was handled in overridden method.
     */
    protected boolean onUnhandledMessage(int command, Object param) {
        return false;
    }

    /**
     * A Handler class for Messages from native SDL applications.
     * It uses current Activities as target (e.g. for the title).
     * static to prevent implicit references to enclosing object.
     */
    protected static class SDLCommandHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            Context context = getContext();
            if (context == null) {
                Log.e(TAG, "error handling message, getContext() returned null");
                return;
            }
            switch (msg.arg1) {
            case COMMAND_CHANGE_TITLE:
                if (context instanceof Activity) {
                    ((Activity) context).setTitle((String)msg.obj);
                } else {
                    Log.e(TAG, "error handling message, getContext() returned no Activity");
                }
                break;
            case COMMAND_TEXTEDIT_HIDE:
                if (mTextEdit != null) {
                    mTextEdit.setVisibility(View.GONE);

                    InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(mTextEdit.getWindowToken(), 0);
                }
                break;

            default:
                if ((context instanceof SDLActivity) && !((SDLActivity) context).onUnhandledMessage(msg.arg1, msg.obj)) {
                    Log.e(TAG, "error handling message, command is " + msg.arg1);
                }
            }
        }
    }

    // Handler for the messages
    Handler commandHandler = new SDLCommandHandler();

    // Send a message from the SDLMain thread
    boolean sendCommand(int command, Object data) {
        Message msg = commandHandler.obtainMessage();
        msg.arg1 = command;
        msg.obj = data;
        return commandHandler.sendMessage(msg);
    }

    public static void flipBuffers() {
        SDLActivity.nativeFlipBuffers();
    }

    public static boolean setActivityTitle(String title) {
        // Called from SDLMain() thread and can't directly affect the view
        return mSingleton.sendCommand(COMMAND_CHANGE_TITLE, title);
    }

    public static boolean sendMessage(int command, int param) {
        return mSingleton.sendCommand(command, Integer.valueOf(param));
    }

    public static Context getContext() {
        return mSingleton;
    }

    static class ShowTextInputTask implements Runnable {
        /*
         * This is used to regulate the pan&scan method to have some offset from
         * the bottom edge of the input region and the top edge of an input
         * method (soft keyboard)
         */
        static final int HEIGHT_PADDING = 15;

        public int x, y, w, h;

        public ShowTextInputTask(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        @Override
        public void run() {
            AbsoluteLayout.LayoutParams params = new AbsoluteLayout.LayoutParams(
                    w, h + HEIGHT_PADDING, x, y);

            if (mTextEdit == null) {
                mTextEdit = new DummyEdit(getContext());

                mLayout.addView(mTextEdit, params);
            } else {
                mTextEdit.setLayoutParams(params);
            }

            mTextEdit.setVisibility(View.VISIBLE);
            mTextEdit.requestFocus();

            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(mTextEdit, 0);
        }
    }

    public static boolean showTextInput(int x, int y, int w, int h) {
        // Transfer the task to the main thread as a Runnable
        return mSingleton.commandHandler.post(new ShowTextInputTask(x, y, w, h));
    }
            
    public static Surface getNativeSurface() {
        return SDLActivity.mSurface.getNativeSurface();
    }

    // Audio
    public static int audioInit(int sampleRate, boolean is16Bit, boolean isStereo, int desiredFrames) {
        int channelConfig = isStereo ? AudioFormat.CHANNEL_CONFIGURATION_STEREO : AudioFormat.CHANNEL_CONFIGURATION_MONO;
        int audioFormat = is16Bit ? AudioFormat.ENCODING_PCM_16BIT : AudioFormat.ENCODING_PCM_8BIT;
        int frameSize = (isStereo ? 2 : 1) * (is16Bit ? 2 : 1);
        
        Log.v("SDL", "SDL audio: wanted " + (isStereo ? "stereo" : "mono") + " " + (is16Bit ? "16-bit" : "8-bit") + " " + (sampleRate / 1000f) + "kHz, " + desiredFrames + " frames buffer");
        
        // Let the user pick a larger buffer if they really want -- but ye
        // gods they probably shouldn't, the minimums are horrifyingly high
        // latency already
        desiredFrames = Math.max(desiredFrames, (AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat) + frameSize - 1) / frameSize);
        
        if (mAudioTrack == null) {
            mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
                    channelConfig, audioFormat, desiredFrames * frameSize, AudioTrack.MODE_STREAM);
            
            // Instantiating AudioTrack can "succeed" without an exception and the track may still be invalid
            // Ref: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/media/java/android/media/AudioTrack.java
            // Ref: http://developer.android.com/reference/android/media/AudioTrack.html#getState()
            
            if (mAudioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e("SDL", "Failed during initialization of Audio Track");
                mAudioTrack = null;
                return -1;
            }
            
            mAudioTrack.play();
        }
       
        Log.v("SDL", "SDL audio: got " + ((mAudioTrack.getChannelCount() >= 2) ? "stereo" : "mono") + " " + ((mAudioTrack.getAudioFormat() == AudioFormat.ENCODING_PCM_16BIT) ? "16-bit" : "8-bit") + " " + (mAudioTrack.getSampleRate() / 1000f) + "kHz, " + desiredFrames + " frames buffer");
        
        return 0;
    }
    
    public static void audioWriteShortBuffer(short[] buffer) {
        for (int i = 0; i < buffer.length; ) {
            int result = mAudioTrack.write(buffer, i, buffer.length - i);
            if (result > 0) {
                i += result;
            } else if (result == 0) {
                try {
                    Thread.sleep(1);
                } catch(InterruptedException e) {
                    // Nom nom
                }
            } else {
                Log.w("SDL", "SDL audio: error return from write(short)");
                return;
            }
        }
    }
    
    public static void audioWriteByteBuffer(byte[] buffer) {
        for (int i = 0; i < buffer.length; ) {
            int result = mAudioTrack.write(buffer, i, buffer.length - i);
            if (result > 0) {
                i += result;
            } else if (result == 0) {
                try {
                    Thread.sleep(1);
                } catch(InterruptedException e) {
                    // Nom nom
                }
            } else {
                Log.w("SDL", "SDL audio: error return from write(byte)");
                return;
            }
        }
    }

    public static void audioQuit() {
        if (mAudioTrack != null) {
            mAudioTrack.stop();
            mAudioTrack = null;
        }
    }

    // Input

    /**
     * @return an array which may be empty but is never null.
     */
    public static int[] inputGetInputDeviceIds(int sources) {
        int[] ids = InputDevice.getDeviceIds();
        int[] filtered = new int[ids.length];
        int used = 0;
        for (int i = 0; i < ids.length; ++i) {
            InputDevice device = InputDevice.getDevice(ids[i]);
            if ((device != null) && ((device.getSources() & sources) != 0)) {
                filtered[used++] = device.getId();
            }
        }
        return Arrays.copyOf(filtered, used);
    }
}

/**
    Simple nativeInit() runnable
*/
class SDLMain implements Runnable {
    @Override
    public void run() {
        // Runs SDL_main()
        SDLActivity.nativeInit();
        
        //Log.v("SDL", "SDL thread terminated");
    }
}


/**
    SDLSurface. This is what we draw on, so we need to know when it's created
    in order to do anything useful. 

    Because of this, that's where we set up the SDL thread
*/
class SDLSurface extends SurfaceView implements SurfaceHolder.Callback, 
    View.OnTouchListener, SensorEventListener ,PreviewCallback { // View.OnKeyListener,

    // Sensors
    protected static SensorManager mSensorManager;
    protected static Display mDisplay;

    // Keep track of the surface size to normalize touch events
    protected static float mWidth, mHeight;

    // Startup    
    public SDLSurface(Context context) {
        super(context);
        getHolder().addCallback(this); 
    
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        //setOnKeyListener(this); 
        setOnTouchListener(this);   

        mDisplay = ((WindowManager)context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        mSensorManager = (SensorManager)context.getSystemService(Context.SENSOR_SERVICE);

        // Some arbitrary defaults to avoid a potential division by zero
        mWidth = 1.0f;
        mHeight = 1.0f;
    }
    
    public Surface getNativeSurface() {
        return getHolder().getSurface();
    }

    // Called when we have a valid drawing surface
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.v("SDL", "surfaceCreated()");
        //holder.setType(SurfaceHolder.SURFACE_TYPE_GPU);
    }

    // Called when we lose the surface
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.v("SDL", "surfaceDestroyed()");
        // Call this *before* setting mIsSurfaceReady to 'false'
        SDLActivity.handlePause();
        SDLActivity.mIsSurfaceReady = false;
        SDLActivity.onNativeSurfaceDestroyed();
    }

    // Called when the surface is resized
    @Override
    public void surfaceChanged(SurfaceHolder holder,int format, int width, int height) {
        Log.v("SDL", "surfaceChanged()");

        int sdlFormat = 0x15151002; // SDL_PIXELFORMAT_RGB565 by default
        switch (format) {
        case PixelFormat.A_8:
            Log.v("SDL", "pixel format A_8");
            break;
        case PixelFormat.LA_88:
            Log.v("SDL", "pixel format LA_88");
            break;
        case PixelFormat.L_8:
            Log.v("SDL", "pixel format L_8");
            break;
        case PixelFormat.RGBA_4444:
            Log.v("SDL", "pixel format RGBA_4444");
            sdlFormat = 0x15421002; // SDL_PIXELFORMAT_RGBA4444
            break;
        case PixelFormat.RGBA_5551:
            Log.v("SDL", "pixel format RGBA_5551");
            sdlFormat = 0x15441002; // SDL_PIXELFORMAT_RGBA5551
            break;
        case PixelFormat.RGBA_8888:
            Log.v("SDL", "pixel format RGBA_8888");
            sdlFormat = 0x16462004; // SDL_PIXELFORMAT_RGBA8888
            break;
        case PixelFormat.RGBX_8888:
            Log.v("SDL", "pixel format RGBX_8888");
            sdlFormat = 0x16261804; // SDL_PIXELFORMAT_RGBX8888
            break;
        case PixelFormat.RGB_332:
            Log.v("SDL", "pixel format RGB_332");
            sdlFormat = 0x14110801; // SDL_PIXELFORMAT_RGB332
            break;
        case PixelFormat.RGB_565:
            Log.v("SDL", "pixel format RGB_565");
            sdlFormat = 0x15151002; // SDL_PIXELFORMAT_RGB565
            break;
        case PixelFormat.RGB_888:
            Log.v("SDL", "pixel format RGB_888");
            // Not sure this is right, maybe SDL_PIXELFORMAT_RGB24 instead?
            sdlFormat = 0x16161804; // SDL_PIXELFORMAT_RGB888
            break;
        default:
            Log.v("SDL", "pixel format unknown " + format);
            break;
        }

        mWidth = width;
        mHeight = height;
        SDLActivity.onNativeResize(width, height, 0x15151002);//sdlFormat
        Log.v("SDL", "Window size:" + width + "x"+height);

        // Set mIsSurfaceReady to 'true' *before* making a call to handleResume
        SDLActivity.mIsSurfaceReady = true;
        SDLActivity.onNativeSurfaceChanged();


        if (SDLActivity.mSDLThread == null) {
            // This is the entry point to the C app.
            // Start up the C app thread and enable sensor input for the first time

            SDLActivity.mSDLThread = new Thread(new SDLMain(), "SDLThread");
            //enableSensor(Sensor.TYPE_ACCELEROMETER, true);
            SDLActivity.mSDLThread.start();
            
            /*
            new Thread() {
		        public void run() {
		            	try {
							Thread.sleep(3000);
							SDLActivity.nativeTest0();
							//SDLActivity.nativeTestStart();
							//SDLActivity.run_showing = true;
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
		        }
		    }.start();
		    */
        }
    }

    // unused
    @Override
    public void onDraw(Canvas canvas) {
    	
    }


    /*
    // Key events
    @Override
    public boolean onKey(View  v, int keyCode, KeyEvent event) {
        
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            //Log.v("SDL", "key down: " + keyCode);
            SDLActivity.onNativeKeyDown(keyCode);
            return true;
        }
        else if (event.getAction() == KeyEvent.ACTION_UP) {
            //Log.v("SDL", "key up: " + keyCode);
            SDLActivity.onNativeKeyUp(keyCode);
            return true;
        }
        
        return false;
    }
    */
    
    // Touch events
    @Override
    public boolean onTouch(View v, MotionEvent event) {
             final int touchDevId = event.getDeviceId();
             final int pointerCount = event.getPointerCount();
             // touchId, pointerId, action, x, y, pressure
             int actionPointerIndex = (event.getAction() & MotionEvent.ACTION_POINTER_ID_MASK) >> MotionEvent.ACTION_POINTER_ID_SHIFT; /* API 8: event.getActionIndex(); */
             int pointerFingerId = event.getPointerId(actionPointerIndex);
             int action = (event.getAction() & MotionEvent.ACTION_MASK); /* API 8: event.getActionMasked(); */

             float x = event.getX(actionPointerIndex) / mWidth;
             float y = event.getY(actionPointerIndex) / mHeight;
             float p = event.getPressure(actionPointerIndex);

             if (action == MotionEvent.ACTION_MOVE && pointerCount > 1) {
                // TODO send motion to every pointer if its position has
                // changed since prev event.
                for (int i = 0; i < pointerCount; i++) {
                    pointerFingerId = event.getPointerId(i);
                    x = event.getX(i) / mWidth;
                    y = event.getY(i) / mHeight;
                    p = event.getPressure(i);
                    SDLActivity.onNativeTouch(touchDevId, pointerFingerId, action, x, y, p);
                }
             } else {
                SDLActivity.onNativeTouch(touchDevId, pointerFingerId, action, x, y, p);
             }
      return true;
   } 

    // Sensor events
    public void enableSensor(int sensortype, boolean enabled) {
        // TODO: This uses getDefaultSensor - what if we have >1 accels?
        if (enabled) {
            mSensorManager.registerListener(this, 
                            mSensorManager.getDefaultSensor(sensortype), 
                            SensorManager.SENSOR_DELAY_GAME, null);
        } else {
            mSensorManager.unregisterListener(this, 
                            mSensorManager.getDefaultSensor(sensortype));
        }
    }
    
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // TODO
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x, y;
            switch (mDisplay.getRotation()) {
                case Surface.ROTATION_90:
                    x = -event.values[1];
                    y = event.values[0];
                    break;
                case Surface.ROTATION_270:
                    x = event.values[1];
                    y = -event.values[0];
                    break;
                case Surface.ROTATION_180:
                    x = -event.values[1];
                    y = -event.values[0];
                    break;
                default:
                    x = event.values[0];
                    y = event.values[1];
                    break;
            }
            SDLActivity.onNativeAccel(-x / SensorManager.GRAVITY_EARTH,
                                      y / SensorManager.GRAVITY_EARTH,
                                      event.values[2] / SensorManager.GRAVITY_EARTH - 1);
        }
    }
 
    //yv12 杞� yuv420p  yvu -> yuv  
    public void swapYV12toI420(byte[] yv12bytes, byte[] i420bytes, int width, int height)   
    {        
        System.arraycopy(yv12bytes, 0, i420bytes, 0,width*height);  
        System.arraycopy(yv12bytes, width*height+width*height/4, i420bytes, width*height,width*height/4);  
        System.arraycopy(yv12bytes, width*height, i420bytes, width*height+width*height/4,width*height/4);    
    } 
    
    int run_time;
    
	@Override
	public void onPreviewFrame(byte[] data, Camera camera) {
		 // TODO Auto-generated method stub
		 swapYV12toI420(data, SDLActivity.h264, 640, 480);
		 //X265Enc(h264,0);
		 
		 /*
		 try {
			 SDLActivity.outputStream.write(SDLActivity.h264, 0, SDLActivity.h264.length); 
		 } catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		 }
		 */
		 //SDLActivity.nativeTestEnc0(SDLActivity.h264, SDLActivity.h264.length);
		 
		 if(!SDLActivity.run_showing){
			 //run_time++;
			 //if(run_time>=5){
		     //run_time = 0;
			 Log.i("Encoder", "      onPreviewFrame nativeDecode     ");
			 String h265Path = Environment.getExternalStorageDirectory() + "/H265_01f.h265";
			 SDLActivity.nativeDecode(h265Path);
			 SDLActivity.run_showing = true;
		     new Thread() {
		        public void run() {
		            	//Thread.sleep(200);
						//SDLActivity.nativeTest0();
		        	
						//SDLActivity.nativeTestStart();
						//SDLActivity.run_showing = true;
		        }
		     }.start();
			 //}
		 }
		 
		 //if(SDLActivity.run_showing){
		    //SDLActivity.nativeTestEnc(SDLActivity.h264, SDLActivity.h264.length);
		 //}
		 //onFrame(data, 0, data.length, 0); 
		 camera.addCallbackBuffer(SDLActivity.buf);	
		 //Log.i("Encoder", "--------------------onPreviewFrame-----------------"+SDLActivity.h264);
	}
    
}

/* This is a fake invisible editor view that receives the input and defines the
 * pan&scan region
 */
class DummyEdit extends View implements View.OnKeyListener {
    InputConnection ic;

    public DummyEdit(Context context) {
        super(context);
        setFocusableInTouchMode(true);
        setFocusable(true);
        setOnKeyListener(this);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {

        // This handles the hardware keyboard input
        if (event.isPrintingKey()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                ic.commitText(String.valueOf((char) event.getUnicodeChar()), 1);
            }
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            SDLActivity.onNativeKeyDown(keyCode);
            return true;
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            SDLActivity.onNativeKeyUp(keyCode);
            return true;
        }

        return false;
    }
        
    //
    @Override
    public boolean onKeyPreIme (int keyCode, KeyEvent event) {
        // As seen on StackOverflow: http://stackoverflow.com/questions/7634346/keyboard-hide-event
        // FIXME: Discussion at http://bugzilla.libsdl.org/show_bug.cgi?id=1639
        // FIXME: This is not a 100% effective solution to the problem of detecting if the keyboard is showing or not
        // FIXME: A more effective solution would be to change our Layout from AbsoluteLayout to Relative or Linear
        // FIXME: And determine the keyboard presence doing this: http://stackoverflow.com/questions/2150078/how-to-check-visibility-of-software-keyboard-in-android
        // FIXME: An even more effective way would be if Android provided this out of the box, but where would the fun be in that :)
        if (event.getAction()==KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_BACK) {
            if (SDLActivity.mTextEdit != null && SDLActivity.mTextEdit.getVisibility() == View.VISIBLE) {
                SDLActivity.onNativeKeyboardFocusLost();
            }
        }
        return super.onKeyPreIme(keyCode, event);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        ic = new SDLInputConnection(this, true);

        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | 33554432 /* API 11: EditorInfo.IME_FLAG_NO_FULLSCREEN */;

        return ic;
    }
}

class SDLInputConnection extends BaseInputConnection {

    public SDLInputConnection(View targetView, boolean fullEditor) {
        super(targetView, fullEditor);

    }

    @Override
    public boolean sendKeyEvent(KeyEvent event) {

        /*
         * This handles the keycodes from soft keyboard (and IME-translated
         * input from hardkeyboard)
         */
        int keyCode = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.isPrintingKey()) {
                commitText(String.valueOf((char) event.getUnicodeChar()), 1);
            }
            SDLActivity.onNativeKeyDown(keyCode);
            return true;
        } else if (event.getAction() == KeyEvent.ACTION_UP) {

            SDLActivity.onNativeKeyUp(keyCode);
            return true;
        }
        return super.sendKeyEvent(event);
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {

        nativeCommitText(text.toString(), newCursorPosition);

        return super.commitText(text, newCursorPosition);
    }

    @Override
    public boolean setComposingText(CharSequence text, int newCursorPosition) {

        nativeSetComposingText(text.toString(), newCursorPosition);

        return super.setComposingText(text, newCursorPosition);
    }

    public native void nativeCommitText(String text, int newCursorPosition);

    public native void nativeSetComposingText(String text, int newCursorPosition);
}

