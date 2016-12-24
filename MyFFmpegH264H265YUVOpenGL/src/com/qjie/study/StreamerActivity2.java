package com.qjie.study;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Camera.Size;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import net.obviam.opengl.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

public class StreamerActivity2 extends Activity implements PreviewCallback, SurfaceHolder.Callback {
    private static final String TAG = "StreamerActivity2"; 
    private Camera camera;
    
    private int width=320, height=240;
	private byte[] buf;
	private Surface surface;
	private SurfaceView surfaceView;
    
	VideoView  videoView;
    MediaCodec mediaCodec, mediaCodecd;
    private BufferedOutputStream outputStream;
    private byte[] h264 = new byte[width*height*3/2]; 
    
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
    
    //method 2
    private native void loop(String addr);
    private native void end();
    private native void WriteRtspFrame(byte[] data, int size);
    
    //method 1
    private native void RtspTempFile(String filename);     
    private native int  RtspServer(String filename);
    
    private native void RtspLiving(String filename);    
    private native void RtspLive();
    private native void RtspH264Data(byte[] data, int size); 
    
    int type_id = 0;
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate()");
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
    	setContentView(R.layout.activity_camera2);
    	 
        surfaceView = (SurfaceView)findViewById(R.id.mSurfaceview);
        //surfaceView = new SurfaceView(this);
		SurfaceHolder mSurfaceHolder = surfaceView.getHolder();
		//mSurfaceHolder.setFormat(PixelFormat.TRANSPARENT);//translucent半透明 transparent透明  
		mSurfaceHolder.addCallback(this);
		surface = surfaceView.getHolder().getSurface();
		//mSurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
		
		//getActionBar().hide();
		//setContentView(surfaceView);
		
		if (Build.VERSION.SDK_INT < 20){ //Build.VERSION_CODES.LOLLIPOP) {
            // your code using Camera API here - is between 1-20
        } else if(Build.VERSION.SDK_INT >= 21){//Build.VERSION_CODES.LOLLIPOP) {
            // your code using Camera2 API here - is api 21 or higher
        }

		
		videoView = (VideoView)findViewById(R.id.mVideoView);
		
		Button playBtn = (Button) findViewById(R.id.playRtsp);
		playBtn.setOnClickListener(new OnClickListener() {
			@Override
		    public void onClick(View v){
				// Do something in response to button click
				//PlayRtspStream();
		    }
		});
		
		Button playBtn1 = (Button) findViewById(R.id.playRtsp1);
		playBtn1.setOnClickListener(new OnClickListener() {
			@Override
		    public void onClick(View v){
				// Do something in response to button click
				new Thread() {
					@Override
					public void run() {
						File f = new File(Environment.getExternalStorageDirectory(), "mytemp.264"); 
						try {
							 if(!f.exists()){
							    f.createNewFile();
							    Log.w("StreamerActivity", " rtsp h264 file "+f.getPath());
							 }else{
								if(f.delete()){
								   Log.w("StreamerActivity", " rtsp h264 file create again! "+f.getPath());
								   f.createNewFile();
								}
							}
						} catch (IOException e) {
							 e.printStackTrace();
						}
						
						RtspLive();  //method 4
					}
				}.start();	
		    }
		});
		
		Button playBtn2 = (Button) findViewById(R.id.playRtsp2);
		playBtn2.setOnClickListener(new OnClickListener() {
			@Override
		    public void onClick(View v){
				// Do something in response to button click
				RtspPlayH264(); // method 3
		    }
		});
		
		Button playBtn3 = (Button) findViewById(R.id.playRtsp0);
		playBtn3.setOnClickListener(new OnClickListener() {
			@Override
		    public void onClick(View v){
				// Do something in response to button click
				RtspTempFile(h264Path); //method 1
		    }
		});
            
		Button playBtn0 = (Button) findViewById(R.id.playRtsp0);
		playBtn0.setOnClickListener(new OnClickListener() {
			@Override
		    public void onClick(View v){
				// Do something in response to button click
				final String h264Path = Environment.getExternalStorageDirectory() + "/butterfly.h264";
				File file = new File(h264Path);
				if(file.exists()){
					Log.w("MainActivity", "      h264 file is exists!   ");
					RtspLiving(h264Path); //method 5
				}
		    }
		});

        isSupportMediaCodecHardDecoder();
        getCodecs();
        /*
	    MediaCodecInfo[] mediaCodecInfo = getCodecs();
	    if(mediaCodecInfo.length>0){
	    	for(int i=0; i<mediaCodecInfo.length; i++){
	    		Log.i("Encoder", "selectCodec = "+ mediaCodecInfo[i].getName());
	    	}
	    } 
	    */
	}
    
    public void RtspPlayH264() {
		final String h264Path = Environment.getExternalStorageDirectory() + "/butterfly.h264";
		File file = new File(h264Path);
		if(file.exists()){
		    Log.w("MainActivity", "      h264 file is exists!   ");
		}

		int size = (int) file.length();

		System.out.println("h264Path =  " + size);
		if ("".equals(h264Path)) {
			Toast.makeText(this, "路径不能为空", 1).show();
			return;
		}

		new Thread() {
			@Override
			public void run() {
				Log.w("StreamerActivity", " isPlaying = "+h264Path);
				RtspServer(h264Path);
				//pd.dismiss();
			}

		}.start();
	}
    
    public void PlayRtspStream(){
		//String rtspUrl = "rtsp://218.204.223.237:554/live/1/67A7572844E51A64/f68g2mj7wjua3la7.sdp";
		
		String sdp = "rtsp://192.168.1.6:8554/live";
		if(sdp != null){
		 //Create media controller
        //mMediaController = new MediaController(MainActivity.this);
        //videoView.setMediaController(mMediaController);
        
		videoView.setVideoURI(Uri.parse(sdp));
		videoView.requestFocus();
		
		Log.w("StreamerActivity2", "PlayRtspStream sdp = "+sdp);
		Toast.makeText(this, "sdp = "+sdp, 1).show();
		
		videoView.start();
		}
	}
    
    public static MediaCodecInfo[] getCodecs() {

	    //if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
	    //    MediaCodecList mediaCodecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
	    //    return mediaCodecList.getCodecInfos();
	    //} else {
	        int numCodecs = MediaCodecList.getCodecCount();
	        MediaCodecInfo[] mediaCodecInfo = new MediaCodecInfo[numCodecs];

	        for (int i = 0; i < numCodecs; i++) {
	            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
	            mediaCodecInfo[i] = codecInfo;
	            Log.i("Encoder", "selectCodec = "+ mediaCodecInfo[i].getName());
	        }

	        return mediaCodecInfo;
	    //}       
	}
    
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
	                    //if ("MediaCodec".equals(tagName)) {
	                        String componentName = xmlPullParser.getAttributeValue(0);
	                        
	                        Log.i("MediaCodec", "MediaCodec = "+componentName);
	                        
	                        if(componentName.startsWith("OMX."))
	                        {
	                            if(!componentName.startsWith("OMX.google."))
	                            {
	                                isHardcode = true;
	                            }
	                        }
	                    //}
	                }
	                eventType = xmlPullParser.next();
	            }
	        } catch (Exception e) {
	            // TODO: handle exception
	        }
	    }
	    return isHardcode;
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy()");
        close();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        releaseCamera(); // release the camera immediately on pause event
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
			
			   
			List<Integer> supportedPreviewFormats = parameters.getSupportedPreviewFormats();
		    for (int i = 0; i < supportedPreviewFormats.size(); i++) {
		        Log.d(TAG, "supportedPreviewFormats[" + i + "]="
		                    + getImageFormatString(supportedPreviewFormats.get(i)));
		    }
		        
			List<Size> supportedPreviewSizes = parameters.getSupportedPreviewSizes();
			for (int i = 0; i < supportedPreviewSizes.size(); i++) {
	            Log.d(TAG, "supportedPreviewSizes[" + i + "]=" + supportedPreviewSizes.get(i).width
	                    + "x" + supportedPreviewSizes.get(i).height);
	        }

	        List<int[]> fpsRange = parameters.getSupportedPreviewFpsRange();
			for (int[] temp3 : fpsRange) {
			     System.out.println(Arrays.toString(temp3));
			}    
				
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
			
			//parameters.setPreviewFpsRange(4000,60000);
			//parameters.setPreviewFpsRange(10000,30000); //this one results fast playback when I use the FRONT CAMERA 
			
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
	
    public static String getImageFormatString(int imageFormat) {
        switch (imageFormat) {
            case ImageFormat.JPEG:
                return "JPEG";
            case ImageFormat.NV16:
                return "NV16";
            case ImageFormat.NV21:
                return "NV21";
            case ImageFormat.RGB_565:
                return "RGB_565";
            case ImageFormat.YUY2:
                return "YUY2";
            case ImageFormat.YV12:
                return "YV12";
            default:
                return "UNKNOWN";
        }
    }
 
    
    /**
     * Returns a color format that is supported by the codec and by this test code.  If no
     * match is found, this throws a test failure -- the set of formats known to the test
     * should be expanded for new platforms.
     */
    private static int selectColorFormat(MediaCodecInfo codecInfo, String mimeType) {
    	int colorFormat0 = 0;
        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
        for (int i = 0; i < capabilities.colorFormats.length; i++) {
            int colorFormat = capabilities.colorFormats[i];
            Log.i("Encoder", "ColorFormat = "+colorFormat);
            if (isRecognizedFormat(colorFormat)) {
            	Log.i("Encoder", "selectColorFormat = "+colorFormat);
            	colorFormat0 = colorFormat;
                //return colorFormat;
            }
        }
        Log.e("Encoder","a good color format for " + codecInfo.getName() + " / " + mimeType
        		+ ", selectColorFormat = "+colorFormat0);
        return colorFormat0;   // not reached
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
		MediaCodecInfo codecInfo0 = null;
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);

            if (!codecInfo.isEncoder()) {
                continue;
            }

            String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
            	Log.i("Encoder", "Codec = "+types[j]);
                if (types[j].equalsIgnoreCase(mimeType)) {
                	Log.i("Encoder", "selectCodec = "+codecInfo.getName());
                	codecInfo0 = codecInfo;
                	//return codecInfo;
                }
            }
        }
        return codecInfo0;
    }
    
	String h264Path;
	
	public void MediaCodecEncodeInit(){
		String type = "video/avc";
		
		File f = new File(Environment.getExternalStorageDirectory(), "mycamerartsp_0.264");
		try {
			 if(!f.exists()){
			    f.createNewFile();
			    Log.w("StreamerActivity", " rtsp file "+f.getPath());
			 }else{
				if(f.delete()){
				   Log.w("StreamerActivity", " rtsp file create again! ");
				   f.createNewFile();
				}
			}
		} catch (IOException e) {
			 e.printStackTrace();
		}
		h264Path = Environment.getExternalStorageDirectory()+"/mycamerartsp_0.264";
		
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
		mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 500000);//125kbps  
		mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 15);  
		mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
		//mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, 
		//		MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);  //COLOR_FormatYUV420Planar
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
	    	if(mediaCodec != null){
	        mediaCodec.stop();
	        mediaCodec.release();
	        
	        mediaCodecd.stop();
	        mediaCodecd.release();
 
	        outputStream.flush();
	        outputStream.close();
	        mediaCodec = null;        
	    	}
	        Log.i("Encoder", "--------------close--------------");
	    } catch (Exception e){ 
	        e.printStackTrace();
	    }
	}

	 //yv12 转 yuv420p  yvu -> yuv  
    private void swapYV12toI420(byte[] yv12bytes, byte[] i420bytes, int width, int height)   
    {        
        System.arraycopy(yv12bytes, 0, i420bytes, 0,width*height);  
        System.arraycopy(yv12bytes, width*height+width*height/4, i420bytes, width*height,width*height/4);  
        System.arraycopy(yv12bytes, width*height, i420bytes, width*height+width*height/4,width*height/4);    
    } 

    
    int  frame_count, frame_count1, frame_count2;
    private boolean runTestFlag = true;
	// encode
	public void onFrame(byte[] buf, int length) {	
		 
		    swapYV12toI420(buf, h264, width, height); 
		    
		    ByteBuffer[] inputBuffers = mediaCodec.getInputBuffers();
		    ByteBuffer[] outputBuffers = mediaCodec.getOutputBuffers();
		    int inputBufferIndex = mediaCodec.dequeueInputBuffer(-1);
		    if (inputBufferIndex >= 0) {
		        ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
		        inputBuffer.clear();
		        inputBuffer.put(h264, 0, length);
		        mediaCodec.queueInputBuffer(inputBufferIndex, 0, length, 0, 0);
		    }
		    MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
		    int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo,0);
		    while (outputBufferIndex >= 0) {
		        ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];

	            byte[] outData = new byte[bufferInfo.size];
	            outputBuffer.get(outData);

	            
	            frame_count++;
				if(frame_count>50){
					frame_count = 0;
					frame_count1++;
					//Log.i("Encoder", " onFrame = "+frame_count1);
				}
				/*
				if(runTestFlag)
				{
					frame_count2++;
					if(frame_count2 >= 50){
						runTestFlag = false;
						new Thread() {
							@Override
							public void run() {
								Log.w("StreamerActivity", " isPlaying = "+h264Path);
				    			//Log.w("StreamerActivity", " isPlaying = "+h264Path);
								if(type_id==1){
								   RtspServer(h264Path); //method 1
								}else if(type_id==2){
								   loop("192.168.1.6"); //method 2
								}
							}

						}.start();
						
					    //selectColorFormat(selectCodec("video/avc"), "video/avc");
					}
				}
				*/
				
				//RtspH264Data(outData, outData.length); // method 5

				onFrame0(outData, 0, outData.length, 0);
				
				try {
					outputStream.write(outData, 0, outData.length); //method2 & method 1 = java write frame
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				/*
				if(type_id==1){
					try {
						outputStream.write(outData, 0, outData.length); //method2 & method 1 = java write frame
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				    WriteRtspFrame(outData, outData.length); // method 1 = native write frame
				}
				*/
				// write into h264 file
	            //Log.i("Encoder", outData.length + " bytes written");
		        
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
	 
	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		// TODO Auto-generated method stub
		showCodecCapabilities();
		MediaCodecEncodeInit();
		MediaCodecDecodeInit();
		openCamera(holder);
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		// TODO Auto-generated method stub
		//isSupportMediaCodecHardDecoder();
		//getCodecs();
		//selectColorFormat(selectCodec("video/avc"), "video/avc");
		releaseCamera();
		close();
		
		if(type_id==2){
		   end(); // method 2
		}
	}

	@Override
	public void onPreviewFrame(byte[] data, Camera camera) {
		// TODO Auto-generated method stub
		
		onFrame(data, data.length); 
		camera.addCallbackBuffer(buf);	
		//Log.i("Encoder", "--------------------onPreviewFrame-----------------"+data.length);
	}
	 
}
