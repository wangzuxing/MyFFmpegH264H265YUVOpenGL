package com.example.mymp4v2h264;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.libsdl.app.SDLActivity;

import net.obviam.opengl.R;

import com.yuv.display.GLES20Support;
import com.yuv.display.GLFrameRenderer;
import com.yuv.display.GLFrameSurface;
import com.yuv.display.YUV;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Camera.Size;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.MediaCodec.BufferInfo;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

public class MainActivity0 extends Activity implements SurfaceHolder.Callback, PreviewCallback, OnClickListener {

	//private int width=320, height=240;
	private int width=640, height=480;
	
	private byte[] buf;
	private Surface surface;
	private SurfaceView surfaceView;
	SurfaceHolder mSurfaceHolder;
	Camera camera = null;
	MediaCodec mediaCodec, mediaCodecd;
	
	byte keyFrame;
	boolean mp4fFlag;
	private byte[] m_info = null; 
	private byte[] yuv420 = new byte[width*height*3/2];
	private byte[] h264 = new byte[width*height*3/2]; 
	
	protected static AudioDecoderThread mAudioDecoder;
	
	private BufferedOutputStream outputStream;
	
	BufferedOutputStream  h264outputStream;
	
	private static final String SAMPLE = Environment.getExternalStorageDirectory() + "/live.mp4";
	
	final String mp4Path = Environment.getExternalStorageDirectory() + "/live.mp4";
	final String mkvPath = Environment.getExternalStorageDirectory() + "/live00.mkv";
	final String mkvPath0 = Environment.getExternalStorageDirectory() + "/live12.mkv";
	
	final String h264Path = Environment.getExternalStorageDirectory() + "/H264_01.mp4";
	
	final String h265Path = Environment.getExternalStorageDirectory() + "/H265_01.h265";
	
	final String h264_Path = Environment.getExternalStorageDirectory() + "/H264_011.h264";//H264_5
	
	final String out_Path = Environment.getExternalStorageDirectory() + "/H264_yuv.yuv";
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
	
	//public native boolean mp4init(String pcm, int type);
	//public native void mp4packVideo(byte[] array, int length, int keyframe);
	//public native void mp4packAudio(byte[] array, int length);
	//public native void mp4close();
	
	
	//mp4v2
	public native boolean Mp4Start(String pcm);
	public native void Mp4PackV(byte[] array, int length, int keyframe);
	public native void Mp4PackA(byte[] array, int length);
	public native void Mp4End();
	
	//x264 
	public native void Mp4CC(String h264_file);
	public native void Mp4CE(byte[] array, int length);
	public native void Mp4EE();
	
	//x265
	public native boolean X265Start(String file);
	public native void X265Enc(byte[] array, int length);
	public native void X265End();
		
	//ffmpeg => x264 => mp4      Encode 
	public native void Mp4FfmpegCC(String h264_file);
	public native void Mp4FfmpegCE(byte[] array, int length);
	public native void Mp4FfmpegEE();
	
	//ffmpeg => x264 + mp3 =>mp4  Encode
	public native void Mp4FfmpegXCC(String h264_file);
	public native void Mp4FfmpegXCE(byte[] array, int length);
	public native void Mp4FfmpegXEE();
	
	//ffmpeg => x264     Encode   avcodec_register_all => direct
	public native void Mp4FpgCC(String h264_file);
	public native void Mp4FpgCE(byte[] array, int length);
	public native void Mp4FpgEE();
	
	//ffmpeg => x264  0  Decode   av_register_all
    public native void Mp4FpgDCC(String out_file, String h264_file);
    public native void Mp4FpgDCE();
    public native void Mp4FpgDEE();
		
    //ffmpeg => x264  1  Decode   avcodec_register_all => direct
    public native void Mp4FpgDCC0(String out_file, String h264_file);
    public native void Mp4FpgDCE0(byte[] array, int length);
    public native void Mp4FpgDEE0();
    
    public native int Mp4Mkv0(String mp4_file, String mkv_file);
    public native int Mp4Mkv(String mp4_file, String mkv_file);
    
    MainActivity0 myYUV;
    FrameLayout preview0;
	FrameLayout preview1;
	/** The OpenGL view */
	private GLSurfaceView glSurfaceView;	
	private GLFrameRenderer glRenderer;
	private Handler mHandler = new Handler();
	int  onFraming;
	boolean isPlaying;
	
	Button btnClick0;
	Button btnClick1;
	Button btnClick2;
	Button btnClick3;
	Button btnClick4;
	Button btnClick5;
	Button btnClick6;
	Button btnClick7;
	
	TextView myshow;
	Button playButton;
	
	@SuppressWarnings("deprecation")
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		//setContentView(R.layout.activity_main);
		mp4fFlag = true;
		/*
		surfaceView = new SurfaceView(this);
		SurfaceHolder mSurfaceHolder = surfaceView.getHolder();
		//mSurfaceHolder.setFormat(PixelFormat.TRANSPARENT);//translucent锟斤拷透锟斤拷 transparent透锟斤拷  
		mSurfaceHolder.addCallback(this);
		surface = surfaceView.getHolder().getSurface();
		//mSurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		//getActionBar().hide();
		setContentView(surfaceView);
        */
		
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
        mSurfaceHolder = surfaceView.getHolder();
		//mSurfaceHolder.setFormat(PixelFormat.TRANSPARENT);//translucent鍗婇�忔槑 transparent閫忔槑  
        mSurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
		mSurfaceHolder.addCallback(this);
		surface = surfaceView.getHolder().getSurface();
		
		//setContentView(surfaceView); 
		
		btnClick0 = (Button) findViewById(R.id.btnClick0);
		btnClick1 = (Button) findViewById(R.id.btnClick1);
		btnClick2 = (Button) findViewById(R.id.btnClick2);
		btnClick3 = (Button) findViewById(R.id.btnClick3);
		btnClick4 = (Button) findViewById(R.id.btnClick4);
		btnClick5 = (Button) findViewById(R.id.btnClick5);
		btnClick6 = (Button) findViewById(R.id.btnClick6);
		btnClick7 = (Button) findViewById(R.id.btnClick7);

		btnClick0.setOnClickListener(this);
		btnClick1.setOnClickListener(this);
		btnClick2.setOnClickListener(this);
		btnClick3.setOnClickListener(this);
		btnClick4.setOnClickListener(this);
		btnClick5.setOnClickListener(this);
		btnClick6.setOnClickListener(this);
		btnClick7.setOnClickListener(this);
		
		myshow = (TextView) findViewById(R.id.myshow);
		
        isPlaying = false;
       
        playButton = (Button) findViewById(R.id.btnPlay);
        playButton.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                    	isPlaying = !isPlaying;
                    	if(isPlaying){
                    	    myshow.setText("YUV --> Opengl es display");
                    	    myshow.setBackgroundColor(Color.RED);
                    	    playButton.setBackgroundColor(Color.MAGENTA);
                    	}else{
                    		myshow.setText("");
                    		myshow.setBackgroundColor(Color.GRAY);
                    		playButton.setBackgroundColor(Color.GRAY);
                    	}
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
                    	isPlaying = false;
         	            mp4fFlag = true;
         	            
         	            Intent intent = new Intent();
         	            intent.setClass(MainActivity0.this, SDLActivity.class);
         	            
         	            startActivity(intent);
         	            
         	            //startActivityForResult(intent, 1);
                    	/*
                    	Message message = new Message();   
                        message.what = 0;     
                        myHandler.sendMessage(message); 
                        */
                    }
                }
        );
        
        Button showButton0 = (Button) findViewById(R.id.btnShow0);
        showButton0.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                    	Log.i("Encoder", "--------------showButton0--------------");
                    	isPlaying = false;
         	            mp4fFlag = true;
         	            
         	            Intent intent = new Intent();
        	            intent.setClass(MainActivity0.this, CameraActivity.class);
        	            
        	            startActivity(intent);
         	            //startActivityForResult(intent, 1);
                    	/*
                    	Message message = new Message();   
                        message.what = 0;     
                        myHandler.sendMessage(message); 
                        */
                    }
                }
        );
        
        /*
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
		*/
        myYUV = this;
	}
	
	int btn_id;
	int btn_id0;
	boolean changefFlag = true;
	@Override
	public void onClick(View v) {
		// TODO Auto-generated method stub
		//int btnId = v.getId();
		Button btn = (Button)v;
		if(btn_id==0)
		{
		    Log.i("Encoder", "                   onClick                    ");
			if(btnClick0 == btn){//mp4v2(Encode)
			    btn_id = 1;
			    btn_id0= 1;
			    final String mp4Path = Environment.getExternalStorageDirectory() + "/mp4v2.mp4";
			    
			    File f = new File(Environment.getExternalStorageDirectory(), "mp4v2.mp4");
				try {
					 if(!f.exists()){
					    f.createNewFile();
					 }
				} catch (IOException e) {
					 e.printStackTrace();
				}
				Log.i("Encoder", "Mp4Start = f.getPath() = "+f.getPath()+", f.getAbsolutePath() = "+f.getAbsolutePath()); 
				
				typeFlag = true;
			    Mp4Start(f.getPath());
			    myshow.setText("MP4V2 Lib --> .mp4");
			    myshow.setBackgroundColor(Color.RED);
			    btnClick0.setBackgroundColor(Color.MAGENTA);
			    Log.i("Encoder", "--------------Mp4Start btnClick0--------------");
			}else if(btnClick1 == btn){//x264(Encode) 
			    btn_id = 2;
			    btn_id0= 2;
			    //final String h264Path = Environment.getExternalStorageDirectory() + "/H264.h264";
			    File f = new File(Environment.getExternalStorageDirectory(), "h264.h264");
				try {
					 if(!f.exists()){
					    f.createNewFile();
					 }
				} catch (IOException e) {
					 e.printStackTrace();
				}
				Log.i("Encoder", "Mp4CC = f.getPath() = "+f.getPath()+", f.getAbsolutePath() = "+f.getAbsolutePath()); 
			    Mp4CC(f.getPath());
			    myshow.setText("X264 Encode --> .h264");
			    myshow.setBackgroundColor(Color.RED);
			    btnClick1.setBackgroundColor(Color.MAGENTA);
			    Log.i("Encoder", "--------------Mp4CC btnClick1--------------");
			}else if(btnClick2 == btn){//x265(Encode)
				btn_id = 3;
				btn_id0= 3;
				File f = new File(Environment.getExternalStorageDirectory(), "H265_03f.h265");
				try {
					 if(!f.exists()){
					    f.createNewFile();
					 }
				} catch (IOException e) {
					 e.printStackTrace();
				}
				Log.i("Encoder", "X265Start = f.getPath() = "+f.getPath()+", f.getAbsolutePath() = "+f.getAbsolutePath()); 
				X265Start(f.getPath());
				myshow.setText("X265 Encode --> .h265");
				myshow.setBackgroundColor(Color.RED);
				btnClick2.setBackgroundColor(Color.MAGENTA);
				Log.i("Encoder", "--------------X265Start btnClick2--------------");
			}else if(btnClick3 == btn){//ffmpeg => x264 => mp4(Encode)
				btn_id = 4;
				btn_id0= 4;
				final String ffh264 = Environment.getExternalStorageDirectory() + "/ffh264.mp4";
				File f = new File(Environment.getExternalStorageDirectory(), "ffh264.mp4");
				try {
					 if(!f.exists()){
					    f.createNewFile();
					 }
				} catch (IOException e) {
					 e.printStackTrace();
				}
				Log.i("Encoder", "Mp4FfmpegCC = f.getPath() = "+f.getPath()+", f.getAbsolutePath() = "+f.getAbsolutePath()); 
				Mp4FfmpegCC(ffh264);
				myshow.setText("FFmpeg(X264) Encode --> .mp4");
				myshow.setBackgroundColor(Color.RED);
				btnClick3.setBackgroundColor(Color.MAGENTA);
				Log.i("Encoder", "--------------Mp4FfmpegCC btnClick3--------------");
			}else if(btnClick4 == btn){//ffmpeg => x264 + mp3 =>mp4(Encode)
				btn_id = 5;
				btn_id0= 5;
				//final String h264Path = Environment.getExternalStorageDirectory() + "/ffh264a.mp4";
				File f = new File(Environment.getExternalStorageDirectory(), "ffh264a.mp4");
				try {
					 if(!f.exists()){
					    f.createNewFile();
					 }
				} catch (IOException e) {
					 e.printStackTrace();
				}
				Log.i("Encoder", "Mp4FfmpegXCC = f.getPath() = "+f.getPath()+", f.getAbsolutePath() = "+f.getAbsolutePath()); 
				Mp4FfmpegXCC(f.getPath());
				myshow.setText("FFmpeg(X264) Encode --> .mp4(interleaved write)");
				myshow.setBackgroundColor(Color.RED);
				btnClick4.setBackgroundColor(Color.MAGENTA);
				Log.i("Encoder", "--------------Mp4FfmpegXCC btnClick4--------------");
			}else if(btnClick5 == btn){//ffmpeg => x264(Encode,avcodec_register_all => direct)
				btn_id = 6;
				btn_id0= 6;
				File f = new File(Environment.getExternalStorageDirectory(), "ffh264.h264");
				try {
					 if(!f.exists()){
					    f.createNewFile();
					 }
				} catch (IOException e) {
					 e.printStackTrace();
				}
				Log.i("Encoder", "Mp4FpgCC = f.getPath() = "+f.getPath()+", f.getAbsolutePath() = "+f.getAbsolutePath()); 
				Mp4FpgCC(f.getPath());
				myshow.setText("FFmpeg(X264) Encode --> .h264");
				myshow.setBackgroundColor(Color.RED);
				btnClick5.setBackgroundColor(Color.MAGENTA);
				Log.i("Encoder", "--------------Mp4FpgCC btnClick5--------------");
			}else if(btnClick6 == btn){//ffmpeg => x264(Decode,av_register_all)
				btn_id = 7;
				btn_id0= 7;
				changefFlag = !changefFlag;
				if(changefFlag){
				    final String h264_Path = Environment.getExternalStorageDirectory() + "/mp4v2.mp4";//H264_5  H264.h264
				    final String out_Path = Environment.getExternalStorageDirectory() + "/H264_yuv.yuv";
				    Mp4FpgDCC(out_Path, h264_Path);
				    myshow.setText("Mp4(MP4V2)-->FFmpeg(X264) Decode-->Opengl es display(YUV)");
				}else{
					final String h264_Path0 = Environment.getExternalStorageDirectory() + "/ffh264a.mp4";//H264_5  H264.h264
					final String out_Path0 = Environment.getExternalStorageDirectory() + "/H264_yuv.yuv";
					Mp4FpgDCC(out_Path0, h264_Path0);
					myshow.setText("Mp4(FFmpeg)-->FFmpeg(X264) Decode-->Opengl es display(YUV)");
				}
				run_stop = false;
				myshow.setBackgroundColor(Color.RED);
				btnClick6.setBackgroundColor(Color.MAGENTA);
				new Thread() {
			        public void run() {
							try {
								Thread.sleep(1500);
								Mp4FpgDCE();
								Log.i("Encoder", "--------------Mp4FpgDCE thread end!--------------");
							} catch (InterruptedException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
			        }
			    }.start();
				Log.i("Encoder", "--------------Mp4FpgDCC btnClick6--------------");
			}else if(btnClick7 == btn){//ffmpeg => x264(Decode,avcodec_register_all => direct)
				btn_id = 8;
				btn_id0= 8;
				final String h264_Path = Environment.getExternalStorageDirectory() + "/H264.h264";//H264_5
				final String out_Path = Environment.getExternalStorageDirectory() + "/H264yuv.yuv";
				run_stop = false;
				typeFlag = true;
				Mp4FpgDCC0(out_Path, h264_Path);
				myshow.setText("MediaCodec Encode -->FFmpeg(X264) Decode-->Opengl es display(YUV)");
				myshow.setBackgroundColor(Color.RED);
				btnClick7.setBackgroundColor(Color.MAGENTA);
				Log.i("Encoder", "--------------Mp4FpgDCC0 btnClick7--------------");
		    }
		}else{
			Log.i("Encoder", "                   onClick release                   ");
			if(btnClick0 == btn && btn_id0==1){
			    btn_id  = 0;
			    btn_id0 = 0;
			    Mp4End();
			    myshow.setBackgroundColor(Color.GRAY);
			    btnClick0.setBackgroundColor(Color.GRAY);
			    Log.i("Encoder", "--------------Mp4End btnClick0--------------");
			}else if(btnClick1 == btn && btn_id0==2){
				btn_id  = 0;
			    btn_id0 = 0;
			    Mp4EE();
			    myshow.setBackgroundColor(Color.GRAY);
			    btnClick1.setBackgroundColor(Color.GRAY);
			    Log.i("Encoder", "--------------Mp4EE btnClick1--------------");
			}else if(btnClick2 == btn && btn_id0==3){
				btn_id  = 0;
			    btn_id0 = 0;
				X265End();
				 myshow.setBackgroundColor(Color.GRAY);
				 btnClick2.setBackgroundColor(Color.GRAY);
				Log.i("Encoder", "--------------X265End btnClick2--------------");
			}else if(btnClick3 == btn && btn_id0==4){
				btn_id  = 0;
			    btn_id0 = 0;
				Mp4FfmpegEE();
				 myshow.setBackgroundColor(Color.GRAY);
				 btnClick3.setBackgroundColor(Color.GRAY);
				Log.i("Encoder", "--------------Mp4FfmpegEE btnClick3--------------");
			}else if(btnClick4 == btn && btn_id0==5){
				btn_id  = 0;
			    btn_id0 = 0;
				Mp4FfmpegXEE();
				 myshow.setBackgroundColor(Color.GRAY);
				 btnClick4.setBackgroundColor(Color.GRAY);
				Log.i("Encoder", "--------------Mp4FfmpegXEE btnClick4--------------");
			}else if(btnClick5 == btn && btn_id0==6){
				btn_id  = 0;
			    btn_id0 = 0;
				Mp4FpgEE();
				 myshow.setBackgroundColor(Color.GRAY);
				 btnClick5.setBackgroundColor(Color.GRAY);
				Log.i("Encoder", "--------------Mp4FpgEE btnClick5--------------");
			}else if(btnClick6 == btn && btn_id0==7){
				btn_id  = 0;
			    btn_id0 = 0;
				Mp4FpgDEE();
				 myshow.setBackgroundColor(Color.GRAY);
				 btnClick6.setBackgroundColor(Color.GRAY);
				Log.i("Encoder", "--------------Mp4FpgDEE btnClick6--------------");
			}else if(btnClick7 == btn && btn_id0==8){
				btn_id  = 0;
			    btn_id0 = 0;
				Mp4FpgDEE0();
				 myshow.setBackgroundColor(Color.GRAY);
				 btnClick7.setBackgroundColor(Color.GRAY);
				Log.i("Encoder", "--------------Mp4FpgDEE0 btnClick7--------------");
		    }
		}
		Log.i("Encoder", "   btn_id = "+btn_id+", btn_id0 = "+btn_id0);
		
	}
	
	@Override
	public void onDestroy() {
        super.onDestroy();
        Log.i("Encoder", "--------------onDestroy--------------");
        if(btn_id == 1 && btn_id0==1){
		    btn_id  = 0;
		    btn_id0 = 0;
		    Mp4End();
		    Log.i("Encoder", "--------------Mp4End btnClick0--------------");
		}else if(btn_id == 2 && btn_id0==2){
			btn_id  = 0;
		    btn_id0 = 0;
		    Mp4EE();
		    Log.i("Encoder", "--------------Mp4EE btnClick1--------------");
		}else if(btn_id == 3 && btn_id0==3){
			btn_id  = 0;
		    btn_id0 = 0;
			X265End();
			Log.i("Encoder", "--------------X265End btnClick2--------------");
		}else if(btn_id == 4 && btn_id0==4){
			btn_id  = 0;
		    btn_id0 = 0;
			Mp4FfmpegEE();
			Log.i("Encoder", "--------------Mp4FfmpegEE btnClick3--------------");
		}else if(btn_id == 5 && btn_id0==5){
			btn_id  = 0;
		    btn_id0 = 0;
			Mp4FfmpegXEE();
			Log.i("Encoder", "--------------Mp4FfmpegXEE btnClick4--------------");
		}else if(btn_id == 6 && btn_id0==6){
			btn_id  = 0;
		    btn_id0 = 0;
			Mp4FpgEE();
			Log.i("Encoder", "--------------Mp4FpgEE btnClick5--------------");
		}else if(btn_id == 7 && btn_id0==7){
			btn_id  = 0;
		    btn_id0 = 0;
			Mp4FpgDEE();
			Log.i("Encoder", "--------------Mp4FpgDEE btnClick6--------------");
		}else if(btn_id == 8 && btn_id0==8){
			btn_id  = 0;
		    btn_id0 = 0;
			Mp4FpgDEE0();
			Log.i("Encoder", "--------------Mp4FpgDEE0 btnClick7--------------");
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
			InputStream in = getResources().openRawResource(R.raw.image_640_480_yuv); // 浠嶳esources涓璻aw涓殑鏂囦欢鑾峰彇杈撳叆娴�

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
    
	boolean run_stop = false;
	
	public void updateYUV(byte[] yuvData, int width0, int height0) {
		//Log.i("updateYUV", "width = "+width0+", height = "+height0);
	    //copyFrom(h264,width,height);
		synchronized (this) {
		  if(width0==0 && height0 ==0){
			  run_stop = true;
			  Log.w("Encoder", "         updateYUV  end         ");
		  }
		  if(!run_stop){
	          copyFrom(yuvData,width0,height0);    
 	          Message message = new Message();   
              message.what = 1;     
              myHandler.sendMessage(message);
		  }
	   }
    }

    public ByteBuffer[] yuvPlanes;
    int planeSize;// = width * height;
    int planeSize_l;
    ByteBuffer[] planes = new ByteBuffer[3];
    
    public void copyFrom(byte[] yuvData, int width, int height) {
    	if (yuvPlanes == null) {
    		int[] yuvStrides = { width, width / 2, width / 2};
    		planeSize = width * height;
    		planeSize_l = planeSize*3/2;
            yuvPlanes = new ByteBuffer[3];
            yuvPlanes[0] = ByteBuffer.allocateDirect(yuvStrides[0] * height);
            yuvPlanes[1] = ByteBuffer.allocateDirect(yuvStrides[1] * height/2);
            yuvPlanes[2] = ByteBuffer.allocateDirect(yuvStrides[2] * height/2);
    	}
    	
        if (yuvData.length < planeSize_l) {
          throw new RuntimeException("Wrong arrays size: " + yuvData.length);
        }

        //ByteBuffer[] planes = new ByteBuffer[3];
        
        planes[0] = ByteBuffer.wrap(yuvData, 0, planeSize);
        planes[1] = ByteBuffer.wrap(yuvData, planeSize, planeSize / 4);
        planes[2] = ByteBuffer.wrap(yuvData, planeSize + planeSize / 4, planeSize / 4);
        
        try{
           if(!run_stop){
        	   for (int i = 0; i < 3; i++) { 
             	  yuvPlanes[i].position(0);
             	  yuvPlanes[i].put(planes[i]);
             	  yuvPlanes[i].position(0);
             	  yuvPlanes[i].limit(yuvPlanes[i].capacity());
               }
           }
        }catch(IllegalArgumentException e){
           e.printStackTrace();
        }catch(BufferOverflowException e){
           e.printStackTrace();
        }
	}    
    
	boolean yuv_update = true;
	Handler myHandler = new Handler() {  
        public void handleMessage(Message msg) {   
             switch (msg.what) {   
                  case 0:   
                	  Log.i("Encoder", "--------------myHandler--------------");
                	  //surfaceView = new SurfaceView(YUV.this);
              		  SurfaceHolder mSurfaceHolder0 = surfaceView.getHolder();
              		  mSurfaceHolder0.setFormat(PixelFormat.TRANSPARENT);//translucent鍗婇�忔槑 transparent閫忔槑  
              		  mSurfaceHolder0.addCallback(myYUV);
              		  surface = surfaceView.getHolder().getSurface();
                      
                      //preview1.addView(surfaceView);
                      break;   
                  case 1:
                	  if(yuv_update){
                	     glRenderer.update(width, height);
                	     yuv_update = false;
                	     Log.i("Encoder", "-------------glRenderer.update(width, height)--------------");
                	  }
                	  byte[] y = new byte[yuvPlanes[0].remaining()];
      	              yuvPlanes[0].get(y, 0, y.length);
      	            
      	              byte[] u = new byte[yuvPlanes[1].remaining()];
      	              yuvPlanes[1].get(u, 0, u.length);
      	            
      	              byte[] v = new byte[yuvPlanes[2].remaining()];
      	              yuvPlanes[2].get(v, 0, v.length);

      	              glRenderer.update(y, u, v);
                	  break;
                  case 2:
                	  openCamera(mSurfaceHolder);
                	  break;
             }   
             super.handleMessage(msg);   
        }   
    }; 
	
	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		Log.i("Encoder", "--------------surfaceCreated--------------");
		MediaCodecEncodeInit();
		//MediaCodecDecodeInit();
		
		//mp4fFlag = Mp4Start(mp4Path); //mp4init(mp4Path,0);
		File f = new File(Environment.getExternalStorageDirectory(), "mytest1.h265");
		try {
			 if(!f.exists()){
			    f.createNewFile();
			 }
		} catch (IOException e) {
			 e.printStackTrace();
		}
		Log.i("Encoder", "f.getPath() = "+f.getPath()+", f.getAbsolutePath() = "+f.getAbsolutePath());  
		 
		File f0 = new File(Environment.getExternalStorageDirectory(), "H264_yuv.yuv");
		try {
			 if(!f0.exists()){
			    f0.createNewFile();
			 }
		} catch (IOException e) {
			 e.printStackTrace();
		}
		Log.i("Encoder", "f0.getPath() = "+f0.getPath()+", f0.getAbsolutePath() = "+f0.getAbsolutePath());  
		//Mp4CC(h264Path);
		//Mp4FfmpegCC(f.getPath());
		
		//Mp4FfmpegXCC(f.getPath()); //H264_10f.mp4
		//Mp4FpgCC(f.getPath()); // 
		
		//Mp4Mkv(mp4Path, mkvPath);//f0.getPath() don't create file, need use file path(ffmpeg can create a file witch this file path)
		//Mp4Mkv0(mp4Path, mkvPath0);
		//Mp4FpgDCC0(out_Path, h264_Path);
		
		//X265Start(f.getPath());
		
		openCamera(holder);
		
		//mAudioDecoder = new AudioDecoderThread();
		//mAudioDecoder.startPlay(SAMPLE);
		
		//AudioEncoder();
		
		/*
		File ff = new File(Environment.getExternalStorageDirectory(), "h264y.yuv");
		 try {
			 if(!ff.exists()){
			    f.createNewFile();
			 }
		 } catch (IOException e) {
			 e.printStackTrace();
		 }
		 
		 try {
		       h264outputStream = new BufferedOutputStream(new FileOutputStream(ff));
		 } catch (Exception e){
		      e.printStackTrace();
		 }
		 */
		 
		 /*
		 new Thread() {
	        public void run() {
					try {
						Thread.sleep(2000);
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
	     */
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
		
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		releaseCamera();
		
		//mAudioDecoder.stop();
		close();
		//X265End();
		
		//Mp4EE();
		//Mp4FfmpegEE();
		
		//Mp4FfmpegXEE();
		//Mp4FpgEE();
		
		//Mp4FpgDEE0();
		
		//if(mp4fFlag){
		 //  Mp4End();//mp4close();
		//}
		
		//AACClose();
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event)  {
	    if (keyCode == KeyEvent.KEYCODE_BACK) { //锟斤拷锟铰碉拷锟斤拷锟斤拷锟紹ACK锟斤拷同时没锟斤拷锟截革拷
	      //do something here
	      finish();   
          //System.exit(0); //锟斤拷锟角凤拷锟姐都锟斤拷示锟届常锟剿筹拷!0锟斤拷示锟斤拷锟斤拷锟剿筹拷! 
	      return true;
	    }
	    return super.onKeyDown(keyCode, event);
	} 
	
	private MediaCodec mediaCodecAAC;
	private BufferedOutputStream outputStreamAAC;
	private String mediaTypeAAC = "audio/mp4a-latm";
	private AudioRecord recorder;
	private int bufferSize;
	private boolean isRunning, tmpBufferClear, isRecording;
	private ByteBuffer tmpInputBuffer;
	
	private boolean isRunning00, isRunning11;
	
	public void AudioEncoder() {
		 File f = new File(Environment.getExternalStorageDirectory(), "audioencoded712tt.aac");
		 try {
			 if(!f.exists()){
			    f.createNewFile();
			 }
		 } catch (IOException e) {
			 e.printStackTrace();
		 }
		 
		 try {
		       outputStreamAAC = new BufferedOutputStream(new FileOutputStream(f));
		      Log.e("AudioEncoder", "outputStream initialized");
		 } catch (Exception e){
		      e.printStackTrace();
		 }
		 isRunning = false;
		 
		 /*
		 bufferSize = AudioRecord.getMinBufferSize(44100,
				 AudioFormat.CHANNEL_IN_STEREO, 
				 AudioFormat.ENCODING_PCM_16BIT);
		 Log.e("AudioEncoder", "bufferSize = "+bufferSize);
		 Log.e("AudioEncoder", "bufferSize = "+bufferSize);
		 
		 recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, 
				 44100, 
				 AudioFormat.CHANNEL_IN_STEREO,
				 AudioFormat.ENCODING_DEFAULT, 
				 bufferSize);
		 */
		 bufferSize = AudioRecord.getMinBufferSize(44100,
				 AudioFormat.CHANNEL_IN_MONO, 
				 AudioFormat.ENCODING_PCM_16BIT)*2;
		 recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, 
		    		44100,
		    		AudioFormat.CHANNEL_IN_MONO,
		    		AudioFormat.ENCODING_PCM_16BIT, 
		    		bufferSize);		   
		 
		 Log.e("AudioEncoder", "bufferSize = "+bufferSize);
		 Log.e("AudioEncoder", "bufferSize = "+bufferSize);
		 
		 try {
			mediaCodecAAC = MediaCodec.createEncoderByType(mediaTypeAAC);
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		 final int kSampleRates[] = { 8000, 11025, 22050, 44100, 48000 };
		 final int kBitRates[] = { 64000, 128000 };
		    
		 MediaFormat mediaFormat = MediaFormat.createAudioFormat(mediaTypeAAC,kSampleRates[3],1);
		 mediaFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
		 mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, kBitRates[1]);
		 mediaFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, 44100);
		 mediaFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);//2
		 mediaFormat.setString(MediaFormat.KEY_MIME, "audio/mp4a-latm");
		 mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize);
		 mediaCodecAAC.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
		 mediaCodecAAC.start();
		 
		 Log.i("Encoder", "--------------AudioEncoder--------------");
		 //doMediaRecordEncode();
		 /*
		 mediaCodecAAC = MediaCodec.createEncoderByType("audio/mp4a-latm");
		 MediaFormat format = new MediaFormat();
		 format.setString(MediaFormat.KEY_MIME, "audio/mp4a-latm");
		 format.setInteger(MediaFormat.KEY_BIT_RATE, 64 * 1024);
		 format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 2);
		 format.setInteger(MediaFormat.KEY_SAMPLE_RATE, 44100);
		 format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectHE);
		 mediaCodecAAC.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
		 mediaCodecAAC.start();
		 */
		 
		 isRecording = true;
		 recorder.startRecording();
		 new Thread() {
		        public void run() {
		            ByteBuffer byteBuffer = ByteBuffer.allocateDirect(bufferSize);
		            int read = 0;
		            while (isRecording) {
		            	try {
							Thread.sleep(10);
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
		                read = recorder.read(byteBuffer, bufferSize);
		                if(AudioRecord.ERROR_INVALID_OPERATION != read){
		                	AACEncoder(byteBuffer.array(), read);
		                }
		            }
		            recorder.stop();
		            mediaCodecAAC.stop();
		 		    mediaCodecAAC.release();
		 		    try {
						outputStreamAAC.flush();
						outputStreamAAC.close();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
		 		   
		            Log.i("Encoder", "--------------end 22--------------");
		        }
		 }.start();
	}
	
	public void AACClose() {
		try {
		   Log.i("Encoder", "--------------AACClose--------------");
		   isRecording = false;
		} catch (Exception e){
		   e.printStackTrace();
		}
    }
	
	/**
	* Add ADTS header at the beginning of each and every AAC packet.
	* This is needed as MediaCodec encoder generates a packet of raw
	* AAC data.
	*
	* Note the packetLen must count in the ADTS header itself.
	**/
	private void addADTStoPacket(byte[] packet, int packetLen) {
		int profile = 2; //AAC LC
		//39=MediaCodecInfo.CodecProfileLevel.AACObjectELD;
		int freqIdx = 4; //44.1KHz
		int chanCfg = 2; //CPE

		// fill in ADTS data
		packet[0] = (byte)0xFF;
		packet[1] = (byte)0xF9;
		packet[2] = (byte)(((profile-1)<<6) + (freqIdx<<2) +(chanCfg>>2));
		packet[3] = (byte)(((chanCfg&3)<<6) + (packetLen>>11));
		packet[4] = (byte)((packetLen&0x7FF) >> 3);
		packet[5] = (byte)(((packetLen&7)<<5) + 0x1F);
		packet[6] = (byte)0xFC;
	}
	
	int count_tt;
	// called AudioRecord's read
	public synchronized void AACEncoder(byte[] input, int length) {
	    //Log.e("AudioEncoder", input.length + " is coming");
	
	    try {
	    	ByteBuffer[] inputBuffers = mediaCodecAAC.getInputBuffers();
	 	    ByteBuffer[] outputBuffers = mediaCodecAAC.getOutputBuffers();
	 	    int inputBufferIndex = mediaCodecAAC.dequeueInputBuffer(0); //-1
	 	    if (inputBufferIndex >= 0) {
	 		    ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
	 		    inputBuffer.clear();
	 		    inputBuffer.put(input);

	 		    mediaCodecAAC.queueInputBuffer(inputBufferIndex, 0, length, 0, 0); //input.length
	 	    }
	 	    MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
	 	    int outputBufferIndex = mediaCodecAAC.dequeueOutputBuffer(bufferInfo,0);

	 	    //trying to add a ADTS
		    while (outputBufferIndex >= 0) {
		    	count_tt++;
		    	if(count_tt>=50){
		    		count_tt = 0;
		    	   Log.e("AudioEncoder", input.length + " is coming");
		    	}
		    	int outBitsSize = bufferInfo.size;
			    int outPacketSize = outBitsSize + 7; // 7 is ADTS size
			    
			    ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
			
			    outputBuffer.position(bufferInfo.offset);
			    outputBuffer.limit(bufferInfo.offset + outBitsSize);
			
			    byte[] outData = new byte[outPacketSize];
			    addADTStoPacket(outData, outPacketSize);
			
			    outputBuffer.get(outData, 7, outBitsSize);
			    outputBuffer.position(bufferInfo.offset);
			    
			    //outputStreamAAC.write(outData, 0, outData.length);
			    //Log.e("AudioEncoder", outData.length + " bytes written");
			    
			    //Log.e("AudioEncoder", "AAC = "+outData.length );
			    if(mp4fFlag){
					Mp4PackA(outData, outData.length);
				   //mp4packAudio(chunk, chunk.length);
				}
			
			    mediaCodecAAC.releaseOutputBuffer(outputBufferIndex, false);
			    outputBufferIndex = mediaCodecAAC.dequeueOutputBuffer(bufferInfo, 0);
	        }
		    
		    /*
		    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();  
	        int index = codec.dequeueOutputBuffer(info,0);
	        
			if (index >= 0) {
				int outBitsSize = info.size;
				int outPacketSize = outBitsSize + 7; // 7 is ADTS size
				ByteBuffer outBuf = codecOutputBuffers[index];

				outBuf.position(info.offset);
				outBuf.limit(info.offset + outBitsSize);
				try {
					byte[] data = new byte[outPacketSize]; //space for ADTS header included
					
					addADTStoPacket(data, outPacketSize);
					outBuf.get(data, 7, outBitsSize);
					outBuf.position(info.offset);
					
					mFileStream.write(data, 0, outPacketSize); //open FileOutputStream beforehand
				} catch (IOException e) {
				    Log.e(TAG, "failed writing bitstream data to file");
				    e.printStackTrace();
				}

				numBytesDequeued += info.size;

				outBuf.clear();
				codec.releaseOutputBuffer(index, false);// render
				Log.d(TAG, " dequeued " + outBitsSize + " bytes of output data.");
				Log.d(TAG, " wrote " + outPacketSize + " bytes into output file.");
			}
			else if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
			}
			else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
				codecOutputBuffers = codec.getOutputBuffers();
		    }
			*/

		    /*
		    //Without ADTS header
		    while (outputBufferIndex >= 0) {
			    ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
			    byte[] outData = new byte[bufferInfo.size];
			    outputBuffer.get(outData);
			    outputStreamAAC.write(outData, 0, outData.length);
			    
			    Log.e("AudioEncoder", outData.length + " bytes written");
			    mediaCodecAAC.releaseOutputBuffer(outputBufferIndex, false);
			    outputBufferIndex = mediaCodecAAC.dequeueOutputBuffer(bufferInfo, 0);
		    }
		    */
	    } catch (Throwable t) {
	        t.printStackTrace();
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
	            camera = getCamera(Camera.CameraInfo.CAMERA_FACING_BACK); // 锟斤拷锟斤拷锟斤拷锟斤拷选锟斤拷前/锟斤拷锟斤拷锟斤拷锟斤拷头
	        } catch (Exception e) {
	            camera = null;
	            e.printStackTrace();
	        }
	    if(camera != null){
	    	/*
	        try {
				camera.setPreviewDisplay(holder);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			*/
			camera.setDisplayOrientation(90); // 锟剿凤拷锟斤拷为锟劫凤拷锟结供锟斤拷锟斤拷转锟斤拷示锟斤拷锟街的凤拷锟斤拷锟斤拷锟斤拷锟斤拷锟斤拷影锟斤拷onPreviewFrame锟斤拷锟斤拷锟叫碉拷原始锟斤拷锟捷ｏ拷
			
			Camera.Parameters parameters = camera.getParameters();
			
			parameters.setPreviewSize(width, height); // 锟斤拷锟斤拷锟斤拷锟斤拷锟矫很讹拷锟斤拷锟斤拷牟锟斤拷锟斤拷锟斤拷锟斤拷墙锟斤拷锟斤拷缺锟斤拷锟斤拷锟角帮拷锟斤拷锟角凤拷支锟街革拷锟斤拷锟矫ｏ拷锟斤拷然锟斤拷锟杰会导锟铰筹拷锟斤拷
			//parameters.getSupportedPreviewSizes();
			parameters.setFlashMode("off"); // 锟斤拷锟斤拷锟斤拷锟�  
			parameters.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO); 
			parameters.setPreviewFormat(ImageFormat.YV12); // 锟斤拷锟矫革拷式锟斤拷NV21 / YV12
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
			
			//parameters.setPreviewFpsRange(10000, 30000);    
			parameters.setPreviewFpsRange(10000, 30000);
			//parameters.setPreviewFpsRange(4000,60000);//this one results fast playback when I use the FRONT CAMERA 
			
			camera.startPreview();
			
			Log.i("Encoder", "width = "+width+", height = "+height);
			
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
    
    //byte[]
    public static void YV12toYUV420PackedSemiPlanar_00(byte[] input, byte[] output, int width, int height) {
        /* 
         * COLOR_TI_FormatYUV420PackedSemiPlanar is NV12
         * We convert by putting the corresponding U and V bytes together (interleaved).
         */
        final int frameSize = width * height;
        final int qFrameSize = frameSize/4;

        System.arraycopy(input, 0, output, 0, frameSize); // Y

        for (int i = 0; i < qFrameSize; i++) {
            output[frameSize + i*2] = input[frameSize + i + qFrameSize]; // Cb (U)
            output[frameSize + i*2 + 1] = input[frameSize + i]; // Cr (V)
        }
        
        /*
        //When set the resolution to 320x240, your color transform should looks like:
        System.arraycopy(input, 0, output, 0, frameSize);
        for (int i = 0; i < (qFrameSize); i++) {  
            output[frameSize + i*2] = (input[frameSize + qFrameSize + i - 32 - 320]);  
            output[frameSize + i*2 + 1] = (input[frameSize + i - 32 - 320]);            
        }
        
        //for resolution 640x480 and above
        System.arraycopy(input, 0, output, 0, frameSize);    
        for (int i = 0; i < (qFrameSize); i++) {  
            output[frameSize + i*2] = (input[frameSize + qFrameSize + i]);  
            output[frameSize + i*2 + 1] = (input[frameSize + i]);   
        } 
        */
        
        //return output;
    }

    public static byte[] YV12toYUV420Planar_00(byte[] input, byte[] output, int width, int height) {
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
		
		File f = new File(Environment.getExternalStorageDirectory(), "mediacodec_r0.264");
	    if(!f.exists()){	
	    	try {
				f.createNewFile();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
	    }
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
		mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
		//mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, 
		//		MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);  //COLOR_FormatYUV420Planar
		mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5); //锟截硷拷帧锟斤拷锟绞憋拷锟� 锟斤拷位s  
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
	        outputStream.flush();
	        outputStream.close();
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
    
    public int offerEncoder(byte[] input, byte[] output)   
    {     
        int pos = 0;
        keyFrame = 0;
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
                  
                else //锟斤拷锟斤拷pps sps 只锟叫匡拷始时 锟斤拷一锟斤拷帧锟斤拷锟叫ｏ拷 锟斤拷锟斤拷锟斤拷锟斤拷锟斤拷锟斤拷锟斤拷  
                {  
                     ByteBuffer spsPpsBuffer = ByteBuffer.wrap(outData);    
                     if (spsPpsBuffer.getInt() == 0x00000001)   
                     {   
                    	 Log.i("Encoder", "--------- pps sps found = "+outData.length+"---------");
                         m_info = new byte[outData.length];  
                         System.arraycopy(outData, 0, m_info, 0, outData.length); 
                         
                         int length = outData.length;
                         for (int ix = 0; ix < length; ++ix) {
                 			System.out.printf("%02x ", outData[ix]);
                 		 }
                 		 System.out.println("\n----------");
                 		 pos += outData.length;  
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
  
            if(output[4] == 0x65) //key frame   锟斤拷锟斤拷锟斤拷锟斤拷锟缴关硷拷帧时只锟斤拷 00 00 00 01 65 没锟斤拷pps sps锟斤拷 要锟斤拷锟斤拷  
            {  
            	Log.i("Encoder", "-----------idr frame: "+output[4]+"-----------");
                System.arraycopy(output, 0,  yuv420, 0, pos);  
                System.arraycopy(m_info, 0,  output, 0, m_info.length);  
                System.arraycopy(yuv420, 0,  output, m_info.length, pos);  
                pos += m_info.length;  
                
                keyFrame = 1;
            }
            
              
        } catch (Throwable t) {  
            t.printStackTrace();  
        }  
  
        return pos;  
    }  
   
    int typeID  = 1;
    boolean typeFlag = true;
    byte[] outData0 = new byte[12]; 
	byte[] outData1 = new byte[8]; 
    byte[] outData2 = new byte[13]; 
	byte[] outData3 = new byte[8]; 
	   
	// encode
	public void onFrame(byte[] buf, int offset, int length, int flag) {	
		 
		    //YV12toYUV420PackedSemiPlanar_00(buf, h264, width, height);
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
 	 		       //Log.i("Encoder", "--------------onFrame--------------");
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
	            //Log.i("Encoder", "onFrame   outData.length = "+outData.length);
	            if(outData.length==20 || outData.length==21){
	               int len = outData.length;
	               Log.i("Encoder", "onFrame   outData.length = "+outData.length);
	               for (int ix = 0; ix < len; ++ix) {
             		  System.out.printf("%02x ", outData[ix]);
             		  //10-11 09:11:36.670: I/System.out(24316): 00 00 00 01 67 42 80 0c e9 02 83 f2 00 00 00 01 68 ce 06 e2 
             	   }
             	   System.out.println("\n----------");
	            }
	            */
	            
	            keyFrame = 0;
	            mp4fFlag = true;
	            if(mp4fFlag){
	            	  if(outData.length==20 || outData.length==21){ //21
		    	    	   Log.i("Encoder", " pps sps set = "+outData.length);
		    	    	   //int length = outData.length;
		    	    	   int len = outData.length;
	                       for (int ix = 0; ix < len; ++ix) {
	                 			System.out.printf("%02x ", outData[ix]);
	                 	   }
	                 	   System.out.println("\n----------");
                            //00 00 00 01 67 42 80 1e e9 01 40 7b 20 00 00 00 01 68 ce 06 e2 
	                 		 
		    	    	   //byte[] outData0 = new byte[13]; 
		    	    	   //byte[] outData1 = new byte[8]; 
	                 	   //System.arraycopy(outData, 0,  outData0, 0, 13);  
		                   //System.arraycopy(outData, 13, outData1, 0, 8); 
	                 	   Log.i("Encoder", "     onFrame  pps sps     ");
	                 	  
	                 	   if(len==20){
	                 		   
	                 		   System.arraycopy(outData, 0,  outData0, 0, 12);  
			                   System.arraycopy(outData, 12, outData1, 0, 8); 
				               //mp4packVideo(outData0, 13, keyFrame);
				               //mp4packVideo(outData1, 8, keyFrame); 
			                   if(btn_id == 1 && btn_id0==1){
			                	  Log.i("Encoder", "onFrame   Mp4PackV");
			                      Mp4PackV(outData0, 12, keyFrame);
			                      Mp4PackV(outData1, 8, keyFrame);
			                      typeFlag = false;
			                   }else if(btn_id == 8 && btn_id0==8){
			                	  Log.i("Encoder", "onFrame   Mp4FpgDCE0");
			                      Mp4FpgDCE0(outData0, 12);
			                      Mp4FpgDCE0(outData1, 8);
			                      typeFlag = false;
			                   }
			                   typeID = 1;
			                   
	                 	   }else if(len==21){
	                 		  
	                 		   System.arraycopy(outData, 0,  outData2, 0, 13);  
			                   System.arraycopy(outData, 13, outData3, 0, 8); 
				               //mp4packVideo(outData0, 13, keyFrame);
				               //mp4packVideo(outData1, 8, keyFrame); 
			                   if(btn_id == 1 && btn_id0==1){
			                	  Log.i("Encoder", "onFrame   Mp4PackV");
			                      Mp4PackV(outData2, 13, keyFrame);
			                      Mp4PackV(outData3, 8, keyFrame);
			                      typeFlag = false;
			                   }else if(btn_id == 8 && btn_id0==8){
			                	  Log.i("Encoder", "onFrame   Mp4FpgDCE0");
			                      Mp4FpgDCE0(outData2, 13);
			                      Mp4FpgDCE0(outData3, 8);
			                      typeFlag = false;
			                   }
			                   typeID = 2;
	                 	   }
		    	      }else{
		    	    	   if(outData[4] == 0x65) //key frame   锟斤拷锟斤拷锟斤拷锟斤拷锟缴关硷拷帧时只锟斤拷 00 00 00 01 65 没锟斤拷pps sps锟斤拷 要锟斤拷锟斤拷  
		    	           { 
		    	    		   keyFrame = 1;
		    	    		   //Log.i("Encoder", "--------- key frame---------");
		    	           }
		    	    	   if(btn_id == 1 && btn_id0==1){
		    	    		  if(typeFlag){
		    	    			  if(typeID==1){
		    	    				  Log.i("Encoder", "Mp4PackV 00 onFrame   Mp4PackV");
				                      Mp4PackV(outData0, 12, keyFrame);
				                      Mp4PackV(outData1, 8, keyFrame);
				                      typeFlag = false; 
		    	    			  }else if(typeID==2){
		    	    				  Log.i("Encoder", "Mp4PackV 11 onFrame   Mp4PackV");
				                      Mp4PackV(outData2, 13, keyFrame);
				                      Mp4PackV(outData3, 8, keyFrame);
				                      typeFlag = false;
		    	    			  }
		    	    		  }
		    	    	      Mp4PackV(outData, outData.length, keyFrame);
		    	    	   }else if(btn_id == 8 && btn_id0==8){
		    	    		  if(typeFlag){
			    	    		  if(typeID==1){
			    	    			  Log.i("Encoder", "Mp4FpgDCE0 00 onFrame   Mp4FpgDCE0");
				                      Mp4FpgDCE0(outData0, 12);
				                      Mp4FpgDCE0(outData1, 8);
				                      typeFlag = false;
			    	    		  }else if(typeID==2){
			    	    			  Log.i("Encoder", "Mp4FpgDCE0 11 onFrame   Mp4FpgDCE0");
				                      Mp4FpgDCE0(outData2, 13);
				                      Mp4FpgDCE0(outData3, 8);
				                      typeFlag = false;
			    	    		  }
		    	    		  }
		    	    	      Mp4FpgDCE0(outData, outData.length);
		    	    	   }
	                       //mp4packVideo(outData, outData.length, keyFrame);
		    	      }
	            }
	            /*
	            try {
					outputStream.write(outData, 0, outData.length);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				*/ 
				// write into h264 file
	            //Log.i("Encoder", outData.length + " bytes written");
                
	            
		        //if (frameListener != null)//取压锟斤拷锟矫碉拷锟斤拷锟斤拷喂锟斤拷锟斤拷锟斤拷锟斤拷
		        //    frameListener.onFrame(outputBuffer, 0, length, flag);
		        
	            //onFrame0(outData, 0, outData.length, flag);
	            
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
	           mediaCodecd.releaseOutputBuffer(outputBufferIndex, true);  
	           outputBufferIndex = mediaCodecd.dequeueOutputBuffer(bufferInfo, 0);  
	       }  
	}
	
	boolean firstFlag = true;
	int yuv_size = width*height*3/2;
			
	@Override
	public void onPreviewFrame(byte[] data, Camera camera) {
		// TODO Auto-generated method stub
		 //if (frameListener != null) {  
		 //       frameListener.onFrame(data, 0, data.length, 0);  
		 //} 
		 /*
		 int ret = offerEncoder(data,h264);  
         
	     if(ret > 0)  
	     {   	 
	    	 //try {
	    	    if(mp4fFlag){
	    	       if(firstFlag){
	    	    	  firstFlag = false;
	    	    	  Log.i("Encoder", "first frame = "+ret);
	    	       }
	    	       if(ret==21){
	    	    	   Log.i("Encoder", "--------- pps sps set---------");
	    	    	   byte[] outData0 = new byte[13]; 
	    	    	   byte[] outData1 = new byte[8]; 
	                   System.arraycopy(h264, 0,  outData0, 0, 13);  
	                   System.arraycopy(h264, 13, outData1, 0, 8); 
		               mp4packVideo(outData0, 13, keyFrame);
		               mp4packVideo(outData1, 8, keyFrame); 
	    	       }else{
	    	    	   mp4packVideo(h264, ret, keyFrame); 
	    	       }
		        }
	    	    
	    	    
				//outputStream.write(h264, 0, ret);
			 } catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			 } // write into h264 file
	         //Log.i("Encoder", ret + " bytes written");
	          
	     }
	     */
		 swapYV12toI420(data, h264, width, height); 
		 //h264outputStream.write(h264, 0, yuv_size);
		 
		 if(btn_id == 1 && btn_id0==1){
			 isPlaying = false;
			 onFrame(data, 0, data.length, 0); 
		 }else if(btn_id == 2 && btn_id0==2){
			 Mp4CE(h264, 0);
         }else if(btn_id == 3 && btn_id0==3){
        	 X265Enc(h264, 0);
		 }else if(btn_id == 4 && btn_id0==4){
			 Mp4FfmpegCE(h264, 0);
         }else if(btn_id == 5 && btn_id0==5){
        	 Mp4FfmpegXCE(h264, 0);
		 }else if(btn_id == 6 && btn_id0==6){
			 Mp4FpgCE(h264,0);
         }else if(btn_id == 8 && btn_id0==8){
        	 isPlaying = false;
        	 onFrame(data, 0, data.length, 0); 
         }else{
        	 if(isPlaying){
        		 onFrame(data, 0, data.length, 0); 
        	 }
         }
	     //Mp4CE(h264, 0);
	     //Mp4FfmpegCE(h264, 0);
	     //Mp4FfmpegXCE(h264, 0);
	     //Mp4FpgCE(h264,0);
	     
		 //onFrame(data, 0, data.length, 0); 
		 
	     //X265Enc(h264, 0);
	     
		 camera.addCallbackBuffer(buf);	
		 //Log.i("Encoder", "--------------------onPreviewFrame-----------------"+data.length);
	}
	
	public class AudioDecoderThread {
		private static final int TIMEOUT_US = 1000;
		private MediaExtractor mExtractor;
		private MediaCodec mDecoder;
		
		private boolean eosReceived;
		private int mSampleRate = 0;
		
		private BufferedOutputStream outputStreamAACp;
		
		/**
		 * 
		 * @param filePath
		 */
		public void startPlay(String path) {
			eosReceived = false;
			mExtractor = new MediaExtractor();
			try {
				mExtractor.setDataSource(path);
			} catch (IOException e2) {
				// TODO Auto-generated catch block
				e2.printStackTrace();
			}

			int channel = 0;
			for (int i = 0; i < mExtractor.getTrackCount(); i++) {
				MediaFormat format = mExtractor.getTrackFormat(i);
				String mime = format.getString(MediaFormat.KEY_MIME);
				if (mime.startsWith("audio/")) {
					mExtractor.selectTrack(i);
					Log.d("TAG", "format : " + format);
					ByteBuffer csd = format.getByteBuffer("csd-0");
					
					if(csd != null){
					for (int k = 0; k < csd.capacity(); ++k) {
						Log.e("TAG", "00 csd : " + csd.array()[k]);
					}
					}
					mSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
					channel = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
					break;
				}
			}
			//MediaCodecInfo.CodecProfileLevel.AACObjectLC  AACObjectHE
			MediaFormat format = makeAACCodecSpecificData(MediaCodecInfo.CodecProfileLevel.AACObjectLC, 
					mSampleRate, 
					channel);
			if (format == null)
				return;
			
			try {
				mDecoder = MediaCodec.createDecoderByType("audio/mp4a-latm");
			} catch (IOException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
			mDecoder.configure(format, null, null, 0);

			if (mDecoder == null) {
				Log.e("DecodeActivity", "Can't find video info!");
				return;
			}

			 File f = new File(Environment.getExternalStorageDirectory(), "aacpcm.pcm");
			 try {
				 if(!f.exists()){
				    f.createNewFile();
				 }
			 } catch (IOException e) {
				 e.printStackTrace();
			 }
			 
			 try {
			       outputStreamAACp = new BufferedOutputStream(new FileOutputStream(f));
			      Log.e("AudioEncoder", "outputStream initialized");
			 } catch (Exception e){
			      e.printStackTrace();
			 }
			 
			mDecoder.start();
		
			new Thread(AACDecoderAndPlayRunnable).start();
		}
		
		/**
		 * The code profile, Sample rate, channel Count is used to
		 * produce the AAC Codec SpecificData.
		 * Android 4.4.2/frameworks/av/media/libstagefright/avc_utils.cpp refer
		 * to the portion of the code written.
		 * 
		 * MPEG-4 Audio refer : http://wiki.multimedia.cx/index.php?title=MPEG-4_Audio#Audio_Specific_Config
		 * 
		 * @param audioProfile is MPEG-4 Audio Object Types
		 * @param sampleRate
		 * @param channelConfig
		 * @return MediaFormat
		 */
		private MediaFormat makeAACCodecSpecificData(int audioProfile, int sampleRate, int channelConfig) {
			MediaFormat format = new MediaFormat();
			format.setString(MediaFormat.KEY_MIME, "audio/mp4a-latm");
			format.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate);
			format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, channelConfig);
			
		    int samplingFreq[] = {
		        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
		        16000, 12000, 11025, 8000
		    };
		    
		    // Search the Sampling Frequencies
		    int sampleIndex = -1;
		    for (int i = 0; i < samplingFreq.length; ++i) {
		    	if (samplingFreq[i] == sampleRate) {
		    		Log.d("TAG", "kSamplingFreq " + samplingFreq[i] + " i : " + i+" , channel = "+channelConfig);
		    		sampleIndex = i;
		    	}
		    }
		    
		    if (sampleIndex == -1) {
		    	return null;
		    }
		    
			ByteBuffer csd = ByteBuffer.allocate(2);
			csd.put((byte) ((audioProfile << 3) | (sampleIndex >> 1)));
			
			csd.position(1);
			csd.put((byte) ((byte) ((sampleIndex << 7) & 0x80) | (channelConfig << 3)));
			csd.flip();
			format.setByteBuffer("csd-0", csd); // add csd-0
			
			for (int k = 0; k < csd.capacity(); ++k) {
				Log.e("TAG", "11 csd : " + csd.array()[k]);
			}
			
			return format;
		}
		
		Runnable AACDecoderAndPlayRunnable = new Runnable() {
			
			@Override
			public void run() {
				AACDecoderAndPlay();
			}
		};

		/**
		 * After decoding AAC, Play using Audio Track.
		 */
		public void AACDecoderAndPlay() {
			ByteBuffer[] inputBuffers = mDecoder.getInputBuffers();
			ByteBuffer[] outputBuffers = mDecoder.getOutputBuffers();
			
			BufferInfo info = new BufferInfo();
			
			int buffsize = AudioTrack.getMinBufferSize(mSampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
	        // create an audiotrack object
			AudioTrack audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, mSampleRate,
	                AudioFormat.CHANNEL_OUT_STEREO,
	                AudioFormat.ENCODING_PCM_16BIT,
	                buffsize,
	                AudioTrack.MODE_STREAM);
			audioTrack.play();
			
			while (!eosReceived) {
				int inIndex = mDecoder.dequeueInputBuffer(TIMEOUT_US);
				if (inIndex >= 0) {
					ByteBuffer buffer = inputBuffers[inIndex];
					int sampleSize = mExtractor.readSampleData(buffer, 0);
					if (sampleSize < 0) {
						// We shouldn't stop the playback at this point, just pass the EOS
						// flag to mDecoder, we will get it again from the
						// dequeueOutputBuffer
						Log.d("DecodeActivity", "InputBuffer BUFFER_FLAG_END_OF_STREAM");
						mDecoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
						
					} else {
						mDecoder.queueInputBuffer(inIndex, 0, sampleSize, mExtractor.getSampleTime(), 0);
						mExtractor.advance();
					}
					
					int outIndex = mDecoder.dequeueOutputBuffer(info, TIMEOUT_US);
					switch (outIndex) {
					case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
						Log.d("DecodeActivity", "INFO_OUTPUT_BUFFERS_CHANGED");
						outputBuffers = mDecoder.getOutputBuffers();
						break;
						
					case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
						MediaFormat format = mDecoder.getOutputFormat();
						Log.d("DecodeActivity", "New format " + format);
						audioTrack.setPlaybackRate(format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
						
						break;
					case MediaCodec.INFO_TRY_AGAIN_LATER:
						Log.d("DecodeActivity", "dequeueOutputBuffer timed out!");
						break;
						
					default:
						ByteBuffer outBuffer = outputBuffers[outIndex];
						//Log.v("DecodeActivity", "We can't use this buffer but render it due to the API limit, " + outBuffer);
						
						final byte[] chunk = new byte[info.size];
						outBuffer.get(chunk); // Read the buffer all at once
						outBuffer.clear(); // ** MUST DO!!! OTHERWISE THE NEXT TIME YOU GET THIS SAME BUFFER BAD THINGS WILL HAPPEN
						
						/*
						try {
	                    	outputStreamAACp.write(chunk, 0, chunk.length);
	                    } 
	                    catch (IOException e) {
	                        e.printStackTrace();
	                    }
						*/
						if(mp4fFlag){
							Mp4PackA(chunk, chunk.length);
						   //mp4packAudio(chunk, chunk.length);
						}
						 
						audioTrack.write(chunk, info.offset, info.offset + info.size); // AudioTrack write data
						mDecoder.releaseOutputBuffer(outIndex, false);
						break;
					}
					
					// All decoded frames have been rendered, we can stop playing now
					if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
						Log.d("DecodeActivity", "OutputBuffer BUFFER_FLAG_END_OF_STREAM");
						
						try {
							outputStreamAACp.flush();
							outputStreamAACp.close();
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
						
						break;
					}
				}
			}
			
			mDecoder.stop();
			mDecoder.release();
			mDecoder = null;
			
			mExtractor.release();
			mExtractor = null;
			
			audioTrack.stop();
			audioTrack.release();
			audioTrack = null;
		}
		
		public void stop() {
			eosReceived = true;
		}
	}
	
	
	public void getSPSAndPPS(String fileName) throws IOException {
		File file = new File(fileName);
		FileInputStream fis = new FileInputStream(file);

		int fileLength = (int) file.length();
		byte[] fileData = new byte[fileLength];
		fis.read(fileData);

		// 'a'=0x61, 'v'=0x76, 'c'=0x63, 'C'=0x43
		byte[] avcC = new byte[] { 0x61, 0x76, 0x63, 0x43 };

		// avcC锟斤拷锟斤拷始位锟斤拷
		int avcRecord = 0;
		for (int ix = 0; ix < fileLength; ++ix) {
			if (fileData[ix] == avcC[0] && fileData[ix + 1] == avcC[1]
					&& fileData[ix + 2] == avcC[2]
					&& fileData[ix + 3] == avcC[3]) {
				// 锟揭碉拷avcC锟斤拷锟斤拷锟铰糰vcRecord锟斤拷始位锟矫ｏ拷然锟斤拷锟剿筹拷循锟斤拷锟斤拷
				avcRecord = ix + 4;
				break;
			}
		}
		if (0 == avcRecord) {
			System.out.println("没锟斤拷锟揭碉拷avcC锟斤拷锟斤拷锟斤拷锟侥硷拷锟斤拷式锟角凤拷锟斤拷确");
			return;
		}

		// 锟斤拷7锟斤拷目锟斤拷锟斤拷为锟斤拷锟斤拷锟斤拷
		// (1)8锟街节碉拷 configurationVersion
		// (2)8锟街节碉拷 AVCProfileIndication
		// (3)8锟街节碉拷 profile_compatibility
		// (4)8 锟街节碉拷 AVCLevelIndication
		// (5)6 bit 锟斤拷 reserved
		// (6)2 bit 锟斤拷 lengthSizeMinusOne
		// (7)3 bit 锟斤拷 reserved
		// (8)5 bit 锟斤拷numOfSequenceParameterSets
		// 锟斤拷6锟斤拷锟街节ｏ拷然锟襟到达拷sequenceParameterSetLength锟斤拷位锟斤拷
		int spsStartPos = avcRecord + 6;
		byte[] spsbt = new byte[] { fileData[spsStartPos],
				fileData[spsStartPos + 1] };
		int spsLength = bytes2Int(spsbt);
		byte[] SPS = new byte[spsLength];
		// 锟斤拷锟斤拷2锟斤拷锟街节碉拷 sequenceParameterSetLength
		spsStartPos += 2;
		System.arraycopy(fileData, spsStartPos, SPS, 0, spsLength);
		printResult("SPS", SPS, spsLength);

		// 锟斤拷锟铰诧拷锟斤拷为锟斤拷取PPS
		// spsStartPos + spsLength 锟斤拷锟斤拷锟斤拷锟斤拷pps位锟斤拷
		// 锟劫硷拷1锟斤拷目锟斤拷锟斤拷锟斤拷锟斤拷1锟街节碉拷 numOfPictureParameterSets
		int ppsStartPos = spsStartPos + spsLength + 1;
		byte[] ppsbt = new byte[] { fileData[ppsStartPos],
				fileData[ppsStartPos + 1] };
		int ppsLength = bytes2Int(ppsbt);
		byte[] PPS = new byte[ppsLength];
		ppsStartPos += 2;
		System.arraycopy(fileData, ppsStartPos, PPS, 0, ppsLength);
		printResult("PPS", PPS, ppsLength);
	}

	private int bytes2Int(byte[] bt) {
		int ret = bt[0];
		ret <<= 8;
		ret |= bt[1];
		return ret;
	}

	private void printResult(String type, byte[] bt, int len) {
		System.out.println(type + "锟斤拷锟斤拷为锟斤拷" + len);
		String cont = type + "锟斤拷锟斤拷锟斤拷为锟斤拷";
		System.out.print(cont);
		for (int ix = 0; ix < len; ++ix) {
			System.out.printf("%02x ", bt[ix]);
		}
		System.out.println("\n----------");
	}
	
	
}
