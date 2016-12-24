package com.yuv.display;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import net.obviam.opengl.R;
import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.Camera.AutoFocusCallback;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Camera.Size;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

public class YUV extends Activity implements SurfaceHolder.Callback, PreviewCallback{
	
	/** The OpenGL view */
	private GLSurfaceView glSurfaceView;
	
	private GLFrameRenderer glRenderer;
	
	private Handler mHandler = new Handler();
	
	private int width=320, height=240;
	private byte[] buf;
	private Surface surface;
	private SurfaceView surfaceView;
	Camera camera = null;
	MediaCodec mediaCodec, mediaCodecd;
	private BufferedOutputStream outputStream;
	
	boolean isPlaying;
	//vlc:  udp://@:5000
	private UdpSendTask netSendTask;
	
	FrameLayout preview0;
	FrameLayout preview1;
	 
	YUV myYUV;
	
	static {
		System.loadLibrary("mp4v2");
		System.loadLibrary("faac");
		System.loadLibrary("x264");
		System.loadLibrary("yuv264");
	}
	
	public native boolean Mp4Start(String pcm);
	public native void Mp4PackV(byte[] array, int length, int keyframe);
	public native void Mp4PackA(byte[] array, int length);
	public native void Mp4End();
	
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // requesting to turn the title OFF
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        // making it full screen
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
				WindowManager.LayoutParams.FLAG_FULLSCREEN);

		if (GLES20Support.detectOpenGLES20(this) == false) {
			GLES20Support.getNoSupportGLES20Dialog(this);
		}
	
        // 
        setContentView(R.layout.activity_streamer);//setContentView(glSurfaceView);
        
        // Initiate the Open GL view and
        // create an instance with this activity
        glSurfaceView = new GLFrameSurface(this);
        glSurfaceView.setEGLContextClientVersion(2);  
        // 
        glRenderer = new GLFrameRenderer(null, glSurfaceView, getDM(this));
        // set our renderer to be the main renderer with
        // the current activity context
        glSurfaceView.setRenderer(glRenderer);
        
        preview0 = (FrameLayout) findViewById(R.id.camera_preview0);
        preview0.addView(glSurfaceView);
        
        //preview1 = (FrameLayout) findViewById(R.id.camera_preview1);
        
        surfaceView = (SurfaceView) findViewById(R.id.mySurfaceView);
        SurfaceHolder mSurfaceHolder = surfaceView.getHolder();
		//mSurfaceHolder.setFormat(PixelFormat.TRANSPARENT);//translucent半透明 transparent透明  
        mSurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
		mSurfaceHolder.addCallback(this);
		surface = surfaceView.getHolder().getSurface();
		
		//setContentView(surfaceView); 
		
        isPlaying = false;
       
        Button playButton = (Button) findViewById(R.id.btnPlay);
        playButton.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                    	isPlaying = !isPlaying;
                    	Log.i("Encoder", "isPlaying = "+isPlaying);
                    }
                }
         );

        Button showButton = (Button) findViewById(R.id.btnShow);
        showButton.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                    	Log.i("Encoder", "--------------showButton--------------");
                    	/*
                    	Message message = new Message();   
                        message.what = 0;     
                        myHandler.sendMessage(message); 
                        */
                    }
                }
        );
        
        mHandler.postDelayed(new Runnable() {
			
			@Override
			public void run() {
				// TODO Auto-generated method stub
				glRenderer.update(width, height); // 640, 480
				
				byte[] yuv = getFromRaw();
				
				copyFrom(yuv, width, height); // 640, 480
				
	            byte[] y = new byte[yuvPlanes[0].remaining()];
	            yuvPlanes[0].get(y, 0, y.length);
	            
	            byte[] u = new byte[yuvPlanes[1].remaining()];
	            yuvPlanes[1].get(u, 0, u.length);
	            
	            byte[] v = new byte[yuvPlanes[2].remaining()];
	            yuvPlanes[2].get(v, 0, v.length);

	            
	            glRenderer.update(y, u, v);
			}
		}, 1000);
        myYUV = this;
    }
    
	Handler myHandler = new Handler() {  
        public void handleMessage(Message msg) {   
             switch (msg.what) {   
                  case 0:   
                	  Log.i("Encoder", "--------------myHandler--------------");
                	  //surfaceView = new SurfaceView(YUV.this);
              		  SurfaceHolder mSurfaceHolder = surfaceView.getHolder();
              		  //mSurfaceHolder.setFormat(PixelFormat.TRANSPARENT);//translucent半透明 transparent透明  
              		  mSurfaceHolder.addCallback(myYUV);
              		  surface = surfaceView.getHolder().getSurface();
                      
                      //preview1.addView(surfaceView);
                      break;   
                  case 1:
                	  byte[] y = new byte[yuvPlanes[0].remaining()];
      	              yuvPlanes[0].get(y, 0, y.length);
      	            
      	              byte[] u = new byte[yuvPlanes[1].remaining()];
      	              yuvPlanes[1].get(u, 0, u.length);
      	            
      	              byte[] v = new byte[yuvPlanes[2].remaining()];
      	              yuvPlanes[2].get(v, 0, v.length);

      	            
      	              glRenderer.update(y, u, v);
                	  break;
             }   
             super.handleMessage(msg);   
        }   
    }; 
    
    public boolean isSupportMediaCodecHardDecoder(){
        boolean isHardcode = false;
        //读取系统配置文件/system/etc/media_codecc.xml
        File file = new File("/system/etc/media_codecs.xml");
        InputStream inFile = null;
        try {
          inFile = new FileInputStream(file);
        } catch (Exception e) {
            // TODO: handle exception
        }

        if(inFile != null) { 
            XmlPullParserFactory pullFactory;
            try {
                pullFactory = XmlPullParserFactory.newInstance();
                XmlPullParser xmlPullParser = pullFactory.newPullParser();
                xmlPullParser.setInput(inFile, "UTF-8");
                int eventType = xmlPullParser.getEventType();
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    String tagName = xmlPullParser.getName();
                    switch (eventType) {
                    case XmlPullParser.START_TAG:
                        if ("MediaCodec".equals(tagName)) {
                            String componentName = xmlPullParser.getAttributeValue(0);
                            
                            Log.i("MediaCodec", "MediaCodec = "+componentName);
                            
                            if(componentName.startsWith("OMX."))
                            {
                                if(!componentName.startsWith("OMX.google."))
                                {
                                    isHardcode = true;
                                }
                            }
                        }
                    }
                    eventType = xmlPullParser.next();
                }
            } catch (Exception e) {
                // TODO: handle exception
            }
        }
        return isHardcode;
    }
    
    private static MediaCodecInfo selectCodecs() {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);

            if (!codecInfo.isEncoder()) {
                continue;
            }

            String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                Log.i("Encoder", "codec types = "+codecInfo.getName());
            }
        }
        return null;
    }
    
    public  void printSupportPreviewSize(Camera.Parameters params){  
        List<Size> previewSizes = params.getSupportedPreviewSizes();  
        for(int i=0; i< previewSizes.size(); i++){  
            Size size = previewSizes.get(i);  
            Log.i("Encoder", "previewSizes:width = "+size.width+" height = "+size.height);  
        }  
      
    }  
	
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
	
	@SuppressWarnings("deprecation")
	private void openCamera(SurfaceHolder holder) { 
	    releaseCamera();
	    try {
	            camera = getCamera(Camera.CameraInfo.CAMERA_FACING_BACK); // 根据需求选择前/后置摄像头
	        } catch (Exception e) {
	            camera = null;
	            e.printStackTrace();
	        }
	    if(camera != null){
	    	
	        try {
				camera.setPreviewDisplay(holder);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			camera.setDisplayOrientation(90); // 此方法为官方提供的旋转显示部分的方法，并不会影响onPreviewFrame方法中的原始数据；
			
			Camera.Parameters parameters = camera.getParameters();
			
			parameters.setPreviewSize(width, height); // 还可以设置很多相机的参数，但是建议先遍历当前相机是否支持该配置，不然可能会导致出错
			//parameters.getSupportedPreviewSizes();
			parameters.setFlashMode("off"); // 无闪光灯  
			parameters.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO); 
			parameters.setPreviewFormat(ImageFormat.YV12); // 常用格式：NV21 / YV12
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
			camera.setPreviewCallbackWithBuffer(this);
			
			List<int[]> fpsRange = parameters.getSupportedPreviewFpsRange();
			for (int[] temp3 : fpsRange) {
			     System.out.println(Arrays.toString(temp3));
			}
			//parameters.setPreviewFpsRange(4000,60000);
			//parameters.setPreviewFpsRange(10000, 30000);    
			//parameters.setPreviewFpsRange(4000,60000);//this one results fast playback when I use the FRONT CAMERA 
			
			Log.i("Encoder", "--------------openCamera--------------");
			
			camera.startPreview();
			
			Log.i("Encoder", "--------------openCamera end--------------");
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
	
	public void showCodecCapabilities(){
    	String mimeType = "video/avc";
    	
    	// Find a code that supports the mime type
	    int numCodecs = MediaCodecList.getCodecCount();
	    MediaCodecInfo codecInfo = null;
	    for (int i = 0; i < numCodecs && codecInfo == null; i++) {
	        MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
	        if (!info.isEncoder()) {
	            continue;
	        }
	        String[] types = info.getSupportedTypes();
	        boolean found = false;
	        for (int j = 0; j < types.length && !found; j++) {
	            if (types[j].equals(mimeType))
	                found = true;
	        }
	        if (!found)
	            continue;
	        codecInfo = info;
	    }
	    Log.i("Encoder", "Found " + codecInfo.getName() + " supporting " + mimeType);

	    // Find a color profile that the codec supports
	    int colorFormat = 0;
	    MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
	    Log.i("Encoder", "capabilities.colorFormats.length " + capabilities.colorFormats.length);
	    for (int i = 0; i < capabilities.colorFormats.length && colorFormat == 0; i++) {
	        int format = capabilities.colorFormats[i];
	        Log.i("Encoder", "color format " + format);
	        switch (format) {
	        case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
	        case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
	        case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
	        case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
	        case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar:
	            colorFormat = format;
	            break;
	        default:
	            Log.i("Encoder", "Skipping unsupported color format " + format);
	            break;
	        }
	    }
	    Log.i("Encoder", "Using color format " + colorFormat);
    	
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
		
		File f = new File(Environment.getExternalStorageDirectory(), "mediacod.264");
	    if(!f.exists()){	
	    	try {
				f.createNewFile();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
	    }
	    //else{
	    //	f.delete();
	    //}
	    
	    try {
	        outputStream = new BufferedOutputStream(new FileOutputStream(f));
	        Log.i("Encoder", "outputStream initialized");
	    } catch (Exception e){ 
	        e.printStackTrace();
	    }
	    
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
		//mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, 
		//		MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);  //COLOR_FormatYUV420Planar
		
		mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
		
		mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5); //关键帧间隔时间 单位s  
		mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);  
		mediaCodec.start();	
		Log.i("Encoder", "--------------MediaCodecEncodeInit--------------");
	}
	
	
	public void MediaCodecDecodeInit(){
		String type = "video/avc";
		try {
			mediaCodecd = MediaCodec.createDecoderByType(type);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}  
		MediaFormat mediaFormat = MediaFormat.createVideoFormat(type, width, height);  
		mediaCodecd.configure(mediaFormat, surface, null, 0);  
		mediaCodecd.start(); 
	}	
	
	
	public void close() {
	    try {
	        mediaCodec.stop();
	        mediaCodec.release();
	        
	        mediaCodecd.stop();
	        mediaCodecd.release();
	        //outputStream.flush();
	        //outputStream.close();
	        Log.i("Encoder", "--------------close--------------");
	    } catch (Exception e){ 
	        e.printStackTrace();
	    }
	}
	
	public static byte[] YV12toYUV420Planar(byte[] input, byte[] output, int width, int height) {
        /* 
         * COLOR_FormatYUV420Planar is I420 which is like YV12, but with U and V reversed.
         * So we just have to reverse U and V.
         */
        final int frameSize = width * height;
        final int qFrameSize = frameSize/4;

        System.arraycopy(input, 0, output, 0, frameSize); // Y
        System.arraycopy(input, frameSize, output, frameSize + qFrameSize, qFrameSize); // Cr (V)
        System.arraycopy(input, frameSize + qFrameSize, output, frameSize, qFrameSize); // Cb (U)

        return output;
    }
	
	private byte[] m_info = null; 
	private byte[] yuv420 = new byte[width*height*3/2];
	private byte[] h264 = new byte[width*height*3/2]; 
	
	 /*
    YUV420P，Y，U，V三个分量都是平面格式，分为I420和YV12。I420格式和YV12格式的不同处在U平面和V平面的位置不同。
          在I420格式中，U平面紧跟在Y平面之后，然后才是V平面（即：YUV）；但YV12则是相反（即：YVU）。
    YUV420SP, Y分量平面格式，UV打包格式, 即NV12。 NV12与NV21类似，U 和 V 交错排列,不同在于UV顺序。
    I420: YYYYYYYY UU VV    =>YUV420P
    YV12: YYYYYYYY VV UU    =>YUV420P
    NV12: YYYYYYYY UVUV     =>YUV420SP
    NV21: YYYYYYYY VUVU     =>YUV420SP
    */
	
	 //yv12 转 yuv420p  yvu -> yuv  
    private void swapYV12toI420(byte[] yv12bytes, byte[] i420bytes, int width, int height)   
    {        
        System.arraycopy(yv12bytes, 0, i420bytes, 0,width*height);  
        System.arraycopy(yv12bytes, width*height+width*height/4, i420bytes, width*height,width*height/4);  
        System.arraycopy(yv12bytes, width*height, i420bytes, width*height+width*height/4,width*height/4);    
    } 
    
    @Override
	public boolean onKeyDown(int keyCode, KeyEvent event)  {
	    if (keyCode == KeyEvent.KEYCODE_BACK) { //按下的如果是BACK，同时没有重复
	      //do something here
	      finish();   
          //System.exit(0); //凡是非零都表示异常退出!0表示正常退出! 
	      return true;
	    }
	    return super.onKeyDown(keyCode, event);
	}
    
    public int offerEncoder(byte[] input, byte[] output)   
    {     
        int pos = 0;  
        swapYV12toI420(input, output, width, height);  
        //YV12toYUV420PackedSemiPlanar_00(input, output, width, height);
        
        try {  
            ByteBuffer[] inputBuffers = mediaCodec.getInputBuffers();  
            ByteBuffer[] outputBuffers = mediaCodec.getOutputBuffers();  
            int inputBufferIndex = mediaCodec.dequeueInputBuffer(-1);  
            if (inputBufferIndex >= 0)   
            {  
                ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];  
                inputBuffer.clear();  
                inputBuffer.put(output);  //yuv420
                mediaCodec.queueInputBuffer(inputBufferIndex, 0, output.length, 0, 0); // yuv420.length 
            }  
  
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();  
            int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo,0);  
             
            while (outputBufferIndex >= 0)   
            {  
                ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];  
                byte[] outData = new byte[bufferInfo.size];  
                outputBuffer.get(outData);  
                  
                if(m_info != null)  
                {                 
                    System.arraycopy(outData, 0,  output, pos, outData.length);  
                    pos += outData.length;  
                      
                }  
                  
                else //保存pps sps 只有开始时 第一个帧里有， 保存起来后面用  
                {  
                     ByteBuffer spsPpsBuffer = ByteBuffer.wrap(outData);    
                     if (spsPpsBuffer.getInt() == 0x00000001)   
                     {   
                    	 Log.i("Encoder", "--------- pps sps found = "+outData.length+"---------");
                         m_info = new byte[outData.length];  
                         System.arraycopy(outData, 0, m_info, 0, outData.length);  
                     }   
                     else   
                     {    
                    	 Log.i("Encoder", "--------- no pps sps detect---------");
                         return -1;  
                     }        
                }  
                  
                mediaCodec.releaseOutputBuffer(outputBufferIndex, false);  
                outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0);  
            }  
  
            if(output[4] == 0x65) //key frame   编码器生成关键帧时只有 00 00 00 01 65 没有pps sps， 要加上  
            {  
            	Log.i("Encoder", "-----------idr frame: "+output[4]+"-----------");
                System.arraycopy(output, 0,  yuv420, 0, pos);  
                System.arraycopy(m_info, 0,  output, 0, m_info.length);  
                System.arraycopy(yuv420, 0,  output, m_info.length, pos);  
                pos += m_info.length;  
            }  
              
        } catch (Throwable t) {  
            t.printStackTrace();  
        }  
  
        return pos;  
    }
    
    int  onFraming;
    // encode
 	public void onFrame(byte[] buf, int offset, int length, int flag) {	
 		    //swapYV12toI420(input, output, width, height);  
 		    swapYV12toI420(buf, h264, width, height);
 		    
 		    if(isPlaying){	
 		    	if(onFraming>50){
 	 		       onFraming = 0;
 	 		       Log.i("Encoder", "--------------onFrame go--------------");
 	 		    }
 		    	copyFrom(h264,width,height);
 		    	Message message = new Message();   
                message.what = 1;     
                myHandler.sendMessage(message);
 		    }else{
 		    	onFraming++;
 	 		    if(onFraming>50){
 	 		       onFraming = 0;
 	 		       Log.i("Encoder", "--------------onFrame--------------");
 	 		    }
 		    }
 		 
 		    ByteBuffer[] inputBuffers = mediaCodec.getInputBuffers();
 		    ByteBuffer[] outputBuffers = mediaCodec.getOutputBuffers();
 		    int inputBufferIndex = mediaCodec.dequeueInputBuffer(-1);
 		    if (inputBufferIndex >= 0) {
 		        ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
 		        inputBuffer.clear();
 		        //inputBuffer.put(buf, offset, length);
 		        inputBuffer.put(h264, offset, length);
 		        mediaCodec.queueInputBuffer(inputBufferIndex, 0, length, 0, 0);
 		    }
 		    MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
 		    int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo,0);
 		    while (outputBufferIndex >= 0) {
 		        ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];

 	            byte[] outData = new byte[bufferInfo.size];
 	            outputBuffer.get(outData);
 	            
 	            /*
 	            try {
 					outputStream.write(outData, 0, outData.length);
 				} catch (IOException e) {
 					// TODO Auto-generated catch block
 					e.printStackTrace();
 				} // write into h264 file
 	            //Log.i("Encoder", outData.length + " bytes written");
                 */
 		        
 	            onFrame0(outData, 0, outData.length, flag);
 	            
 		        //onFrame0(outputBuffer.array(), 0, bufferInfo.size, flag);
 		        
 		        mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
 		        outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0);
 		    }
 	 }  
 	 
 	 private int mCount;
 	 private final static int FRAME_RATE = 15;
 	 // decoder
 	 public void onFrame0(byte[] buf, int offset, int length, int flag) {  
 	        ByteBuffer[] inputBuffers = mediaCodecd.getInputBuffers();  
 	        int inputBufferIndex = mediaCodecd.dequeueInputBuffer(-1);  
 	        if (inputBufferIndex >= 0) {  
 	            ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];  
 	            inputBuffer.clear();  
 	            inputBuffer.put(buf, offset, length);  
 	            mediaCodecd.queueInputBuffer(inputBufferIndex, 0, length,
 	            		mCount * 1000000 / FRAME_RATE, 0);  
 	            mCount++;  
 	        }  
 	  
 	       MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();  
 	       int outputBufferIndex = mediaCodecd.dequeueOutputBuffer(bufferInfo,0);  
 	       while (outputBufferIndex >= 0) {  
 	    	   /*
 	    	   ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];  
 	    	   byte[] outData = new byte[bufferInfo.size + 3];  
 	    		        outputBuffer.get(outData, 3, bufferInfo.size);  
 	    	   if (frameListener != null) {  
 	    		     if ((outData[3]==0 && outData[4]==0 && outData[5]==1)  
 	    		     || (outData[3]==0 && outData[4]==0 && outData[5]==0 && outData[6]==1))  
 	    		     {  
 	    		         frameListener.onFrame(outData, 3, outData.length-3, bufferInfo.flags);  
 	    		     }  
 	    		     else  
 	    		     {  
 	    		      outData[0] = 0;  
 	    		      outData[1] = 0;  
 	    		      outData[2] = 1;  
 	    		         frameListener.onFrame(outData, 0, outData.length, bufferInfo.flags);  
 	    		     }  
 	    		} 
 	    		 */
 	           mediaCodecd.releaseOutputBuffer(outputBufferIndex, true);  //true
 	           outputBufferIndex = mediaCodecd.dequeueOutputBuffer(bufferInfo, 0);  
 	       }  
 	}
 	
 	@Override
	public void onPreviewFrame(byte[] data, Camera camera) {
		// TODO Auto-generated method stub
 		int ret = offerEncoder(data,h264);  
        
 		/*
	     if(ret > 0)  
	     {   	 
	    	 try {
	    		 outputStream.write(h264, 0, ret);
	    		 netSendTask.pushBuf(h264, ret);
			 } catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			 } // write into h264 file
	         //Log.i("Encoder", ret + " bytes written");
	     }
	     */
	     
		 onFrame(data, 0, data.length, 0); 
		 camera.addCallbackBuffer(buf);	
		 //Log.i("Encoder", "--------------------onPreviewFrame-----------------"+data.length);
	}
    
 	private static final String REMOTE_HOST= "192.168.1.3";
 	private static final short REMOTE_HOST_PORT = 5000;
    private InetAddress address;
 	private DatagramSocket socket;
 	
     class UdpSendTask extends Thread{
 		private ArrayList<ByteBuffer> mList;
 		private boolean running;
 		public void init()
 		{
 			try {  
 				running = true;
 	            socket = new DatagramSocket();  
 	            address = InetAddress.getByName(REMOTE_HOST);  
 	        } catch (SocketException e) {  
 	            e.printStackTrace();  
 	        } catch (UnknownHostException e) {
 	            e.printStackTrace();  
 	        }
 			
 			mList = new ArrayList<ByteBuffer>();
 			
 		}
 		
 		public void end()
 		{
 			running = false;		
 		}
 		
 		public void pushBuf(byte[] buf,int len)
 		{
 			ByteBuffer buffer = ByteBuffer.allocate(len);
 			buffer.put(buf,0,len);
 			mList.add(buffer);
 		}
 		
 		@Override  
 	    public void run() {
 			Log.d("UdpSendTask","fall in udp send thread");
 			while(running){
 				if(mList.size() <= 0){
 		        	try {
 						Thread.sleep(100);
 					} catch (InterruptedException e) {
 						e.printStackTrace();
 					}
 		        }
 		        while(mList.size() > 0){
 		        	ByteBuffer sendBuf = mList.get(0);
 		        	try {         
 		        		Log.d("UdpSendTask","send udp packet len:"+sendBuf.capacity());
 		                DatagramPacket packet=new DatagramPacket(sendBuf.array(),sendBuf.capacity(), address,REMOTE_HOST_PORT);  
 		                socket.send(packet);  
 		            } catch (Throwable t) {
 		    	        t.printStackTrace();
 		    	    }
 		        	mList.remove(0);
 		        }	
 			}
 	    }  
 	}
    public DisplayMetrics getDM(Context context) {
		WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
		Display display = windowManager.getDefaultDisplay();
		DisplayMetrics outMetrics = new DisplayMetrics();
		display.getMetrics(outMetrics);
		return outMetrics;
	}

    
    public byte[] getFromRaw() {
		try {
			InputStream in = getResources().openRawResource(R.raw.image_640_480_yuv); // 从Resources中raw中的文件获取输入流

			int length = in.available();
			byte[] buffer = new byte[length];

			in.read(buffer);
			in.close();

			return buffer;
		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}
    
    public ByteBuffer[] yuvPlanes;
    
    public void copyFrom(byte[] yuvData, int width, int height) {
    	
    	int[] yuvStrides = { width, width / 2, width / 2};
    	
    	if (yuvPlanes == null) {
            yuvPlanes = new ByteBuffer[3];
            yuvPlanes[0] = ByteBuffer.allocateDirect(yuvStrides[0] * height);
            yuvPlanes[1] = ByteBuffer.allocateDirect(yuvStrides[1] * height / 2);
            yuvPlanes[2] = ByteBuffer.allocateDirect(yuvStrides[2] * height / 2);
    	}
    	
        if (yuvData.length < width * height * 3 / 2) {
          throw new RuntimeException("Wrong arrays size: " + yuvData.length);
        }
        
        int planeSize = width * height;
        
        ByteBuffer[] planes = new ByteBuffer[3];
        
        planes[0] = ByteBuffer.wrap(yuvData, 0, planeSize);
        planes[1] = ByteBuffer.wrap(yuvData, planeSize, planeSize / 4);
        planes[2] = ByteBuffer.wrap(yuvData, planeSize + planeSize / 4, planeSize / 4);
        
        for (int i = 0; i < 3; i++) {
        	yuvPlanes[i].position(0);
        	yuvPlanes[i].put(planes[i]);
        	yuvPlanes[i].position(0);
        	yuvPlanes[i].limit(yuvPlanes[i].capacity());
        }
	}    
    
	/**
	 * Remember to resume the glSurface
	 */
	@Override
	protected void onResume() {
		super.onResume();
		//glSurfaceView.onResume();
	}

	/**
	 * Also pause the glSurface
	 */
	@Override
	protected void onPause() {
		super.onPause();
		//glSurfaceView.onPause();
	}
	
	@Override
	protected void onStart(){
		super.onStart();
		Log.i("Encoder", "--------------onStart--------------");
		
	}
	
	
	@Override 
	protected void onDestroy(){
		super.onDestroy();
		Log.i("Encoder", "--------------onDestroy--------------");
	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		// TODO Auto-generated method stub
		Log.i("Encoder", "--------------surfaceCreated--------------");
		showCodecCapabilities();
		selectCodecs();
		
		MediaCodecEncodeInit();
		MediaCodecDecodeInit();
		openCamera(holder); //holder
		
		//netSendTask = new UdpSendTask();
		//netSendTask.init();
		//netSendTask.start();
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		// TODO Auto-generated method stub
		Log.i("Encoder", "--------------surfaceDestroyed--------------");
		releaseCamera();
		close();
		//netSendTask.end();
	}
}