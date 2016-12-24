package com.example.mymp4v2h264;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;  
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException; 
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.obviam.opengl.R;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Camera.Size;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.View.OnClickListener;


@SuppressLint("NewApi") 
public class CameraActivity extends Activity implements SurfaceHolder.Callback,PreviewCallback,OnClickListener{
	
	
	private static final String TAG = "StudyCamera";
	private static final int FRAME_RATE = 15;
	private static final String REMOTE_HOST= "192.168.1.2";
	private static final short REMOTE_HOST_PORT = 5000;
	//private static final String H264FILE = "/sdcard/test.h264";
	private static final String H264FILE = Environment.getExternalStorageDirectory() + "/h264.h264";// butterfly.h264
	private boolean bOpening = false;
	private SurfaceView surfaceView;
	private SurfaceHolder surfaceHolder;
	
	private Surface	mSurface;
	private Camera 	mCamera;
	private int		mCameraWidth  = 640;
	private int     mCameraHeight = 480;
	private int		mSurfaceWidth = 640;
	private int     mSurfaceHeight= 480;
	private MediaCodec mMediaEncoder;
	private MediaCodec mMediaDecoder;
	private Paint 		paint;
	
	private InetAddress    address;
	private DatagramSocket socket;
	private UdpSendTask    netSendTask;
	private H264FileTask   h264FileTask;
	
	BufferedOutputStream outputStream;
	private int mFrameIndex = 0;
	private byte[] mEncoderH264Buf;
	private byte[] mMediaHead = null;  	
	private byte[] mYuvBuffer = new byte[640*480*3/2];
	private byte[] buf;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
				WindowManager.LayoutParams.FLAG_FULLSCREEN);
		
		setContentView(R.layout.activity_camera);
		findViewById(R.id.btnEnc).setOnClickListener(this);
		findViewById(R.id.btnDec).setOnClickListener(this);
		findViewById(R.id.btnUdp).setOnClickListener(this);
		
		//mCameraWidth  = 640;
		//mCameraHeight = 480;
		if(!setupView()){
			Log.e(TAG, "failed to setupView");
			return;
		}	

		paint = new Paint();
		mMediaEncoder = null;
		mMediaDecoder = null;
		mSurface = null;
		
		mEncoderH264Buf = new byte[640*480*3/2];
		
		netSendTask = new UdpSendTask();
		netSendTask.init();
		netSendTask.start();
	}

	boolean udpFlag = false;
	boolean encFlag = false;
	boolean decFlag = false;
	@Override
	public void onClick(View view)
	{
		switch(view.getId())
		{
		case R.id.btnEnc:
			 {
				decFlag = false;
			    if(!encFlag){
			    	File f = new File(Environment.getExternalStorageDirectory(), "mediacodectest.264");
					if(!f.exists()){	
					    try {
							f.createNewFile();
							Log.e(TAG, "       create a file     ");
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
					    }
					}else{
						if(f.delete()){
							try {
								f.createNewFile();
								Log.e(TAG, "      delete and create a file    ");
							} catch (IOException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
						}
					}
					try {
					    outputStream = new BufferedOutputStream(new FileOutputStream(f));
					    Log.i("Encoder", "outputStream initialized");
					} catch (Exception e){ 
					     e.printStackTrace();
					}
			    }else{
			    	try {
						outputStream.flush();
						outputStream.close();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
			    }
			    if(!encFlag){
			    	encFlag = true;
			        setupCamera();
			    }else{
			    	encFlag = false;
			    	releaseCamera();
			    }
			    
			    /*
			    if(!encFlag){
				    if(!startCamera()){
					   Log.e(TAG, "failed to startCamera");
					   return;
				    }else{
				       encFlag = true;
				       Log.e(TAG, "     startCamera ok   ");
				    }
			    }else{
					if(!stopCamera()){
					   Log.e(TAG, "failed to stopCamera");
				       return;
					}else{
				   	   encFlag = false;
					   Log.e(TAG, "   stopCamera ok  ");
					}
				}
			    */
			 }
			 break;
		case R.id.btnDec: 
		     {
		    	/*
				if(!stopCamera()){
					Log.e(TAG, "failed to stopCamera");
					//return;
				}
				*/
				if(encFlag){
			    	encFlag = false;
			    	releaseCamera();
			    }
				Log.e(TAG, "   decFlag  "+decFlag);
				if(!decFlag){
					decFlag = true;
				    startPlayH264File(); // decode + surface display
				}else{
					decFlag = false; 
				}
			 }
		     break;
		case R.id.btnUdp:
			 udpFlag = !udpFlag;
			 break;
		}
	}
	
	@Override
	public void surfaceCreated(SurfaceHolder holder) {  
        Log.i(TAG, "surfaceCreated.");  
    }  
	
	@Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width,  
            int height) {  
		Log.i(TAG,"surfaceChanged w:"+width+" h:"+height);
		mSurface = surfaceHolder.getSurface();
		
        mSurfaceWidth  = 640;
        mSurfaceHeight = 480;
        
        
        if(mMediaEncoder == null){
        	if(!setupEncoder("video/avc", mCameraWidth, mCameraHeight)){
    			releaseCamera();
    			Log.e(TAG, "failed to setupEncoder");
    			return;
    		}else{
    			Log.e(TAG, "       setupEncoder ok     ");
    		}
        } 
        
        if(mMediaDecoder == null){
        	if(!setupDecoder(mSurface,"video/avc", mCameraWidth, mCameraHeight)){
    			releaseCamera();
    			Log.e(TAG, "failed to setupDecoder");
    			return;
    		}else{
    			Log.e(TAG, "       setupDecoder ok     ");
    		}
        }
        
        /*
         * get canvas from surface holder with draw something
         * */
//        Surface surface = holder.getSurface();
//        if(!setupDecoder(surface,"video/avc",mCameraWidth,mCameraHeight)){
//			releaseCamera();
//			Log.e(TAG, "failed to setupEncoder");
//			return;
//		}

//      Canvas c = holder.lockCanvas();
//		paint.setColor(Color.WHITE);
//		paint.setStrokeWidth(4);
//		c.drawRect(0, 0, width, height, paint);
//		paint.setColor(Color.BLACK);
//		paint.setTextSize(30);
//		paint.setStrokeWidth(1);        
//		c.drawText("http://blog.csdn.net/", 100, 100, paint);


//        Bitmap bitmap = null;
//        bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.waiting);
//        if (bitmap != null) {
//            float left=(c.getWidth()-bitmap.getWidth())/2;
//            float top=(c.getHeight()-bitmap.getHeight())/2;
//            c.drawBitmap(bitmap, left, top, paint);
//        }
//
//        holder.unlockCanvasAndPost(c);
    }  
	
	@Override
    public void surfaceDestroyed(SurfaceHolder holder) {  
        Log.i(TAG,"surfaceDestroyed");
        mSurface = null;
        releaseCamera();
        
        try {
			outputStream.flush();
			outputStream.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    }    

	int  enc_len =  mCameraWidth*mCameraHeight*3/2;
	
	@Override
	public void onPreviewFrame(byte[] rawData, Camera camera)
	{
		//int w = camera.getParameters().getPreviewSize().width; 
        //int h = camera.getParameters().getPreviewSize().height; 
        //int format = camera.getParameters().getPreviewFormat();
        //Log.d(TAG,"preview frame format:"+format+" size:"+rawData.length+" w:"+w+" h:"+h);

		/*
        //convert yv12 to i420
        swapYV12toI420(rawData, mYuvBuffer, mCameraWidth, mCameraHeight);

        //System.arraycopy(mYuvBuffer, 0, rawData, 0, rawData.length);
               
        //set h264 buffer to zero.
        //for(int i=0;i<mEncoderH264Buf.length;i++)
        //	mEncoderH264Buf[i]=0;
        
        int encoderRet = offerEncoder(mYuvBuffer, mEncoderH264Buf); //rawData
        
        if(encoderRet > 0){
        	//Log.d(TAG,"encoder output h264 buffer len:"+encoderRet);
        	try {
    		    outputStream.write(mEncoderH264Buf, 0, encoderRet);
    		} catch (IOException e) {
    		    // TODO Auto-generated catch block
    			e.printStackTrace();
            }
        	//send to VLC client by udp://@port;
        	if(udpFlag){
        	   netSendTask.pushBuf(mEncoderH264Buf, encoderRet);
        	}
        	// push data to decoder
        	offerDecoder(mEncoderH264Buf,encoderRet);
        }
        
        */
		onFrame(rawData, rawData.length);
        //reset buff to camera.
		camera.addCallbackBuffer(buf);
	}
	
	private boolean setupView()
	{
		Log.d(TAG,"fall in setupView");
		Log.d(TAG,"fall in setupView");
        if (null != surfaceHolder) {
        	surfaceHolder.removeCallback(this);
        	surfaceView = null;
        }
        if (null != surfaceView) {
        	surfaceView = null;
        }
        
		surfaceView = (SurfaceView)findViewById(R.id.surfaceView);
		surfaceHolder = surfaceView.getHolder();
		surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
		surfaceHolder.addCallback(this);
		return true;
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
	private void setupCamera() {
	    releaseCamera();
	    try {
	    	mCamera = getCamera(Camera.CameraInfo.CAMERA_FACING_BACK); // 锟斤拷锟斤拷锟斤拷锟斤拷选锟斤拷前/锟斤拷锟斤拷锟斤拷锟斤拷头
	        } catch (Exception e) {
	        	mCamera = null;
	            e.printStackTrace();
	        }
	        if(mCamera != null){
	    	/*
	        try {
				camera.setPreviewDisplay(holder);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			*/
	        mCamera.setDisplayOrientation(90); // 锟剿凤拷锟斤拷为锟劫凤拷锟结供锟斤拷锟斤拷转锟斤拷示锟斤拷锟街的凤拷锟斤拷锟斤拷锟斤拷锟斤拷锟斤拷影锟斤拷onPreviewFrame锟斤拷锟斤拷锟叫碉拷原始锟斤拷锟捷ｏ拷
			
			Camera.Parameters parameters = mCamera.getParameters();
			
			parameters.setPreviewSize(mCameraWidth, mCameraHeight); // 锟斤拷锟斤拷锟斤拷锟斤拷锟矫很讹拷锟斤拷锟斤拷牟锟斤拷锟斤拷锟斤拷锟斤拷墙锟斤拷锟斤拷缺锟斤拷锟斤拷锟角帮拷锟斤拷锟角凤拷支锟街革拷锟斤拷锟矫ｏ拷锟斤拷然锟斤拷锟杰会导锟铰筹拷锟斤拷
			//parameters.getSupportedPreviewSizes();
			parameters.setFlashMode("off"); // 锟斤拷锟斤拷锟斤拷锟�  
			parameters.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO); 
			parameters.setPreviewFormat(ImageFormat.YV12); // 锟斤拷锟矫革拷式锟斤拷NV21 / YV12
			parameters.setSceneMode(Camera.Parameters.SCENE_MODE_AUTO);  
			//parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO); 
			//parameters.set("orientation", "portrait");
			//parameters.set("orientation", "landscape");
			mCamera.setParameters(parameters);
         
			
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
			
			buf = new byte[mCameraWidth*mCameraHeight*3/2];
			mCamera.addCallbackBuffer(buf);
			mCamera.setPreviewCallbackWithBuffer(this);
			
			List<int[]> fpsRange = parameters.getSupportedPreviewFpsRange();
			for (int[] temp3 : fpsRange) {
			     System.out.println(Arrays.toString(temp3));
			}
			
			//parameters.setPreviewFpsRange(10000, 30000);    
			parameters.setPreviewFpsRange(10000, 30000);
			//parameters.setPreviewFpsRange(4000,60000);//this one results fast playback when I use the FRONT CAMERA 
			
			mCamera.startPreview();
			
			Log.i("Encoder", "width = "+mCameraWidth+", height = "+mCameraHeight);
			
			Log.i("Encoder", "--------------openCamera--------------");
	    }
	}
	
	private boolean startCamera(){
		Log.d(TAG,"fall in startCamera");
		if(bOpening)return false;
		mCamera.startPreview(); // Start Preview  
        bOpening = true;
		return true;
	}
	
	private boolean stopCamera(){
		Log.d(TAG,"fall in stop Camera");
		if(!bOpening)return false;
		
		mCamera.stopPreview();// stop preview 
		bOpening = false;
		return true;
	}
	
	private boolean releaseCamera()
	{
		Log.d(TAG,"fall in release Camera");
		
		if(mCamera != null){
		    mCamera.stopPreview();
		    mCamera.release(); // Release camera resources  
            mCamera = null; 
            return true;
        }else{
        	return false;
        }
	}

	//int colorFormat = selectColorFormat(selectCodec("video/avc"), "video/avc");
	 
	private boolean setupEncoder(String mime, int width, int height)
	{
		String type = "video/avc";
		int colorFormat = selectColorFormat(selectCodec("video/avc"), "video/avc");
		try {
			mMediaEncoder = MediaCodec.createEncoderByType(type);
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
		mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5); //锟截硷拷帧锟斤拷锟绞憋拷锟� 锟斤拷位s  
		mMediaEncoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);  
		mMediaEncoder.start();	
		Log.i("Encoder", "--------------MediaCodecEncodeInit--------------");
		return true;
	}
	
	private boolean setupDecoder(Surface surface,String mime,int width, int height){
		String type = "video/avc";
		try {
			mMediaDecoder = MediaCodec.createDecoderByType(type);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}  
		MediaFormat mediaFormat = MediaFormat.createVideoFormat(type, width, height);  
		mMediaDecoder.configure(mediaFormat, surface, null, 0);  
		mMediaDecoder.start(); 
		return true;
	}
	
	private int offerEncoder(byte[] input,byte[] output) {
		int pos = 0;          
	    try {
	        ByteBuffer[] inputBuffers = mMediaEncoder.getInputBuffers();
	        ByteBuffer[] outputBuffers = mMediaEncoder.getOutputBuffers();
	        int inputBufferIndex = mMediaEncoder.dequeueInputBuffer(-1);
	        if (inputBufferIndex >= 0) {
	            ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
	            //Log.d(TAG,"offerEncoder InputBufSize: " +inputBuffer.capacity()+" inputSize: "+input.length + " bytes");
	            inputBuffer.clear();
	            inputBuffer.put(input);
	            mMediaEncoder.queueInputBuffer(inputBufferIndex, 0, input.length, 0, 0);
	            
	        }
	       
	        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
	        int outputBufferIndex = mMediaEncoder.dequeueOutputBuffer(bufferInfo,0);
	        while (outputBufferIndex >= 0) {
	        	ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
	        	
	        	byte[] data = new byte[bufferInfo.size];
	            outputBuffer.get(data);
	            
	            //Log.d(TAG,"offerEncoder InputBufSize:"+outputBuffer.capacity()+" outputSize:"+ data.length + " bytes written");
	            if(mMediaHead != null)  
                {                 
                    System.arraycopy(data, 0,  output, pos, data.length);  
                    pos += data.length;  
				} else // 保存pps sps 只有开始时 第一个帧里有， 保存起来后面用
				{
					Log.d(TAG, "offer Encoder save sps head,len:"+data.length);
					ByteBuffer spsPpsBuffer = ByteBuffer.wrap(data);
					if (spsPpsBuffer.getInt() == 0x00000001) {
						mMediaHead = new byte[data.length];
						System.arraycopy(data, 0, mMediaHead, 0, data.length);
					} else {
						Log.e(TAG,"not found media head.");
						return -1;
					}
				}
	            mMediaEncoder.releaseOutputBuffer(outputBufferIndex, false);
	            outputBufferIndex = mMediaEncoder.dequeueOutputBuffer(bufferInfo, 0);
	        }
	        if(output[4] == 0x65) //key frame   编码器生成关键帧时只有 00 00 00 01 65 没有pps sps， 要加上  
            {
                System.arraycopy(output, 0,  input, 0, pos);  
                System.arraycopy(mMediaHead, 0,  output, 0, mMediaHead.length);  
                System.arraycopy(input, 0,  output, mMediaHead.length, pos);  
                pos += mMediaHead.length;  
            }  
	        
	    } catch (Throwable t) {
	        t.printStackTrace();
	    }
	    return pos;
	}
	
	public void onFrame(byte[] buf, int length) {	
		swapYV12toI420(buf, mYuvBuffer, mCameraWidth, mCameraHeight); 
		
	    ByteBuffer[] inputBuffers = mMediaEncoder.getInputBuffers();
	    ByteBuffer[] outputBuffers = mMediaEncoder.getOutputBuffers();
	    int inputBufferIndex = mMediaEncoder.dequeueInputBuffer(-1);
	    if (inputBufferIndex >= 0) {
	        ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
	        inputBuffer.clear();
	        //inputBuffer.put(buf, offset, length);
	        inputBuffer.put(mYuvBuffer, 0, length);
	        mMediaEncoder.queueInputBuffer(inputBufferIndex, 0, length, 0, 0);
	    }
	    MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
	    int outputBufferIndex = mMediaEncoder.dequeueOutputBuffer(bufferInfo,0);
	    while (outputBufferIndex >= 0) {
	        ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];

            byte[] outData = new byte[bufferInfo.size];
            outputBuffer.get(outData);
            
            try {
				outputStream.write(outData, 0, outData.length);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
            
            if(udpFlag){
         	   netSendTask.pushBuf(outData, outData.length);
         	}
            offerDecoder(outData, outData.length);
	        
            mMediaEncoder.releaseOutputBuffer(outputBufferIndex, false);
	        outputBufferIndex = mMediaEncoder.dequeueOutputBuffer(bufferInfo, 0);
	    }
    } 
	
	private  void offerDecoder(byte[] input,int length) {
		try {
	        ByteBuffer[] inputBuffers = mMediaDecoder.getInputBuffers();
	        int inputBufferIndex = mMediaDecoder.dequeueInputBuffer(-1);
	        if (inputBufferIndex >= 0) {
	            ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
	            long timestamp = mFrameIndex++ * 1000000 / FRAME_RATE;
	            //Log.d(TAG,"offerDecoder timestamp: " +timestamp+" inputSize: "+length + " bytes");
	            inputBuffer.clear();
	            inputBuffer.put(input,0,length);
	            mMediaDecoder.queueInputBuffer(inputBufferIndex, 0, length, timestamp, 0);
	        }
	        
	        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
	        int outputBufferIndex = mMediaDecoder.dequeueOutputBuffer(bufferInfo,0);
	        while (outputBufferIndex >= 0) {
	        	//Log.d(TAG,"offerDecoder OutputBufSize:"+bufferInfo.size+ " bytes written");
	        	
	        	//If a valid surface was specified when configuring the codec, 
	        	//passing true renders this output buffer to the surface.  
	            mMediaDecoder.releaseOutputBuffer(outputBufferIndex, true);
	            outputBufferIndex = mMediaDecoder.dequeueOutputBuffer(bufferInfo, 0);
	        }
	    } catch (Throwable t) {
	        t.printStackTrace(); 
	    }
	}
	
	
	private void startPlayH264File()
	{
        if(mMediaDecoder == null){
        	if(!setupDecoder(mSurface,"video/avc",mSurfaceWidth,mSurfaceHeight)){
    			Log.e(TAG, "failed to setupDecoder");
    			return;
    		}
        }
        h264FileTask = new H264FileTask();
        h264FileTask.start();
	}
	
	
	private void swapYV12toI420(byte[] yv12bytes, byte[] i420bytes, int width, int height)   
    {        
        System.arraycopy(yv12bytes, 0, i420bytes, 0,width*height);
        System.arraycopy(yv12bytes, width*height+width*height/4, i420bytes, width*height,width*height/4);  
        System.arraycopy(yv12bytes, width*height, i420bytes, width*height+width*height/4,width*height/4);    
    }    
	
		
	private void readH264FromFile(){
		File file = new File(H264FILE);
		if(!file.exists() || !file.canRead()){
			Log.e(TAG,"failed to open h264 file.");
			return;
		}
		Log.e(TAG,"        readH264FromFile       ");
		decFlag = true;
		try {
			int len = 0;
			FileInputStream fis = new FileInputStream(file);
			byte[] buf = new byte[1024];
			while ((len = fis.read(buf)) > 0){
				offerDecoder(buf, len);
				if(!decFlag){
					break;
				}
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch(IOException e){
			e.printStackTrace();
		}
		return;		
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

    /**
     * Returns true if this is a color format that this test code understands (i.e. we know how
     * to read and generate frames in this format).
     */
    private static boolean isRecognizedFormat(int colorFormat) {
        switch (colorFormat) {
            // these are the formats we know how to handle for this test
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
            	 Log.e(TAG,"   COLOR_FormatYUV420Planar  ");
            	 return true;
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
            	 Log.e(TAG,"   COLOR_FormatYUV420PackedPlanar  ");
           	     return true;
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
            	 Log.e(TAG,"   COLOR_FormatYUV420SemiPlanar  ");
           	     return true;
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
             	 Log.e(TAG,"   COLOR_FormatYUV420PackedSemiPlanar  ");
           	     return true;
            case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar:
            	 Log.e(TAG,"   COLOR_TI_FormatYUV420PackedSemiPlanar  ");
           	     return true;
            default:
                return false;
        }
    }
	
	class H264FileTask extends Thread{
		@Override
		public void run() {
			Log.d(TAG,"fall in H264File Read thread");
			readH264FromFile();
		}
	}
	
	class UdpSendTask extends Thread{
		private ArrayList<ByteBuffer> mList;
		public void init()
		{
			try {  
	            socket = new DatagramSocket();  
	            address = InetAddress.getByName(REMOTE_HOST);  
	        } catch (SocketException e) {  
	            e.printStackTrace();  
	        } catch (UnknownHostException e) {
	            e.printStackTrace();  
	        }
			
			mList = new ArrayList<ByteBuffer>();
			
		}
		public void pushBuf(byte[] buf,int len)
		{
			ByteBuffer buffer = ByteBuffer.allocate(len);
			buffer.put(buf,0,len);
			mList.add(buffer);
		}
		
		@Override  
	    public void run() {
			Log.d(TAG,"fall in udp send thread");
			while(true){
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
		        		Log.d(TAG,"send udp packet len:"+sendBuf.capacity());
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
	
}
