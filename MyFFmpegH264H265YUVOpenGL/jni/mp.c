#include <stdio.h>
#include <jni.h>
#include <malloc.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <string.h>
#include "mp4v2/mp4v2.h"

#include "x264.h"
#include "x264_config.h"

#include <libavcodec/avcodec.h>
#include <libavutil/avassert.h>
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>
#include <libavutil/mathematics.h>
#include <libavutil/timestamp.h>
#include <libavformat/avformat.h>
#include <libswscale/swscale.h>
#include <libswresample/swresample.h>

#define LOG_TAG "libmp4"
#define LOGD(...) ((void)__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__))
#define LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__))
#define LOGW(...) ((void)__android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__))

typedef struct _MP4ENC_NaluUnit
{
   int type;
   int size;
   unsigned char *data;
}MP4ENC_NaluUnit;

int m_nWidth;
int m_nHeight;
int m_nFrameRate;
int m_nTimeScale;
MP4TrackId m_videoId;

#define BUFFER_SIZE				(50*1024)
#define MP4_DETAILS_ALL		    0xFFFFFFFF

// X264
FILE *h264s_file;
int  width = 640;
int  height = 480;
int  fps = 25;
long yuv_size;
int  inf;
int  outf;
uint8_t *yuv_buffer;

int64_t i_pts = 0;
x264_nal_t *nals;
int nnal;

x264_t *encoder;
x264_param_t   m_param;
x264_picture_t pic_in;
x264_picture_t pic_out;

// FFMPEG
AVFormatContext* pFormatCtx;
AVOutputFormat* fmt;
AVStream* video_st0;
AVCodecContext* pCodecCtx;
AVCodec* pCodec;
AVPacket pkt;
uint8_t* picture_buf;
AVFrame* pFrame;
int picture_size;
int y_size;
int framecnt=0;
int in_w=640;
int in_h=480;
int framenum=100;

/**
 * 返回值 char* 这个代表char数组的首地址
 *  Jstring2CStr 把java中的jstring的类型转化成一个c语言中的char 字符串
 */
char* Jstring2CStr(JNIEnv* env, jstring jstr) {
	char* rtn = NULL;
	jclass clsstring = (*env)->FindClass(env, "java/lang/String"); //String
	jstring strencode = (*env)->NewStringUTF(env, "GB2312"); // 得到一个java字符串 "GB2312"
	jmethodID mid = (*env)->GetMethodID(env, clsstring, "getBytes",
			"(Ljava/lang/String;)[B"); //[ String.getBytes("gb2312");
	jbyteArray barr = (jbyteArray)(*env)->CallObjectMethod(env, jstr, mid,
			strencode); // String .getByte("GB2312");
	jsize alen = (*env)->GetArrayLength(env, barr); // byte数组的长度
	jbyte* ba = (*env)->GetByteArrayElements(env, barr, JNI_FALSE);
	if (alen > 0) {
		rtn = (char*) malloc(alen + 1); //"\0"
		memcpy(rtn, ba, alen);
		rtn[alen] = 0;
	}
	(*env)->ReleaseByteArrayElements(env, barr, ba, 0); //
	return rtn;
}

MP4TrackId video;
MP4TrackId audio;
MP4FileHandle fileHandle;
unsigned char sps_pps[17] = {0x67, 0x42, 0x40, 0x1F, 0x96 ,0x54, 0x05,
		0x01, 0xED, 0x00, 0xF3, 0x9E, 0xA0, 0x68, 0xCE, 0x38, 0x80}; //存储sps和pps

unsigned char sps[] = {0x67, 0x42, 0x80, 0x1e, 0xe9, 0x01, 0x40, 0x7b, 0x20};
unsigned char pps[] = {0x68, 0xce, 0x06, 0xe2};

int video_width = 640;
int video_height = 480;
int video_type;

int m_nVideoFrameRate=20;
int	m_nMp4TimeScale=90000;
MP4Timestamp m_lastTime=0;
MP4Timestamp m_thisTime=0;
MP4SampleId  m_samplesWritten=0;

#define NUM_ADTS_SAMPLING_RATES	16

uint32_t AdtsSamplingRates[NUM_ADTS_SAMPLING_RATES] = {
	96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
	16000, 12000, 11025, 8000, 7350, 0, 0, 0
};

uint8_t MP4AdtsFindSamplingRateIndex(uint32_t samplingRate)
{
	uint8_t i;
	for(i = 0; i < NUM_ADTS_SAMPLING_RATES; i++) {
		if (samplingRate == AdtsSamplingRates[i]) {
			return i;
		}
	}
	return NUM_ADTS_SAMPLING_RATES - 1;
}


bool MP4AacGetConfiguration(uint8_t** ppConfig,
					     uint32_t* pConfigLength,
					     uint8_t profile,
						 uint32_t samplingRate,
						 uint8_t channels)
{
	/* create the appropriate decoder config */

	uint8_t* pConfig = (uint8_t*)malloc(2);

	if (pConfig == NULL) {
		return false;
	}

	uint8_t samplingRateIndex = MP4AdtsFindSamplingRateIndex(samplingRate);

	pConfig[0] = ((profile + 1) << 3) | ((samplingRateIndex & 0xe) >> 1);
	pConfig[1] = ((samplingRateIndex & 0x1) << 7) | (channels << 3);

	/* LATER this option is not currently used in MPEG4IP
	 if (samplesPerFrame == 960) {
	 pConfig[1] |= (1 << 2);
	 }
	 */

	*ppConfig = pConfig;
	*pConfigLength = 2;

	return true;
}

int ReadOneNaluFromBuf(const unsigned char *buffer,
		unsigned int nBufferSize,
		unsigned int offSet,
		MP4ENC_NaluUnit *nalu)
{
	int i = offSet;
	while(i<nBufferSize)
	{
		if(buffer[i++] == 0x00 &&
			buffer[i++] == 0x00 &&
			buffer[i++] == 0x00 &&
			buffer[i++] == 0x01
			)
		{
			int pos = i;
			while (pos<nBufferSize)
			{
				if(buffer[pos++] == 0x00 &&
					buffer[pos++] == 0x00 &&
					buffer[pos++] == 0x00 &&
					buffer[pos++] == 0x01
					)
				{
					break;
				}
			}
			if(pos == nBufferSize)
			{
				nalu->size = pos-i;
			}
			else
			{
				nalu->size = (pos-4)-i;
			}

			nalu->type = buffer[i]&0x1f;
			nalu->data =(unsigned char*)&buffer[i];
			LOGI("     nalu type = %d, size = %d    ", nalu->type, nalu->size);
			return (nalu->size+i-offSet);
		}
	}
	return 0;
}

int WriteH264Data(MP4FileHandle hMp4File,const unsigned char* pData,int size)
{
	if(hMp4File == NULL)
	{
		return -1;
	}
	if(pData == NULL)
	{
		return -1;
	}
	MP4ENC_NaluUnit nalu;
	int pos = 0, len = 0;
	while (len = ReadOneNaluFromBuf(pData, size, pos, &nalu))
	{
		if(nalu.type == 0x07) // sps
		{
			// track
			m_videoId = MP4AddH264VideoTrack
				(hMp4File,
				m_nTimeScale,
				m_nTimeScale / m_nFrameRate,
				m_nWidth,     // width
				m_nHeight,    // height
				nalu.data[1], // sps[1] AVCProfileIndication
				nalu.data[2], // sps[2] profile_compat
				nalu.data[3], // sps[3] AVCLevelIndication
				3);           // 4 bytes length before each NAL unit
			if (m_videoId == MP4_INVALID_TRACK_ID)
			{
				printf("add video track failed.\n");
				//MP4Close(mMp4File, 0);
				return 0;
			}
			MP4SetVideoProfileLevel(hMp4File, 1); //  Simple Profile @ Level 3

			MP4AddH264SequenceParameterSet(hMp4File,m_videoId,nalu.data,nalu.size);
			LOGI("              write sps                ");
		}
		else if(nalu.type == 0x08) // pps
		{
			MP4AddH264PictureParameterSet(hMp4File,m_videoId,nalu.data,nalu.size);
			LOGI("              write pps                ");
		}
		else
		{
			int datalen = nalu.size+4;
			unsigned char data[datalen];
			// MP4 Nalu
			data[0] = nalu.size>>24;
			data[1] = nalu.size>>16;
			data[2] = nalu.size>>8;
			data[3] = nalu.size&0xff;
			memcpy(data+4, nalu.data, nalu.size);
			if(!MP4WriteSample(hMp4File, m_videoId, data, datalen,MP4_INVALID_DURATION, 0, 1))
			{
				LOGI("              MP4_INVALID_TRACK_ID = %d               ",m_samplesWritten);
				// MP4DeleteTrack(mMp4File, video);
				return 0;
			}
		}

		pos += len;
	}
	return pos;
}

MP4FileHandle CreateMP4File(const char *pFileName,int width,int height)//int timeScale/* = 90000*/, int frameRate/* = 25*/)
{
	if(pFileName == NULL)
	{
		return false;
	}
	// create mp4 file
	MP4FileHandle hMp4file = MP4Create(pFileName, 0);
	if (hMp4file == MP4_INVALID_FILE_HANDLE)
	{
		//printf("ERROR:Open file fialed.\n");
		LOGI("              MP4_INVALID_FILE_HANDLE                ");
		return false;
	}
	m_nWidth = width;
	m_nHeight = height;
	m_nTimeScale = 90000;
	m_nFrameRate = 15;
	MP4SetTimeScale(hMp4file, m_nTimeScale);
	return hMp4file;
}

void CloseMP4File(MP4FileHandle hMp4File)
{
	if(hMp4File)
	{
		MP4Close(hMp4File,0);
		hMp4File = NULL;
	}
}

bool WriteH264File(const char* pFile264,const char* pFileMp4)
{
	if(pFile264 == NULL || pFileMp4 == NULL)
	{
		return false;
	}

	MP4FileHandle hMp4File = CreateMP4File(pFileMp4, 640, 480);//240,320);

	if(hMp4File == NULL)
	{
		//printf("ERROR:Create file failed!");
		LOGI("              MP4_INVALID_FILE_HANDLE                ");
		return false;
	}

	FILE *fp = fopen(pFile264, "rb");
	if(!fp)
	{
		//printf("ERROR:open file failed!");
		LOGI("              h264 fopen error                ");
		return false;
	}
	LOGI("              h264 fopen                 ");

	fseek(fp, 0, SEEK_SET);

	unsigned char buffer[BUFFER_SIZE];
	int pos = 0;
	LOGI("       mp4Encoder start %s      ",pFile264);
	while(1)
	{
		int readlen = fread(buffer+pos, sizeof(unsigned char), BUFFER_SIZE-pos, fp);
		if(readlen<=0)
		{
			break;
		}
		readlen += pos;

		int writelen = 0;
		int i;
		for(i = readlen-1; i>=0; i--)
		{
			if(buffer[i--] == 0x01 &&
				buffer[i--] == 0x00 &&
				buffer[i--] == 0x00 &&
				buffer[i--] == 0x00
				)
			{
				writelen = i+5;
				break;
			}
		}
		LOGI("          mp4Encoder writelen = %d     ",writelen);
		writelen = WriteH264Data(hMp4File,buffer,writelen);
		if(writelen<=0)
		{
			break;
		}
		memcpy(buffer,buffer+writelen,readlen-writelen+1);
		pos = readlen-writelen+1;
	}
	fclose(fp);
	CloseMP4File(hMp4File);
	LOGI("              mp4Encoder end                ");
	LOGI("              mp4Encoder end                ");
	return true;
}

JNIEXPORT bool JNICALL Java_com_example_mymp4v2h264_Mp4Activity_Mp4Encode
 (JNIEnv *env, jclass clz, jstring h264, jstring mp4)
 {
 	const char* h264_title = (*env)->GetStringUTFChars(env,h264, NULL);
 	const char* mp4_title = (*env)->GetStringUTFChars(env,mp4, NULL);

    bool ret = WriteH264File(h264_title, mp4_title);

 	(*env)->ReleaseStringUTFChars(env, h264, h264_title);
 	(*env)->ReleaseStringUTFChars(env, mp4, mp4_title);
 }

/***************************************************************************************/
/*                                      X264                                           */
/***************************************************************************************/
int run_ce;

JNIEXPORT void JNICALL Java_com_example_mymp4v2h264_MainActivity0_Mp4CC
 (JNIEnv *env, jclass clz, jstring h264_file)
{
	const char* h264_file_n = (*env)->GetStringUTFChars(env, h264_file, NULL);
	h264s_file = fopen(h264_file_n, "wb");
	if(h264s_file == NULL){
	   printf("Could not open audio file!\n");
	   LOGI("              Mp4CC Could not open audio file             ");
	   return;
	}
	yuv_size = (width * height *3)/2;

	x264_param_default_preset(&m_param,"fast","zerolatency");
	//x264_param_default(&m_param);
	m_param.i_threads = 1;
    m_param.i_width   = width;
    m_param.i_height  = height;
    m_param.i_fps_num = fps;
    m_param.i_fps_den = 1;
    m_param.i_bframe  = 10;

    //m_param->i_timebase_den = m_param->i_fps_num;
    //m_param->i_timebase_num = m_param->i_fps_den;
    //m_param->b_open_gop = 0;
    //m_param->i_bframe_pyramid = 0;
    //m_param->i_bframe_adaptive = X264_B_ADAPT_TRELLIS;
    //* 宽高比
    //m_param->vui.i_sar_width  = 640;
    //m_param->vui.i_sar_height = 480;

    //m_param.b_deblocking_filter= 1;
    //m_param.rc.f_rf_constant   = 33;
    //m_param.rc.i_bitrate       = bitrate;

    //m_param->rc.i_qp_constant=0;
    //m_param->rc.i_qp_max=0;
    //m_param->rc.i_qp_min=0;

    //m_param.i_csp                = (csp == 17) ? X264_CSP_NV12 : csp;//编码比特流的CSP，仅支持i420，色彩空间设置

    m_param.i_keyint_max    = 25;
	m_param.b_intra_refresh = 1;
	m_param.b_annexb  = 1;
    x264_param_apply_profile(&m_param,"baseline");
    encoder = x264_encoder_open(&m_param);

	x264_encoder_parameters(encoder, &m_param );
	x264_picture_alloc(&pic_in, X264_CSP_I420, width, height);

	yuv_buffer = (uint8_t *)malloc(yuv_size);

	//pic_in.img.i_csp = X264_CSP_I420;
	//pic_in.img.i_plane = 3;

    pic_in.img.plane[0] = yuv_buffer;
    pic_in.img.plane[1] = pic_in.img.plane[0] + width * height;
    pic_in.img.plane[2] = pic_in.img.plane[1] + width * height / 4;

    run_ce = 0;
    i_pts  = 0;

    //* 获取允许缓存的最大帧数.
    int iMaxFrames = x264_encoder_maximum_delayed_frames(encoder);
    LOGI("              Mp4CE iMaxFrames = %d           ",iMaxFrames);

    //* 获取X264中缓冲帧数.
    //int iFrames = x264_encoder_delayed_frames(pX264Handle);

	(*env)->ReleaseStringUTFChars(env, h264_file, h264_file_n);

	/*
	x264_t* pHandle   = NULL;

	x264_picture_t* pPic_in = (x264_picture_t*)malloc(sizeof(x264_picture_t));
	x264_picture_t* pPic_out = (x264_picture_t*)malloc(sizeof(x264_picture_t));
	x264_param_t* pParam = (x264_param_t*)malloc(sizeof(x264_param_t));

	x264_param_default(pParam);
    pParam->i_width   = width;
    pParam->i_height  = height;

    pParam->i_csp=csp;
    x264_param_apply_profile(pParam, x264_profile_names[5]);

    pHandle = x264_encoder_open(pParam);

    x264_picture_init(pPic_out);
    x264_picture_alloc(pPic_in, csp, pParam->i_width, pParam->i_height);

    //release
    x264_picture_clean(pPic_in);
    x264_encoder_close(pHandle);
    pHandle = NULL;

    free(pPic_in);
    free(pPic_out);
    free(pParam);
    */
}

int nal_n;
JNIEXPORT void JNICALL Java_com_example_mymp4v2h264_MainActivity0_Mp4CE
 (JNIEnv *env, jclass clz, jbyteArray data, jint size)
{
	unsigned char *buf = (unsigned char *)(*env)->GetByteArrayElements(env, data, JNI_FALSE);
	//yuv_buffer = buf;//(uint8_t *)(*env)->GetByteArrayElements(env, data, JNI_FALSE);
	memcpy(yuv_buffer, buf, yuv_size);

	nnal  = 0;
	nal_n = 0;
	pic_in.i_pts = i_pts++;
	x264_encoder_encode(encoder, &nals, &nnal, &pic_in, &pic_out);

	x264_nal_t *nal;
	for (nal = nals; nal < nals + nnal; nal++) {
		nal_n++;
	    fwrite(nal->p_payload, 1, nal->i_payload, h264s_file);
	    //LOGI("              Mp4CE %d, nal->i_payload = %d          ",nal_n, nal->i_payload);
	}
	run_ce++;
	LOGI("              Mp4CE %d  %d           ",run_ce, nnal);
	(*env)->ReleaseByteArrayElements(env, data, (jbyte *)buf, 0);

	//(*env)->ReleaseByteArrayElements(env, data, (jbyte *)yuv_buffer, 0);
}

JNIEXPORT void JNICALL Java_com_example_mymp4v2h264_MainActivity0_Mp4EE
 (JNIEnv *env, jclass clz)
{
	LOGI("              Mp4EE end             ");
	//flush encoder
	while(1){
		int j;
		int ret = x264_encoder_encode(encoder, &nals, &nnal, NULL, &pic_out);
	    if(ret<=0){
	    	LOGI("              x264_encoder_encode          ");
	        break;
	    }
		LOGI("              Mp4CE Flush          ");
	    for (j=0; j < nnal; j++){
	        fwrite(nals[j].p_payload, 1, nals[j].i_payload, h264s_file);
	    }
	}
	LOGI("              Mp4EE end             ");

    x264_picture_clean(&pic_in);
    LOGI("              Mp4EE end 00            ");
    //x264_picture_clean(&pic_out);
    LOGI("              Mp4EE end 11            ");
    x264_encoder_close(encoder);
    LOGI("              Mp4EE end 22            ");
    fclose(h264s_file);
    LOGI("              Mp4EE end 33             ");
    //free(yuv_buffer);
    LOGI("              Mp4EE end end             ");
}

/***************************************************************************************/
/*                                    FFMPEG  MP4(x264)                                */
/***************************************************************************************/
const char* out_file;
int frame_pts;
int ffmpeg_ce;

int flush_encoder(AVFormatContext *fmt_ctx,unsigned int stream_index)
{
    int ret;
    int got_frame;
    AVPacket enc_pkt;
    if (!(fmt_ctx->streams[stream_index]->codec->codec->capabilities &CODEC_CAP_DELAY))
        return 0;
    while (1) {
        enc_pkt.data = NULL;
        enc_pkt.size = 0;
        av_init_packet(&enc_pkt);
        ret = avcodec_encode_video2 (fmt_ctx->streams[stream_index]->codec, &enc_pkt, NULL, &got_frame);
        av_frame_free(NULL);
        if (ret < 0)
            break;
        if (!got_frame){
            ret=0;
            break;
        }
        printf("Flush Encoder: Succeed to encode 1 frame!\tsize:%5d\n",enc_pkt.size);
        /* mux encoded frame */
        enc_pkt.pts = frame_pts++*(pCodecCtx->time_base.num*1000/pCodecCtx->time_base.den);
        ret = av_write_frame(fmt_ctx, &enc_pkt);
        if (ret < 0)
            break;
    }
    return ret;
}

JNIEXPORT void JNICALL Java_com_example_mymp4v2h264_MainActivity0_Mp4FfmpegCC
 (JNIEnv *env, jclass clz, jstring h264_file)
{
	const char* h264_file_n = (*env)->GetStringUTFChars(env, h264_file, NULL);
	int ret;
	out_file = h264_file_n;

	LOGI("              Mp4FfmpegCC 00 %s           ",out_file);
	//const char* out_file = "ds.h264";

	av_register_all();
	avcodec_register_all();

	//Method1.
	pFormatCtx = avformat_alloc_context();
	//Guess Format
	fmt = av_guess_format(NULL, out_file, NULL);
	pFormatCtx->oformat = fmt;

	LOGI("              Mp4FfmpegCC 11           ");

	//Method 2.
	//avformat_alloc_output_context2(&pFormatCtx, NULL, NULL, out_file);
    //fmt = pFormatCtx->oformat;

    LOGI("              Mp4FfmpegCC 11           ");
    if (!(fmt->flags & AVFMT_NOFILE))
    {
	//Open output URL
	if (avio_open(&pFormatCtx->pb,out_file, AVIO_FLAG_WRITE) < 0){//AVIO_FLAG_WRITE AVIO_FLAG_READ_WRITE
	    //printf("Failed to open output file! \n");
	    LOGI("              Failed to open output file!            ");
	    return ;
	}
	LOGI("           avio_open!            ");
    }

    pCodec = avcodec_find_encoder(AV_CODEC_ID_H264);
    if (!pCodec) {
        LOGI("codec not found encoder \n");
        return ;
    }

	video_st0 = avformat_new_stream(pFormatCtx, pCodec);//NULL
	//video_st->time_base.num = 1;
	//video_st->time_base.den = 25;

	if (!video_st0) {
	//if (video_st==NULL){
		LOGI("              Failed to create video stream!            ");
	    return ;
	}
    LOGI("           avformat_new_stream 00 !            ");

	//Param that must set
	pCodecCtx = video_st0->codec;
	//pCodecCtx->codec_id =AV_CODEC_ID_HEVC;
    pCodecCtx->codec_id = AV_CODEC_ID_H264;//fmt->video_codec;
	pCodecCtx->codec_type = AVMEDIA_TYPE_VIDEO;
    pCodecCtx->pix_fmt = AV_PIX_FMT_YUV420P;

	pCodecCtx->width  = in_w;
	pCodecCtx->height = in_h;
	pCodecCtx->bit_rate = 1000000;
	pCodecCtx->gop_size=250;

	pCodecCtx->time_base.num = 1;
	pCodecCtx->time_base.den = 25;

	pCodecCtx->i_quant_factor = 1.0 / 1.40f; // x4->params.rc.f_ip_factor = 1 / fabs(avctx->i_quant_factor);
	pCodecCtx->b_quant_factor = 1.30f;

	LOGI("              pCodecCtx->codec_id = %d            ",pCodecCtx->codec_id);

	//H264
	//pCodecCtx->me_range = 16;
	//pCodecCtx->max_qdiff = 4;
	//pCodecCtx->qcompress = 0.6;
	//pCodecCtx->qmin = 10;
	//pCodecCtx->qmax = 51;

	//Optional Param
	//pCodecCtx->max_b_frames=3;

	// Set Option
	AVDictionary *param = NULL;

	//H.264
	if(pCodecCtx->codec_id == AV_CODEC_ID_H264) {
	   LOGI("           AV_CODEC_ID_H264            ");
	   av_dict_set(&param, "preset", "ultrafast", 0); //slow
	   av_dict_set(&param, "tune", "zerolatency", 0);
	   //av_dict_set(&param, "profile", "main", 0);
	}
	//H.265
	if(pCodecCtx->codec_id == AV_CODEC_ID_H265){
	   LOGI("           AV_CODEC_ID_H265            ");
	   av_dict_set(&param, "preset", "ultrafast", 0);
	   av_dict_set(&param, "tune", "zero-latency", 0);
	}
	LOGI("           av_dict_set            ");

	//pCodec = avcodec_find_encoder(AV_CODEC_ID_H264);
	//if (!pCodec){
	//	//printf("Can not find encoder! \n");
	//	LOGI("              Mp4FfmpegCC Can not find encoder!            ");
	//	return ;
	//}

	LOGI("           avcodec_find_encoder            ");

	//Show some Information
	av_dump_format(pFormatCtx, 0, out_file, 1);
	LOGI("           av_dump_format            ");

	/*
	//avcodec_open2(AVCodecContext *avctx, const AVCodec *codec, AVDictionary **options)
	if(avcodec_open2(pCodecCtx, pCodec, NULL)< 0){//&param
	     //printf("Failed to open encoder! \n");
	     LOGI("              Mp4FfmpegCC Failed to open encoder!            ");
	     return ;
	}
	*/
	if (avcodec_open2(pCodecCtx, pCodec, NULL) < 0) {
		 LOGI("              Mp4FfmpegCC Failed to open encoder!            ");
		 return ;
	}

	LOGI("           avcodec_open2            ");
	//设置编码延迟
	//av_opt_set(c->priv_data, "preset", "ultrafast", 0);
	//av_opt_set(c->priv_data, "preset", "superfast", 0);
    //av_opt_set(c->priv_data, "tune", "zerolatency", 0);

	pFrame = av_frame_alloc();
	//picture_size = avpicture_get_size(pCodecCtx->pix_fmt, pCodecCtx->width, pCodecCtx->height);
	//picture_buf = (uint8_t *)av_malloc(picture_size);
	//avpicture_fill((AVPicture *)pFrame, picture_buf, pCodecCtx->pix_fmt, pCodecCtx->width, pCodecCtx->height);
	picture_size=  pCodecCtx->width*pCodecCtx->height*3/2;
	picture_buf = (uint8_t *)av_malloc(picture_size);

	LOGI("   picture_size = %d, w = %d, h = %d   ",picture_size,pCodecCtx->width, pCodecCtx->height);

	pFrame->format = pCodecCtx->pix_fmt;
	pFrame->width  = pCodecCtx->width;
	pFrame->height = pCodecCtx->height;

	LOGI("           av_frame_alloc            ");
	/* the image can be allocated by any means and av_image_alloc() is
	* just the most convenient way if av_malloc() is to be used */
	//ret = av_image_alloc(pFrame->data, pFrame->linesize, pCodecCtx->width, pCodecCtx->height, pCodecCtx->pix_fmt, 32);
	ret = av_frame_get_buffer(pFrame, 32);
	if (ret < 0) {
	    LOGI("        Could not allocate raw picture buffer       ");
	    return;
	}

	//Write File Header
	ret = avformat_write_header(pFormatCtx, &param);//NULL
	if (ret < 0) {
		LOGI("        avformat_write_header      ");
		LOGI("Error occurred when opening output file: %s\n", av_err2str(ret));
	    return ;
    }

	LOGI("        avformat_write_header       ");
	av_new_packet(&pkt,picture_size);
	//av_init_packet(&pkt);
	//pkt.data = NULL;    // packet data will be allocated by the encoder
	//pkt.size = 0;

	y_size = pCodecCtx->width*pCodecCtx->height;

	pFrame->data[0] = picture_buf;              // Y
	pFrame->data[1] = picture_buf+ y_size;      // U
    pFrame->data[2] = picture_buf+ y_size*5/4;  // V

    LOGI("        y_size = %d       ",y_size);

    frame_pts = 0;
    framecnt  = 0;
    ffmpeg_ce = 0;
	(*env)->ReleaseStringUTFChars(env, h264_file, h264_file_n);
}

/*
ffmpeg中的时间单位

AV_TIME_BASE

ffmpeg中的内部计时单位（时间基），ffmepg中的所有时间都是于它为一个单位，
比如AVStream中的duration即以为着这个流的长度为duration个AV_TIME_BASE。AV_TIME_BASE定义为：

#define         AV_TIME_BASE   1000000


AV_TIME_BASE_Q

ffmpeg内部时间基的分数表示，实际上它是AV_TIME_BASE的倒数。从它的定义能很清楚的看到这点：

#define         AV_TIME_BASE_Q   (AVRational){1, AV_TIME_BASE}


AVRatioal的定义如下：

typedef struct AVRational{
int num; //numerator
int den; //denominator
} AVRational;
ffmpeg提供了一个把AVRatioal结构转换成double的函数：

复制代码
static inline double av_q2d(AVRational a)｛
//Convert rational to double.
//@param a rational to convert

    return a.num / (double) a.den;
}
复制代码
现在可以根据pts来计算一桢在整个视频中的时间位置：

timestamp(秒) = pts * av_q2d(st->time_base)

计算视频长度的方法：

time(秒) = st->duration * av_q2d(st->time_base)

这里的st是一个AVStream对象指针。

时间基转换公式

timestamp(ffmpeg内部时间戳) = AV_TIME_BASE * time(秒)
time(秒) = AV_TIME_BASE_Q * timestamp(ffmpeg内部时间戳)
所以当需要把视频跳转到N秒的时候可以使用下面的方法：

int64_t timestamp = N * AV_TIME_BASE;
2
av_seek_frame(fmtctx, index_of_video, timestamp, AVSEEK_FLAG_BACKWARD);
ffmpeg同样为我们提供了不同时间基之间的转换函数：

int64_t av_rescale_q(int64_t a, AVRational bq, AVRational cq)
这个函数的作用是计算a * bq / cq，来把时间戳从一个时基调整到另外一个时基。在进行时基转换的时候，
我们应该首选这个函数，因为它可以避免溢出的情况发生。

1. 视频时间戳

pts = inc++ *(1000/fps); 其中inc是一个静态的，初始值为0，每次打完时间戳inc加1.

在ffmpeg，中的代码为

pkt.pts= m_nVideoTimeStamp++ * (pctx->time_base.num * 1000 / pctx->time_base.den);

2. 音频时间戳

pts = inc++ * (frame_size * 1000 / sample_rate)

在ffmpeg中的代码为

pkt.pts= m_nAudioTimeStamp++ * (m_ACtx->frame_size * 1000 / m_ACtx->sample_rate);

采样频率是指将模拟声音波形进行数字化时，每秒钟抽取声波幅度样本的次数。

当我们调用av_rescale_q(pts, timebase1, timebase2)或者av_rescale_q_rnd(pts, timebase1, timebase2, XX)时
其实就是按照下面的公式计算了一下， 公式如下：
x = pts * (timebase1.num / timebase1.den )* (timebase2.den / timebase2.num);
这个x就是转换后的时间戳。
*/

JNIEXPORT void JNICALL Java_com_example_mymp4v2h264_MainActivity0_Mp4FfmpegCE
 (JNIEnv *env, jclass clz, jbyteArray data, jint size)
{
	unsigned char *buf = (unsigned char *)(*env)->GetByteArrayElements(env, data, JNI_FALSE);

	memcpy(picture_buf, buf, picture_size);

	//PTS
	//pFrame->pts= frame_pts++;
    //pFrame->pts=frame_pts*(video_st0->time_base.den)/((video_st0->time_base.num)*25);
    //frame_pts = frame_pts+1;
	int got_picture=0;

	//Encode
	int ret = avcodec_encode_video2(pCodecCtx, &pkt, pFrame, &got_picture);
	if(ret < 0){
	    printf("Failed to encode! \n");
	    LOGI("              Mp4FfmpegCE Failed to encode!            ");
	    return ;
	}
	if (got_picture){   //if (got_picture==1){
	    //printf("Succeed to encode frame: %5d\tsize:%5d\n",framecnt,pkt.size);
		if(frame_pts%25 == 1){
		   LOGI("              Mp4FfmpegCE %d            ",frame_pts);
		}
	    //framecnt++;
		//if (pCodecCtx->coded_frame->pts != AV_NOPTS_VALUE) {
		//     pkt.pts= av_rescale_q(pCodecCtx->coded_frame->pts, pCodecCtx->time_base, video_st0->time_base);
		//}
		//pkt.pts = AV_TIME_BASE*frame_pts/25;

		pkt.pts = frame_pts++*(pCodecCtx->time_base.num*1000/pCodecCtx->time_base.den);

	    //pkt.stream_index = video_st0->index;

	    ret = av_write_frame(pFormatCtx, &pkt);
	    av_packet_unref(&pkt);//av_free_packet(&pkt);
	}

	ffmpeg_ce++;
	//LOGI("              Mp4FfmpegCE %d            ",frame_pts);

	(*env)->ReleaseByteArrayElements(env, data, (jbyte *)buf, 0);

	//(*env)->ReleaseByteArrayElements(env, data, (jbyte *)yuv_buffer, 0);
}


JNIEXPORT void JNICALL Java_com_example_mymp4v2h264_MainActivity0_Mp4FfmpegEE
 (JNIEnv *env, jclass clz)
{
	LOGI("              Mp4FfmpegEE end             ");
	//Flush Encoder
	int ret = flush_encoder(pFormatCtx,0);
	if (ret < 0) {
	    printf("Flushing encoder failed\n");
		LOGI("              Flushing encoder failed             ");
	    return ;
	}
	LOGI("              Mp4FfmpegEE end 00            ");
	//Write file trailer
	av_write_trailer(pFormatCtx);
	LOGI("              Mp4FfmpegEE end 11            ");
	//Clean
	if (video_st0){
	   avcodec_close(video_st0->codec);
	   av_free(video_st0->codec);
	   av_freep(&pFrame->data[0]);
	   av_frame_free(&pFrame);
	   LOGI("              Mp4FfmpegEE end 12            ");
	   //av_free(picture_buf);
	}
	LOGI("              Mp4FfmpegEE end 22            ");
	/* close output */
	if (pFormatCtx && !(fmt->flags & AVFMT_NOFILE))
	        avio_closep(&pFormatCtx->pb);
	//avio_close(pFormatCtx->pb);
	LOGI("              Mp4FfmpegEE end 33            ");
	//avformat_free_context(pFormatCtx);
	LOGI("              Mp4FfmpegEE end end            ");
}

/***************************************************************************************/
/*                               FFMPEG MUX MP4                                        */
/***************************************************************************************/
#define STREAM_DURATION   10.0
#define STREAM_FRAME_RATE 25 /* 25 images/s */
#define STREAM_PIX_FMT AV_PIX_FMT_YUV420P /* default pix_fmt */
#define SCALE_FLAGS SWS_BICUBIC

typedef struct OutputStream {
    AVStream *st;
    /* pts of the next frame that will be generated */
    int64_t next_pts;
    int samples_count;

    AVFrame *frame;
    AVFrame *tmp_frame;

    float t, tincr, tincr2;

    struct SwsContext *sws_ctx;
    struct SwrContext *swr_ctx;
} OutputStream;

OutputStream video_st = { 0 }, audio_st = { 0 };
const char *filename;
AVOutputFormat *avfmt;
AVFormatContext *oc;
AVCodec *audio_codec, *video_codec;
int ret;
int have_video = 0, have_audio = 0;
int encode_video = 0, encode_audio = 0;
AVDictionary *opt = NULL;

static void add_stream(OutputStream *ost, AVFormatContext *oc, AVCodec **codec, enum AVCodecID codec_id)
{
    AVCodecContext *c;
    int i;

    /* find the encoder */
    *codec = avcodec_find_encoder(codec_id);
    if (!(*codec)) {
    	LOGI("Could not find encoder for '%s'\n",
                avcodec_get_name(codec_id));
        exit(1);
    }

    ost->st = avformat_new_stream(oc, *codec);
    if (!ost->st) {
    	LOGI("Could not allocate stream\n");
        exit(1);
    }
    ost->st->id = oc->nb_streams-1;
    c = ost->st->codec;

    /*
    AVCodecContext *c;
    AVStream *st;
    AVCodec *codec;

    st = avformat_new_stream(oc, NULL);
    if (!st) {
        LOGI(stderr, "Could not alloc stream\n");
        exit(1);
    }

    c = st->codec;
    // find the video encoder
    codec = avcodec_find_encoder(codec_id);
    if (!codec) {
        LOGI(stderr, "codec not found\n");
        exit(1);
    }
    avcodec_get_context_defaults3(c, codec);

    c->codec_id = codec_id;
    c->codec_type = AVMEDIA_TYPE_VIDEO;
    */
    switch ((*codec)->type)
    {
    case AVMEDIA_TYPE_AUDIO:
        c->sample_fmt  = (*codec)->sample_fmts ?
            (*codec)->sample_fmts[0] : AV_SAMPLE_FMT_FLTP;
        c->bit_rate    = 64000;
        c->sample_rate = 44100;
        if ((*codec)->supported_samplerates) {
            c->sample_rate = (*codec)->supported_samplerates[0];
            for (i = 0; (*codec)->supported_samplerates[i]; i++) {
                if ((*codec)->supported_samplerates[i] == 44100)
                    c->sample_rate = 44100;
            }
        }
        c->channels        = av_get_channel_layout_nb_channels(c->channel_layout);
        c->channel_layout = AV_CH_LAYOUT_STEREO;
        if ((*codec)->channel_layouts) {
            c->channel_layout = (*codec)->channel_layouts[0];
            for (i = 0; (*codec)->channel_layouts[i]; i++) {
                if ((*codec)->channel_layouts[i] == AV_CH_LAYOUT_STEREO)
                    c->channel_layout = AV_CH_LAYOUT_STEREO;
            }
        }
        c->channels        = av_get_channel_layout_nb_channels(c->channel_layout);
        ost->st->time_base = (AVRational){ 1, c->sample_rate };
        break;

    case AVMEDIA_TYPE_VIDEO:
        c->codec_id = codec_id;

        c->bit_rate = 1000000;
        /* Resolution must be a multiple of two. */
        c->width    = 640;
        c->height   = 480;
        /* timebase: This is the fundamental unit of time (in seconds) in terms
         * of which frame timestamps are represented. For fixed-fps content,
         * timebase should be 1/framerate and timestamp increments should be
         * identical to 1. */
        ost->st->time_base = (AVRational){ 1, STREAM_FRAME_RATE };
        c->time_base       = ost->st->time_base;

        c->gop_size      = 250; /* emit one intra frame every twelve frames at most */
        c->pix_fmt       = STREAM_PIX_FMT;

        c->i_quant_factor = 1.0 / 1.40f; // x4->params.rc.f_ip_factor = 1 / fabs(avctx->i_quant_factor);
        c->b_quant_factor = 1.30f;

        if (c->codec_id == AV_CODEC_ID_MPEG2VIDEO) {
            /* just for testing, we also add B frames */
            c->max_b_frames = 2;
        }
        if (c->codec_id == AV_CODEC_ID_MPEG1VIDEO) {
            /* Needed to avoid using macroblocks in which some coeffs overflow.
             * This does not happen with normal video, it just happens here as
             * the motion of the chroma plane does not match the luma plane. */
            c->mb_decision = 2;
        }
    break;

    default:
        break;
    }

    /* Some formats want stream headers to be separate. */
    if (oc->oformat->flags & AVFMT_GLOBALHEADER)
        c->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
}

static void log_packet(const AVFormatContext *fmt_ctx, const AVPacket *pkt)
{
    AVRational *time_base = &fmt_ctx->streams[pkt->stream_index]->time_base;

    printf("pts:%s pts_time:%s dts:%s dts_time:%s duration:%s duration_time:%s stream_index:%d\n",
           av_ts2str(pkt->pts), av_ts2timestr(pkt->pts, time_base),
           av_ts2str(pkt->dts), av_ts2timestr(pkt->dts, time_base),
           av_ts2str(pkt->duration), av_ts2timestr(pkt->duration, time_base),
           pkt->stream_index);
}

static int write_frame(AVFormatContext *fmt_ctx, const AVRational *time_base, AVStream *st, AVPacket *pkt)
{
    /* rescale output packet timestamp values from codec to stream timebase */
    av_packet_rescale_ts(pkt, *time_base, st->time_base);
    pkt->stream_index = st->index;

    /* Write the compressed frame to the media file. */
    //log_packet(fmt_ctx, pkt);

    return av_interleaved_write_frame(fmt_ctx, pkt);
}

/****************************video********************************/

static AVFrame *alloc_picture(enum AVPixelFormat pix_fmt, int width, int height)
{
    AVFrame *picture;
    int ret;

    picture = av_frame_alloc();
    if (!picture)
        return NULL;

    picture->format = pix_fmt;
    picture->width  = width;
    picture->height = height;

    /* allocate the buffers for the frame data */
    ret = av_frame_get_buffer(picture, 32);
    if (ret < 0) {
    	LOGI("Could not allocate frame data.\n");
        exit(1);
    }

    return picture;
}

static void open_video(AVFormatContext *oc, AVCodec *codec, OutputStream *ost, AVDictionary *opt_arg)
{
    int ret;
    AVCodecContext *c = ost->st->codec;
    AVDictionary *opt = NULL;

    av_dict_copy(&opt, opt_arg, 0);

    /* open the codec */
    ret = avcodec_open2(c, codec, &opt);
    av_dict_free(&opt);
    if (ret < 0) {
    	LOGI("Could not open video codec: %s\n", av_err2str(ret));
        exit(1);
    }

    /* allocate and init a re-usable frame */
    ost->frame = alloc_picture(c->pix_fmt, c->width, c->height);
    if (!ost->frame) {
    	LOGI("Could not allocate video frame\n");
        exit(1);
    }

    /* If the output format is not YUV420P, then a temporary YUV420P
     * picture is needed too. It is then converted to the required
     * output format. */
    ost->tmp_frame = NULL;
    if (c->pix_fmt != AV_PIX_FMT_YUV420P) {
        ost->tmp_frame = alloc_picture(AV_PIX_FMT_YUV420P, c->width, c->height);
        if (!ost->tmp_frame) {
        	LOGI("Could not allocate temporary picture\n");
            exit(1);
        }
    }

    picture_size=  c->width*c->height*3/2;
    picture_buf = (uint8_t *)av_malloc(picture_size);

    int y_size = c->width*c->height;
    LOGI("open_video w = %d, h = %d, picture_size= %d, y_size = %d\n", c->width, c->height, picture_size, y_size);


    ost->frame->data[0] = picture_buf;              // Y
    ost->frame->data[1] = picture_buf+ y_size;      // U
    ost->frame->data[2] = picture_buf+ y_size*5/4;  // V
}

/* Prepare a dummy image. */
static void fill_yuv_image(AVFrame *pict, int frame_index,
                           int width, int height)
{
    int x, y, i, ret;

    /* when we pass a frame to the encoder, it may keep a reference to it
     * internally;
     * make sure we do not overwrite it here
     */
    ret = av_frame_make_writable(pict);
    if (ret < 0)
        exit(1);

    i = frame_index;

    /* Y */
    for (y = 0; y < height; y++)
        for (x = 0; x < width; x++)
            pict->data[0][y * pict->linesize[0] + x] = x + y + i * 3;

    /* Cb and Cr */
    for (y = 0; y < height / 2; y++) {
        for (x = 0; x < width / 2; x++) {
            pict->data[1][y * pict->linesize[1] + x] = 128 + y + i * 2;
            pict->data[2][y * pict->linesize[2] + x] = 64 + x + i * 5;
        }
    }
}

static AVFrame *get_video_frame(OutputStream *ost)
{
    AVCodecContext *c = ost->st->codec;

    /* check if we want to generate more frames */
    if (av_compare_ts(ost->next_pts, ost->st->codec->time_base,
                      STREAM_DURATION, (AVRational){ 1, 1 }) >= 0)
        return NULL;

    if (c->pix_fmt != AV_PIX_FMT_YUV420P) {
        /* as we only generate a YUV420P picture, we must convert it
         * to the codec pixel format if needed */
        if (!ost->sws_ctx) {
            ost->sws_ctx = sws_getContext(c->width, c->height,
                                          AV_PIX_FMT_YUV420P,
                                          c->width, c->height,
                                          c->pix_fmt,
                                          SCALE_FLAGS, NULL, NULL, NULL);
            if (!ost->sws_ctx) {
            	LOGI("Could not initialize the conversion context\n");
                exit(1);
            }
        }
        fill_yuv_image(ost->tmp_frame, ost->next_pts, c->width, c->height);
        sws_scale(ost->sws_ctx,
                  (const uint8_t * const *)ost->tmp_frame->data, ost->tmp_frame->linesize,
                  0, c->height, ost->frame->data, ost->frame->linesize);
    } else {
        fill_yuv_image(ost->frame, ost->next_pts, c->width, c->height);
    }

    ost->frame->pts = ost->next_pts++;

    return ost->frame;
}

/*
 * encode one video frame and send it to the muxer
 * return 1 when encoding is finished, 0 otherwise
 */
static int write_video_frame(AVFormatContext *oc, OutputStream *ost)
{
    int ret;
    //AVCodecContext *c;
    // AVFrame *frame;
    int got_packet = 0;
    AVPacket pkt;

    //c = ost->st->codec;
    //frame = get_video_frame(ost);

    av_init_packet(&pkt);
    pkt.data = NULL;
    pkt.size = 0;
    /* encode the image */
    //ret = avcodec_encode_video2(c, &pkt, frame, &got_packet);
    ret = avcodec_encode_video2(ost->st->codec, &pkt, ost->frame, &got_packet);

    if (ret < 0) {
    	LOGI("Error encoding video frame: %s\n", av_err2str(ret));
        //exit(1);
    }

    if (got_packet) {
    	//if (c->coded_frame->pts != AV_NOPTS_VALUE) {
    	//   pkt.pts= av_rescale_q(c->coded_frame->pts, c->time_base, ost->st->time_base);
    	//}
        ret = write_frame(oc, &ost->st->codec->time_base, ost->st, &pkt);
    }

    //return (frame || got_packet) ? 0 : 1;
    return (got_packet) ? 0 : 1;
}

/****************************audio********************************/

static AVFrame *alloc_audio_frame(enum AVSampleFormat sample_fmt,
                                  uint64_t channel_layout,
                                  int sample_rate, int nb_samples)
{
    AVFrame *frame = av_frame_alloc();
    int ret;

    if (!frame) {
    	LOGI("Error allocating an audio frame\n");
        exit(1);
    }

    frame->format = sample_fmt;
    frame->channel_layout = channel_layout;
    frame->sample_rate = sample_rate;
    frame->nb_samples = nb_samples;

    if (nb_samples) {
        ret = av_frame_get_buffer(frame, 0);
        if (ret < 0) {
        	LOGI("Error allocating an audio buffer\n");
            exit(1);
        }
    }

    return frame;
}

static void open_audio(AVFormatContext *oc, AVCodec *codec, OutputStream *ost, AVDictionary *opt_arg)
{
    AVCodecContext *c;
    int nb_samples;
    int ret;
    AVDictionary *opt = NULL;

    c = ost->st->codec;

    /* open it */
    av_dict_copy(&opt, opt_arg, 0);
    ret = avcodec_open2(c, codec, &opt);
    av_dict_free(&opt);
    if (ret < 0) {
    	LOGI("Could not open audio codec: %s\n", av_err2str(ret));
        exit(1);
    }

    /* init signal generator */
    ost->t     = 0;
    ost->tincr = 2 * M_PI * 110.0 / c->sample_rate;
    /* increment frequency by 110 Hz per second */
    ost->tincr2 = 2 * M_PI * 110.0 / c->sample_rate / c->sample_rate;

    if (c->codec->capabilities & AV_CODEC_CAP_VARIABLE_FRAME_SIZE)
        nb_samples = 10000;
    else
        nb_samples = c->frame_size;

    ost->frame     = alloc_audio_frame(c->sample_fmt, c->channel_layout,
                                       c->sample_rate, nb_samples);
    ost->tmp_frame = alloc_audio_frame(AV_SAMPLE_FMT_S16, c->channel_layout,
                                       c->sample_rate, nb_samples);

    /*
    // create resampler context
        ost->swr_ctx = swr_alloc();
        if (!ost->swr_ctx) {
        	LOGI("Could not allocate resampler context\n");
            exit(1);
        }

        // set options
        av_opt_set_int       (ost->swr_ctx, "in_channel_count",   c->channels,       0);
        av_opt_set_int       (ost->swr_ctx, "in_sample_rate",     c->sample_rate,    0);
        av_opt_set_sample_fmt(ost->swr_ctx, "in_sample_fmt",      AV_SAMPLE_FMT_S16, 0);
        av_opt_set_int       (ost->swr_ctx, "out_channel_count",  c->channels,       0);
        av_opt_set_int       (ost->swr_ctx, "out_sample_rate",    c->sample_rate,    0);
        av_opt_set_sample_fmt(ost->swr_ctx, "out_sample_fmt",     c->sample_fmt,     0);

        // initialize the resampling context
        if ((ret = swr_init(ost->swr_ctx)) < 0) {
        	LOGI("Failed to initialize the resampling context\n");
            exit(1);
        }
    */
}

/* Prepare a 16 bit dummy audio frame of 'frame_size' samples and
 * 'nb_channels' channels. */
static AVFrame *get_audio_frame(OutputStream *ost)
{
    AVFrame *frame = ost->tmp_frame;
    int j, i, v;
    int16_t *q = (int16_t*)frame->data[0];

    /* check if we want to generate more frames */
    if (av_compare_ts(ost->next_pts, ost->st->codec->time_base,
                      STREAM_DURATION, (AVRational){ 1, 1 }) >= 0)
        return NULL;

    for (j = 0; j <frame->nb_samples; j++) {
        v = (int)(sin(ost->t) * 10000);
        for (i = 0; i < ost->st->codec->channels; i++)
            *q++ = v;
        ost->t     += ost->tincr;
        ost->tincr += ost->tincr2;
    }

    frame->pts = ost->next_pts;
    ost->next_pts  += frame->nb_samples;

    return frame;
}

/*
 * encode one audio frame and send it to the muxer
 * return 1 when encoding is finished, 0 otherwise
 */
static int write_audio_frame(AVFormatContext *oc, OutputStream *ost)
{
    AVCodecContext *c;
    AVPacket pkt = { 0 }; // data and size must be 0;
    AVFrame *frame;
    int ret;
    int got_packet;
    int dst_nb_samples;

    av_init_packet(&pkt);
    c = ost->st->codec;

    frame = get_audio_frame(ost);

    if (frame) {
        /* convert samples from native format to destination codec format, using the resampler */
            /* compute destination number of samples */
            dst_nb_samples = av_rescale_rnd(swr_get_delay(ost->swr_ctx, c->sample_rate) + frame->nb_samples,
                                            c->sample_rate, c->sample_rate, AV_ROUND_UP);
            av_assert0(dst_nb_samples == frame->nb_samples);

        /* when we pass a frame to the encoder, it may keep a reference to it
         * internally;
         * make sure we do not overwrite it here
         */
        ret = av_frame_make_writable(ost->frame);
        if (ret < 0)
            exit(1);

            /* convert to destination format */
            ret = swr_convert(ost->swr_ctx,
                              ost->frame->data, dst_nb_samples,
                              (const uint8_t **)frame->data, frame->nb_samples);
            if (ret < 0) {
            	LOGI("Error while converting\n");
                exit(1);
            }
            frame = ost->frame;

        frame->pts = av_rescale_q(ost->samples_count, (AVRational){1, c->sample_rate}, c->time_base);
        ost->samples_count += dst_nb_samples;
    }

    ret = avcodec_encode_audio2(c, &pkt, frame, &got_packet);
    if (ret < 0) {
    	LOGI("Error encoding audio frame: %s\n", av_err2str(ret));
        exit(1);
    }

    if (got_packet) {
        ret = write_frame(oc, &c->time_base, ost->st, &pkt);
        if (ret < 0) {
        	LOGI("Error while writing audio frame: %s\n", av_err2str(ret));
            exit(1);
        }
    }

    return (frame || got_packet) ? 0 : 1;
}

static void close_stream(AVFormatContext *oc, OutputStream *ost)
{
     avcodec_close(ost->st->codec);
     //av_free(ost->st->codec);
     av_frame_free(&ost->frame);
     if(!ost->tmp_frame){
        av_frame_free(&ost->tmp_frame);
     }
     //sws_freeContext(ost->sws_ctx);
     //swr_free(&ost->swr_ctx);
}

int xcc_run;

JNIEXPORT void JNICALL Java_com_example_mymp4v2h264_MainActivity0_Mp4FfmpegXCC
 (JNIEnv *env, jclass clz, jstring h264_file)
{
	const char* h264_file_n = (*env)->GetStringUTFChars(env, h264_file, NULL);
	int ret;
	filename = h264_file_n;

	have_video   = 0;
	encode_video = 0;
	have_audio   = 0;
	encode_audio = 0;

	av_register_all();

	/* allocate the output media context */
	avformat_alloc_output_context2(&oc, NULL, NULL, filename);
	if (!oc) {
		LOGI("Could not deduce output format from file extension: using MPEG.\n");
	    avformat_alloc_output_context2(&oc, NULL, "mpeg", filename);
	}
	if (!oc)
	    return ;
	avfmt = oc->oformat;

	LOGI("              Mp4FfmpegXCC %s           ",filename);

    avfmt->video_codec = AV_CODEC_ID_H264;
    avfmt->audio_codec = AV_CODEC_ID_MP3;

    /* Add the audio and video streams using the default format codecs
         * and initialize the codecs. */
    if (avfmt->video_codec != AV_CODEC_ID_NONE) {
        add_stream(&video_st, oc, &video_codec, avfmt->video_codec);
        have_video = 1;
        encode_video = 1;

        //H.264
        if(avfmt->video_codec == AV_CODEC_ID_H264) {
        	LOGI("           AV_CODEC_ID_H264            ");
        	av_dict_set(&opt, "preset", "fast", 0);
        	av_dict_set(&opt, "tune", "zerolatency", 0);
        	//av_dict_set(&param, "profile", "main", 0);
        }
    }
    if (avfmt->audio_codec != AV_CODEC_ID_NONE) {
        //add_stream(&audio_st, oc, &audio_codec, avfmt->audio_codec);
        //have_audio = 1;
        //encode_audio = 1;
    }

    /* Now that all the parameters are set, we can open the audio and
    * video codecs and allocate the necessary encode buffers. */
    if (have_video)
        open_video(oc, video_codec, &video_st, opt);

    if (have_audio)
        open_audio(oc, audio_codec, &audio_st, opt);

    av_dump_format(oc, 0, filename, 1);

    /* open the output file, if needed */
    if (!(avfmt->flags & AVFMT_NOFILE)) {
       ret = avio_open(&oc->pb, filename, AVIO_FLAG_WRITE);
       if (ret < 0) {
    	   LOGI("Could not open '%s': %s\n", filename, av_err2str(ret));
           return ;
       }
    }

    /* Write the stream header, if any. */
    ret = avformat_write_header(oc, &opt);
    if (ret < 0) {
       LOGI("Error occurred when opening output file: %s\n", av_err2str(ret));
       return ;
    }
    ffmpeg_ce = 0;
    frame_pts = 0;
    xcc_run = 0;

	(*env)->ReleaseStringUTFChars(env, h264_file, h264_file_n);
}

JNIEXPORT void JNICALL Java_com_example_mymp4v2h264_MainActivity0_Mp4FfmpegXCE
 (JNIEnv *env, jclass clz, jbyteArray data, jint size)
{
	unsigned char *buf = (unsigned char *)(*env)->GetByteArrayElements(env, data, JNI_FALSE);

	memcpy(picture_buf, buf, picture_size);

	//write_video_frame(oc, &video_st);
	int got_packet = 0;
	AVPacket pkt;

	av_init_packet(&pkt);
	pkt.data = NULL;
	pkt.size = 0;

	ret = avcodec_encode_video2(video_st.st->codec, &pkt, video_st.frame, &got_packet);
	if (ret < 0) {
	    LOGI("Error encoding video frame: %s\n", av_err2str(ret));
	    //exit(1);
	}

	if (got_packet) {
	   //if (c->coded_frame->pts != AV_NOPTS_VALUE) {
	   //   pkt.pts= av_rescale_q(c->coded_frame->pts, c->time_base, ost->st->time_base);
	   //}
	   //ret = write_frame(oc, &ost->st->codec->time_base, ost->st, &pkt);

	   /* rescale output packet timestamp values from codec to stream timebase */
	   // method  1
	   //av_packet_rescale_ts(&pkt, video_st.st->codec->time_base, video_st.st->time_base);
	   // method  2

	   if (video_st.st->codec->coded_frame->pts != AV_NOPTS_VALUE) {
		   LOGI("      nor AV_NOPTS_VALUE     \n");
	       pkt.pts = av_rescale_q(video_st.st->codec->coded_frame->pts,
	    		   video_st.st->codec->time_base,
	    		   video_st.st->time_base);
	   }else{
		   pkt.pts = frame_pts++*(video_st.st->codec->time_base.num*1000/video_st.st->codec->time_base.den);
	   }

	   /*
	   //method 3
	   if (video_st.st->codec->coded_frame->pts != AV_NOPTS_VALUE) {
	   	       pkt.pts= av_rescale_q(video_st.st->codec->coded_frame->pts,
	   	    		   video_st.st->codec->time_base,
	   	    		   video_st.st->time_base);
	   	       pkt.dts=pkt.pts;
	   }
	   else{//pkt.pts==AV_NOPTS_VALUE)
		   //Write PTS
		   //frame_pts = 0;
		   LOGI("      AV_NOPTS_VALUE     \n");
		   LOGI("      AV_NOPTS_VALUE     \n");
		   AVRational time_base1 = video_st.st->time_base;
		   //Duration between 2 frames (us)
		   int64_t calc_duration=(double)AV_TIME_BASE/av_q2d(video_st.st->r_frame_rate);
		   //Parameters
		   pkt.pts=(double)(frame_pts*calc_duration)/(double)(av_q2d(time_base1)*AV_TIME_BASE);
		   pkt.dts=pkt.pts;
		   pkt.duration=(double)calc_duration/(double)(av_q2d(time_base1)*AV_TIME_BASE);
	   }
       */
	   pkt.stream_index = video_st.st->index;

	   xcc_run++;

	   ret = av_interleaved_write_frame(oc, &pkt);

	   if (ret < 0) {
	   	   LOGI("      av_interleaved_write_frame  failed     \n");
	   }else{
		   LOGI("      Mp4FfmpegXCE  encode: %d     \n", xcc_run);
	   }
	}


	/*
	while (encode_video || encode_audio) {
	        // select the stream to encode
	        if (encode_video && (!encode_audio || av_compare_ts(video_st.next_pts, video_st.st->codec->time_base, audio_st.next_pts, audio_st.st->codec->time_base) <= 0))
	        {
	            encode_video = !write_video_frame(oc, &video_st);
	        } else {
	            encode_audio = !write_audio_frame(oc, &audio_st);
	        }
	}
    */
	ffmpeg_ce++;
    //LOGI("              Mp4FfmpegXCE %d            ",ffmpeg_ce);

	(*env)->ReleaseByteArrayElements(env, data, (jbyte *)buf, 0);
}

JNIEXPORT void JNICALL Java_com_example_mymp4v2h264_MainActivity0_Mp4FfmpegXEE
 (JNIEnv *env, jclass clz)
{
	LOGI("              Mp4FfmpegXEE end             ");

	av_write_trailer(oc);

	/* Close each codec. */
	if (have_video)
	   close_stream(oc, &video_st);
	if (have_audio)
	   close_stream(oc, &audio_st);

	if (!(avfmt->flags & AVFMT_NOFILE))
	   /* Close the output file. */
	   avio_closep(&oc->pb);

	/* free the stream */
    avformat_free_context(oc);
}

/*
static void log_packet(const AVFormatContext *fmt_ctx, const AVPacket *pkt, const char *tag)
{
    AVRational *time_base = &fmt_ctx->streams[pkt->stream_index]->time_base;

    printf("%s: pts:%s pts_time:%s dts:%s dts_time:%s duration:%s duration_time:%s stream_index:%d\n",
           tag,
           av_ts2str(pkt->pts), av_ts2timestr(pkt->pts, time_base),
           av_ts2str(pkt->dts), av_ts2timestr(pkt->dts, time_base),
           av_ts2str(pkt->duration), av_ts2timestr(pkt->duration, time_base),
           pkt->stream_index);
}
*/

struct SwsContext *sws_ctx;
#define SCALE_FLAGS SWS_BICUBIC

JNIEXPORT int JNICALL Java_com_example_mymp4v2h264_MainActivity0_Mp4Mkv0
 (JNIEnv *env, jclass clz, jstring mp4_file, jstring mkv_file)
{
    AVOutputFormat *ofmt = NULL;
    AVFormatContext *ifmt_ctx = NULL, *ofmt_ctx = NULL;
    AVStream *in_stream, *out_stream;
    AVCodecContext *c_pctx;
    int video_index=0;
    int audio_index=0;
    const char *in_filename, *out_filename;
    int ret, i;
    AVPacket pkt;
    AVPacket enc_pkt;
    AVFrame *frame = NULL;
    int frame_index = 0;
    int got_frame=0;
    int count_cc = 0;

    in_filename = (*env)->GetStringUTFChars(env, mp4_file, NULL);
    out_filename = (*env)->GetStringUTFChars(env, mkv_file, NULL);

    LOGI("              Mp4Mkv %s             ", in_filename);
    LOGI("              Mp4Mkv %s             ", out_filename);

    av_register_all();
    avcodec_register_all();

    if ((ret = avformat_open_input(&ifmt_ctx, in_filename, 0, 0)) < 0) {
        LOGI("Could not open input file '%s'", in_filename);
        goto end;
    }
    LOGI("              Mp4Mkv avformat_open_input            ");

    if ((ret = avformat_find_stream_info(ifmt_ctx, 0)) < 0) {
        LOGI("Failed to retrieve input stream information");
        goto end;
    }
    LOGI("              Mp4Mkv avformat_find_stream_info             ");

    av_dump_format(ifmt_ctx, 0, in_filename, 0);

    avformat_alloc_output_context2(&ofmt_ctx, NULL, NULL, out_filename);
    if (!ofmt_ctx) {
        LOGI("Could not create output context\n");
        ret = AVERROR_UNKNOWN;
        goto end;
    }

    LOGI("              Mp4Mkv avformat_alloc_output_context2             ");

    ofmt = ofmt_ctx->oformat;

    for (i = 0; i < ifmt_ctx->nb_streams; i++) {
        AVStream *in_stream = ifmt_ctx->streams[i];
        if (in_stream->codec->codec_type == AVMEDIA_TYPE_VIDEO){
        	LOGI(" find a video stream  %d\n", i);
        	// find the mpeg1 video encoder
           /*
        	AVCodec *ptrcodec = avcodec_find_decoder(in_stream->codec->codec_id);
        	if (!ptrcodec) {
        		LOGI("  avcodec_find_decoder failed  \n");
        		break;
        	}

        	c_pctx = avcodec_alloc_context3(ptrcodec);
            if (!c_pctx) {
        		LOGI(" avcodec_alloc_context3 failed \n");
        		break;
            }

            c_pctx = ifmt_ctx->streams[i]->codec;

        	//if (ptrcodec->capabilities & AV_CODEC_CAP_TRUNCATED)
        	//	c_pctx->flags |= AV_CODEC_FLAG_TRUNCATED; // we do not send complete frames

        	int retr = avcodec_open2(c_pctx, ptrcodec, NULL);
        	if (retr < 0) {
        		LOGI("  avcodec_open2 failed \n");
        		break;
        	}
        	*/
        	AVStream *stream;
        	AVStream *out_stream;
        	AVCodecContext *codec_ctx;
        	AVCodecContext *pCodecCtx;
        	AVCodec *encoder;

        	video_index = i;
        	stream = ifmt_ctx->streams[i];
        	codec_ctx = stream->codec;
        	/* Reencode video & audio and remux subtitles etc. */
        	ret = avcodec_open2(codec_ctx, avcodec_find_decoder(codec_ctx->codec_id), NULL);
        	if (ret < 0) {
        		LOGI("Failed to open decoder for stream #%u\n", i);
        	    break;
        	}

        	encoder = avcodec_find_encoder(AV_CODEC_ID_H264);
        	if(!encoder) {
        	   LOGI("  avcodec_find_encoder failed  \n");
        	   break;
        	}

        	out_stream = avformat_new_stream(ofmt_ctx, encoder);
        	if (!out_stream) {
        	    LOGI("avformat_new_stream failed\n");
        	    break;
        	}
        	out_stream->id = ofmt_ctx->nb_streams-1;
        	LOGI(" out_stream->id = %d, %d\n", out_stream->id, ofmt_ctx->nb_streams);
        	//Param that must set
            pCodecCtx = out_stream->codec;
            //pCodecCtx->codec_id =AV_CODEC_ID_HEVC;
            pCodecCtx->codec_id = AV_CODEC_ID_H264;//fmt->video_codec;
            pCodecCtx->codec_type = AVMEDIA_TYPE_VIDEO;
        	pCodecCtx->pix_fmt = AV_PIX_FMT_YUV420P;
            pCodecCtx->bit_rate = 1000000;
        	pCodecCtx->max_b_frames = 3;
        	pCodecCtx->width  = stream->codec->width;
        	pCodecCtx->height = stream->codec->height;
        	pCodecCtx->time_base.num = stream->time_base.num;
        	pCodecCtx->time_base.den = stream->time_base.den;
        	pCodecCtx->gop_size = stream->time_base.den;
            pCodecCtx->i_quant_factor = 1.0 / 1.40f; // x4->params.rc.f_ip_factor = 1 / fabs(avctx->i_quant_factor);
        	pCodecCtx->b_quant_factor = 1.30f;
        	/*
        	ret = avcodec_open2(pCodecCtx, avcodec_find_encoder(pCodecCtx->codec_id), NULL);
        	if (ret < 0) {
        	    LOGI("Failed to avcodec_open2 \n");
        	    break;
        	}
            */
        	/*
        	encoder = avcodec_find_encoder(pCodecCtx->codec_id);
        	if(!encoder) {
        	    LOGI("  avcodec_find_encoder failed  \n");
        	    break;
        	}
        	ret = avcodec_open2(pCodecCtx, encoder, NULL);
        	if (ret < 0) {
        		 LOGI("Cannot open video encoder for stream \n");
        	    break;
        	}
            */

        	out_stream->codec->codec_tag = 0;
        	if (ofmt_ctx->oformat->flags & AVFMT_GLOBALHEADER){
        	    //out_stream->codec->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
        		pCodecCtx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
        	}
        }else{
        	audio_index = i;
        	LOGI(" find a audio stream %d \n", i);
        	AVStream *out_stream = avformat_new_stream(ofmt_ctx, in_stream->codec->codec);
        	if (!out_stream) {
        	    LOGI("Failed allocating output stream\n");
        	    ret = AVERROR_UNKNOWN;
        	    goto end;
        	}

           ret = avcodec_copy_context(out_stream->codec, in_stream->codec);
           if (ret < 0) {
               LOGI("Failed to copy context from input to output stream codec context\n");
               goto end;
           }
           out_stream->codec->codec_tag = 0;
           if (ofmt_ctx->oformat->flags & AVFMT_GLOBALHEADER){
               out_stream->codec->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
           }
        }
    }
    av_dump_format(ofmt_ctx, 0, out_filename, 1);

    if (!(ofmt->flags & AVFMT_NOFILE)) {
        ret = avio_open(&ofmt_ctx->pb, out_filename, AVIO_FLAG_WRITE);
        if (ret < 0) {
            LOGI("Could not open output file '%s'", out_filename);
            goto end;
        }
    }

    ret = avformat_write_header(ofmt_ctx, NULL);
    if (ret < 0) {
        LOGI("Error occurred when opening output file\n");
        goto end;
    }
    LOGI("              Mp4Mkv avformat_write_header             ");

    LOGI("              ofmt_ctx->nb_streams = %d           ",ofmt_ctx->nb_streams);

    while (1) {
    	av_init_packet(&pkt);
    	pkt.size = 0;
    	pkt.data = NULL;

    	av_init_packet(&enc_pkt);
    	enc_pkt.data = NULL;
    	enc_pkt.size = 0;
        ret = av_read_frame(ifmt_ctx, &pkt);
        if (ret < 0){
        	LOGI("Error av_read_frame \n");
            break;
        }
        LOGI(" pkt.stream_index = %d, video = %d, audio = %d\n", pkt.stream_index, video_index, audio_index);

        if(audio_index == pkt.stream_index)
        {
        	        in_stream  = ifmt_ctx->streams[pkt.stream_index];
        	        out_stream = ofmt_ctx->streams[pkt.stream_index];
        	        LOGI("         audio_index        ");
        	        if(pkt.pts==AV_NOPTS_VALUE)
        	        {
        	           //Write PTS
        	           AVRational time_base1=in_stream->time_base;
        	           //Duration between 2 frames (us)
        	           int64_t calc_duration=(double)AV_TIME_BASE/av_q2d(in_stream->r_frame_rate);
        	           //Parameters
        	           pkt.pts=(double)(frame_index*calc_duration)/(double)(av_q2d(time_base1)*AV_TIME_BASE);
        	           pkt.dts=pkt.pts;
        	           pkt.duration=(double)calc_duration/(double)(av_q2d(time_base1)*AV_TIME_BASE);
        	        }
        	        frame_index++;
        	        //log_packet(ifmt_ctx, &pkt, "in");

        	        /* copy packet */
        	        pkt.pts = av_rescale_q_rnd(pkt.pts, in_stream->time_base, out_stream->time_base, AV_ROUND_NEAR_INF|AV_ROUND_PASS_MINMAX);
        	        pkt.dts = av_rescale_q_rnd(pkt.dts, in_stream->time_base, out_stream->time_base, AV_ROUND_NEAR_INF|AV_ROUND_PASS_MINMAX);
        	        pkt.duration = av_rescale_q(pkt.duration, in_stream->time_base, out_stream->time_base);
        	        pkt.pos = -1;
        	        //log_packet(ofmt_ctx, &pkt, "out");

        	        ret = av_interleaved_write_frame(ofmt_ctx, &pkt);
        	        if (ret < 0) {
        	            LOGI("Error muxing packet\n");
        	            break;
        	        }else{
        	        	LOGI("  audio  muxing packet %d\n", frame_index);
        	        }
        }
        if(video_index == pkt.stream_index)
        {
        	            count_cc++;
        	            LOGI("         video_index  = %d      ",count_cc);
        	            //frame_index = video_index;
        	            frame = av_frame_alloc();
        	            if (!frame) {
        	                //ret = AVERROR(ENOMEM);
        	                LOGI("Decoding failed\n");
        	                break;
        	            }
        	            av_packet_rescale_ts(&pkt,
        	            		             ifmt_ctx->streams[video_index]->time_base,
        	                                 ifmt_ctx->streams[video_index]->codec->time_base);
        	            ret = avcodec_decode_video2(ifmt_ctx->streams[video_index]->codec, frame, &got_frame, &pkt);

        	            //LOGI("frame->fmt %d, %d, %d\n",ifmt_ctx->streams[video_index]->codec->pix_fmt, AV_PIX_FMT_YUV420P, frame->pix_fmt);

        	            //ret = avcodec_decode_video2(c_pctx, frame, &got_frame, &pkt);
        	            if (ret < 0) {
        	                av_frame_free(&frame);
        	                LOGI("video avcodec_decode_video2 failed\n");
        	                break;
        	            }
        	            if (got_frame) {
        	            	//LOGI("video avcodec_decode_video2 ok\n");
        	                //frame->pts = av_frame_get_best_effort_timestamp(frame);
        	                //ret = filter_encode_write_frame(frame, stream_index);
        	            	//LOGI("video avcodec_decode_video2 ok\n");
        	            	int got_frame0 = 0;

        	            	AVFrame *frame0 = av_frame_alloc();

        	            	if (ifmt_ctx->streams[video_index]->codec->pix_fmt != AV_PIX_FMT_YUV420P) {
        	            	        /* as we only generate a YUV420P picture, we must convert it
        	            	         * to the codec pixel format if needed */
        	            	        if (!sws_ctx) {
        	            	            sws_ctx = sws_getContext(ifmt_ctx->streams[video_index]->codec->width,
        	            	            		                 ifmt_ctx->streams[video_index]->codec->height,
        	            	                                     AV_PIX_FMT_YUV420P,
        	            	                                     ifmt_ctx->streams[video_index]->codec->width,
        	            	                                     ifmt_ctx->streams[video_index]->codec->height,
        	            	                                     ifmt_ctx->streams[video_index]->codec->pix_fmt,
        	            	                                     SCALE_FLAGS, NULL, NULL, NULL);
        	            	            if (!sws_ctx) {
        	            	            	LOGI("Could not initialize the conversion context\n");
        	            	                //break;
        	            	            }else{
        	            	            	LOGI(" sws_getContext  ok\n");
        	            	            }
        	            	        }
        	            	        LOGI(" sws_getContext  ok\n");
        	            	        /*
        	            	        fill_yuv_image(ost->tmp_frame, ost->next_pts, c->width, c->height);
        	            	        sws_scale(sws_ctx,
        	            	                  (const uint8_t * const *)ost->tmp_frame->data, ost->tmp_frame->linesize,
        	            	                  0, ifmt_ctx->streams[video_index]->codec->height,
        	            	                  ost->frame->data,
        	            	                  ost->frame->linesize);
        	            	        */
        	            	        sws_scale(sws_ctx,
        	            	        		   (const uint8_t * const *)frame->data,
        	            	        		    frame->linesize,
        	            	                	0,
        	            	                	ifmt_ctx->streams[video_index]->codec->height,
        	            	                	frame0->data,
        	            	                	frame0->linesize);

        	            	}

        	            	LOGI("video avcodec_decode_video2 ok\n");
        	                ret = avcodec_encode_video2(ofmt_ctx->streams[pkt.stream_index]->codec, &enc_pkt, frame0, &got_frame0);
        	                //int ret = avcodec_encode_video2(pCodecCtx, &pkt, pFrame, &got_picture);

        	                LOGI("video avcodec_encode_video2 failed\n");
        	                av_frame_free(&frame);
        	                av_frame_free(&frame0);
        	                if (ret < 0) {
        	                	LOGI("video avcodec_encode_video2 failed\n");
        	                	break;
        	                }
        	                if (got_frame0){
        	                  LOGI("video avcodec_encode_video2 ok\n");
        	                   /* prepare packet for muxing */
        	                   enc_pkt.stream_index = video_index;
        	                   av_packet_rescale_ts(&enc_pkt,
        	                                        ofmt_ctx->streams[video_index]->codec->time_base,
        	                                        ofmt_ctx->streams[video_index]->time_base);

        	                   ret = av_interleaved_write_frame(ofmt_ctx, &enc_pkt);
        	                   if (ret < 0) {
        	                       LOGI("Error muxing packet\n");
        	                       break;
        	                   }else{
        	                       LOGI("  video  muxing packet %d\n", frame_index);
        	                   }
        	               }
        	            }else{
        	               av_frame_free(&frame);
        	            }
        }
        //av_packet_unref(&pkt);
    }
    LOGI("              Mp4Mkv av_write_trailer             ");

    av_write_trailer(ofmt_ctx);
end:
    LOGI("              Mp4Mkv avformat_close_input             ");
    avformat_close_input(&ifmt_ctx);

    /* close output */
    if (ofmt_ctx && !(ofmt->flags & AVFMT_NOFILE))
        avio_closep(&ofmt_ctx->pb);
    avformat_free_context(ofmt_ctx);

    if (ret < 0 && ret != AVERROR_EOF) {
        LOGI("Error occurred: %s\n", av_err2str(ret));
        return 1;
    }

	(*env)->ReleaseStringUTFChars(env, mp4_file, in_filename);
	(*env)->ReleaseStringUTFChars(env, mkv_file, out_filename);

    return 0;
}

JNIEXPORT int JNICALL Java_com_example_mymp4v2h264_MainActivity0_Mp4Mkv
 (JNIEnv *env, jclass clz, jstring mp4_file, jstring mkv_file)
{
    AVOutputFormat *ofmt = NULL;
    AVFormatContext *ifmt_ctx = NULL, *ofmt_ctx = NULL;
    AVStream *in_stream, *out_stream;

    const char *in_filename, *out_filename;
    int ret, i;

    in_filename = (*env)->GetStringUTFChars(env, mp4_file, NULL);
    out_filename = (*env)->GetStringUTFChars(env, mkv_file, NULL);

    LOGI("              Mp4Mkv %s             ", in_filename);
    LOGI("              Mp4Mkv %s             ", out_filename);

    av_register_all();

    if ((ret = avformat_open_input(&ifmt_ctx, in_filename, 0, 0)) < 0) {
        LOGI("Could not open input file '%s'", in_filename);
        goto end;
    }
    LOGI("              Mp4Mkv avformat_open_input            ");

    if ((ret = avformat_find_stream_info(ifmt_ctx, 0)) < 0) {
        LOGI("Failed to retrieve input stream information");
        goto end;
    }
    LOGI("              Mp4Mkv avformat_find_stream_info             ");

    av_dump_format(ifmt_ctx, 0, in_filename, 0);

    avformat_alloc_output_context2(&ofmt_ctx, NULL, NULL, out_filename);
    if (!ofmt_ctx) {
        LOGI("Could not create output context\n");
        ret = AVERROR_UNKNOWN;
        goto end;
    }

    LOGI("              Mp4Mkv avformat_alloc_output_context2             ");

    ofmt = ofmt_ctx->oformat;

    for (i = 0; i < ifmt_ctx->nb_streams; i++) {
        AVStream *in_stream = ifmt_ctx->streams[i];
        AVStream *out_stream = avformat_new_stream(ofmt_ctx, in_stream->codec->codec);
        if (!out_stream) {
            LOGI("Failed allocating output stream\n");
            ret = AVERROR_UNKNOWN;
            goto end;
        }

        ret = avcodec_copy_context(out_stream->codec, in_stream->codec);
        if (ret < 0) {
            LOGI("Failed to copy context from input to output stream codec context\n");
            goto end;
        }
        out_stream->codec->codec_tag = 0;
        if (ofmt_ctx->oformat->flags & AVFMT_GLOBALHEADER)
            out_stream->codec->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    }
    av_dump_format(ofmt_ctx, 0, out_filename, 1);

    if (!(ofmt->flags & AVFMT_NOFILE)) {
        ret = avio_open(&ofmt_ctx->pb, out_filename, AVIO_FLAG_WRITE);
        if (ret < 0) {
            LOGI("Could not open output file '%s'", out_filename);
            goto end;
        }
    }

    ret = avformat_write_header(ofmt_ctx, NULL);
    if (ret < 0) {
        LOGI("Error occurred when opening output file\n");
        goto end;
    }
    LOGI("              Mp4Mkv avformat_write_header             ");
    AVPacket pkt;
    int frame_index = 0;
    while (1) {
    	av_init_packet(&pkt);
    	pkt.size = 0;
    	pkt.data = NULL;

        ret = av_read_frame(ifmt_ctx, &pkt);
        if (ret < 0){
        	LOGI("Error av_read_frame \n");
            break;
        }

        in_stream  = ifmt_ctx->streams[pkt.stream_index];
        out_stream = ofmt_ctx->streams[pkt.stream_index];

        if(pkt.pts==AV_NOPTS_VALUE)
        {
           //Write PTS
           AVRational time_base1=in_stream->time_base;
           //Duration between 2 frames (us)
           int64_t calc_duration=(double)AV_TIME_BASE/av_q2d(in_stream->r_frame_rate);
           //Parameters
           pkt.pts=(double)(frame_index*calc_duration)/(double)(av_q2d(time_base1)*AV_TIME_BASE);
           pkt.dts=pkt.pts;
           pkt.duration=(double)calc_duration/(double)(av_q2d(time_base1)*AV_TIME_BASE);
        }
        frame_index++;
        //log_packet(ifmt_ctx, &pkt, "in");

        /* copy packet */
        pkt.pts = av_rescale_q_rnd(pkt.pts, in_stream->time_base, out_stream->time_base, AV_ROUND_NEAR_INF|AV_ROUND_PASS_MINMAX);
        pkt.dts = av_rescale_q_rnd(pkt.dts, in_stream->time_base, out_stream->time_base, AV_ROUND_NEAR_INF|AV_ROUND_PASS_MINMAX);
        pkt.duration = av_rescale_q(pkt.duration, in_stream->time_base, out_stream->time_base);
        pkt.pos = -1;
        //log_packet(ofmt_ctx, &pkt, "out");

        ret = av_interleaved_write_frame(ofmt_ctx, &pkt);
        if (ret < 0) {
            LOGI("Error muxing packet\n");
            break;
        }else{
        	LOGI("    muxing packet %d\n", frame_index);
        }
        //av_packet_unref(&pkt);
    }
    LOGI("              Mp4Mkv av_write_trailer             ");

    av_write_trailer(ofmt_ctx);
end:
    LOGI("              Mp4Mkv avformat_close_input             ");
    avformat_close_input(&ifmt_ctx);

    /* close output */
    if (ofmt_ctx && !(ofmt->flags & AVFMT_NOFILE))
        avio_closep(&ofmt_ctx->pb);
    avformat_free_context(ofmt_ctx);

    if (ret < 0 && ret != AVERROR_EOF) {
        LOGI("Error occurred: %s\n", av_err2str(ret));
        return 1;
    }

	(*env)->ReleaseStringUTFChars(env, mp4_file, in_filename);
	(*env)->ReleaseStringUTFChars(env, mkv_file, out_filename);

    return 0;
}

/*****************************************************************************/
/*****************************************************************************/
AVCodec *ptrcodec;
AVCodecContext *pctx= NULL;
FILE *ptrf;
FILE *ptrfo;
AVFrame *ptrframe;
AVPacket avpkt;
uint8_t endcode[] = { 0, 0, 1, 0xb7 };

JNIEXPORT void JNICALL Java_com_example_mymp4v2h264_MainActivity0_Mp4FpgCC
 (JNIEnv *env, jclass clz, jstring h264_file)
{
	    const char* h264_file_n = (*env)->GetStringUTFChars(env, h264_file, NULL);
	    filename = h264_file_n;
	    int ret;
	    int codec_id = AV_CODEC_ID_H264;

	    avcodec_register_all();

	    LOGI("              Mp4FpgCC %s             ", filename);

	    /* find the mpeg1 video encoder */
	    ptrcodec = avcodec_find_encoder(codec_id);
	    if (!ptrcodec) {
	    	LOGI("Codec not found\n");
	        exit(1);
	    }

	    pctx = avcodec_alloc_context3(ptrcodec);
	    if (!pctx) {
	    	LOGI("Could not allocate video codec context\n");
	        exit(1);
	    }

	    /*
	    1.Bitrate-based固定比特率；
	    2.Quality-based动态比特率，基于质量模式，文件大小不可控；
	    3.Two-Pass转换两遍
	    4.Three-Pass转换三遍。
	    */
        /*
	    pctx->profile =FF_PROFILE_H264_MAIN ;
	    pctx->time_base.den = 24;
	    pctx->time_base.num = 1;
	    pctx->gop_size = 8; // emit one intra frame every twelve frames at most
	    pctx->pix_fmt = AV_PIX_FMT_YUV420P;
	    pctx->max_b_frames = 0;
	    pctx->has_b_frames = 0;
	    av_opt_set(pctx->priv_data, "preset", "slow", 0); ///
	    av_opt_set(pctx->priv_data, "tune", "zerolatency", 0);

	    pctx->bit_rate = br ;
	    pctx->rc_min_rate = br ;
	    pctx->rc_max_rate = br ;
	    pctx->bit_rate_tolerance = br;
	    pctx->rc_buffer_size = br;
	    pctx->rc_initial_buffer_occupancy = pctx->rc_buffer_size*3/4;
	    pctx->rc_buffer_aggressivity = (float)1.0;
	    pctx->rc_initial_cplx = 0.5;
	    pctx->i_quant_factor = -1;
	    */
	    //pctx->profile =FF_PROFILE_H264_MAIN;

	    /*
	    //int br = 2000000;
	    // put sample parameters
	    pctx->bit_rate    = br;
	    pctx->rc_min_rate = br;
	    pctx->rc_max_rate = br;
	    pctx->bit_rate_tolerance = br;
	    pctx->rc_buffer_size = br;
	    pctx->rc_initial_buffer_occupancy = pctx->rc_buffer_size*3/4;
	    pctx->rc_buffer_aggressivity = (float)1.0;
	    pctx->rc_initial_cplx = 0.5;
	    pctx->i_quant_factor = -1; //i_quant_factor 相当于x264的参数ipratio 默认值：1.40

	    //Optional Param
	    pctx->max_b_frames = 0;
	    pctx->has_b_frames = 0;
	    // resolution must be a multiple of two
	    pctx->width  = 640;
	    pctx->height = 480;
	    // frames per second
	    pctx->time_base = (AVRational){1,25};
	    pctx->gop_size = 10;
        pctx->pix_fmt = AV_PIX_FMT_YUV420P;

	    //pctx->qmin = 10;
	    //pctx->qmax = 51;
        */

	    /*
	    int bitrate = 1000;
        int fps = 25;
	        //pctx->codec_type = CODEC_TYPE_VIDEO;
	    	pctx->bit_rate = bitrate * 1000;
	    	pctx->width = width;
	    	pctx->height = height;
	    	pctx->time_base.den = fps;
	    	pctx->time_base.num = 1;
	    	pctx->gop_size = fps * 10;
	    	//pctx->gop_size = 1;
	    	//pctx->crf = 26;
	    	pctx->refs = 3;
	    	//pctx->flags2 = CODEC_FLAG2_MIXED_REFS;
	    	pctx->max_b_frames = 3;
	    	//pctx->deblockbeta = -1;
	    	//pctx->deblockalpha = -1;
	    	pctx->trellis = 2;
	    	//pctx->partitions = X264_PART_I4X4 | X264_PART_I8X8 | X264_PART_P8X8 | X264_PART_P4X4 | X264_PART_B8X8;
	    	//pctx->flags2 |= CODEC_FLAG2_8X8DCT;
	    	pctx->me_method = 8;
	    	pctx->me_range = 16;
	    	pctx->me_subpel_quality = 7;
	    	pctx->qmin = 10;
	    	pctx->qmax = 51;
	    	pctx->rc_initial_buffer_occupancy = 0.9;
	     	pctx->i_quant_factor = 1.0 / 1.40f; // x4->params.rc.f_ip_factor = 1 / fabs(avctx->i_quant_factor);
	     	pctx->b_quant_factor = 1.30f;
	    	pctx->chromaoffset = 0;
	    	pctx->max_qdiff = 4;
	    	pctx->qcompress = 0.6f;
	    	//pctx->complexityblur = 20.0f;
	    	pctx->qblur = 0.5f;
	    	//pctx->directpred = 1;
	    	pctx->noise_reduction = 0;
	    	pctx->pix_fmt = AV_PIX_FMT_YUV420P;

	     	pctx->thread_count = 1;
	     	pctx->scenechange_threshold = 40;
	     	//pctx->bframebias = 0;
	     	//pctx->flags2 |= CODEC_FLAG2_AUD;
	     	//pctx->coder_type = FF_CODER_TYPE_AC;
	     	pctx->flags |= CODEC_FLAG_LOOP_FILTER;
	     	pctx->me_cmp = FF_CMP_CHROMA;
	     	pctx->flags2 |= CODEC_FLAG_NORMALIZE_AQP;
	     	pctx->keyint_min = 25;
	     	//pctx->flags2 |= CODEC_FLAG2_MBTREE;

	    // 	pctx->rc_min_rate =1024 * 1000;
	    // 	pctx->rc_max_rate = 1024 * 1000;
	    // 	pctx->bit_rate_tolerance = 1024 * 1000;
	    // 	pctx->rc_buffer_size=1024 * 1000;
	    // 	pctx->rc_initial_buffer_occupancy = pctx->rc_buffer_size*3/4;
	    // 	pctx->rc_buffer_aggressivity= (float)1.0;
	    // 	pctx->rc_initial_cplx= 0.5;

	    	//pctx->ticks_per_frame = 2;
	    	pctx->level = 30;
	    	pctx->b_frame_strategy = 2;
	    	pctx->codec_tag = 7; // FLV must not = 0  {CODEC_ID_H264,    FLV_CODECID_H264  },
       */

	    /*
	    //AVCodecContext 相当于虚基类，需要用具体的编码器实现来给他赋值
	    pCodecCtxEnc = video_st->codec;

	    //编码器的ID号，这里我们自行指定为264编码器，实际上也可以根据video_st里的codecID 参数赋值
	    pCodecCtxEnc->codec_id = AV_CODEC_ID_H264;

	    //编码器编码的数据类型
	    pCodecCtxEnc->codec_type = AVMEDIA_TYPE_VIDEO;

	    //目标的码率，即采样的码率；显然，采样码率越大，视频大小越大
	    pCodecCtxEnc->bit_rate = 200000;

	    //固定允许的码率误差，数值越大，视频越小
	    pCodecCtxEnc->bit_rate_tolerance = 4000000;

	    //编码目标的视频帧大小，以像素为单位
	    pCodecCtxEnc->width = 640;
	    pCodecCtxEnc->height = 480;

	    //帧率的基本单位，我们用分数来表示，
	    //用分数来表示的原因是，有很多视频的帧率是带小数的eg：NTSC 使用的帧率是29.97
	    pCodecCtxEnc->time_base.den = 30;
	    pCodecCtxEnc->time_base = (AVRational){1,25};
	    pCodecCtxEnc->time_base.num = 1;

	    //像素的格式，也就是说采用什么样的色彩空间来表明一个像素点
	    pCodecCtxEnc->pix_fmt = PIX_FMT_YUV420P;

	    //每250帧插入1个I帧，I帧越少，视频越小

	    [cpp] view plain copy
	    pCodecCtxEnc->gop_size = 250;

	    //两个非B帧之间允许出现多少个B帧数
	    //设置0表示不使用B帧
	    //b 帧越多，图片越小
	    pCodecCtxEnc->max_b_frames = 0;

	    //运动估计
	    pCodecCtxEnc->pre_me = 2;

	    //设置最小和最大拉格朗日乘数
	    //拉格朗日乘数 是统计学用来检测瞬间平均值的一种方法
	    pCodecCtxEnc->lmin = 1;
	    pCodecCtxEnc->lmax = 5;

	    //最大和最小量化系数
	    pCodecCtxEnc->qmin = 10;
	    pCodecCtxEnc->qmax = 50;

	    //因为我们的量化系数q是在qmin和qmax之间浮动的，
	    //qblur表示这种浮动变化的变化程度，取值范围0.0～1.0，取0表示不削减
	    pCodecCtxEnc->qblur = 0.0;

	    //空间复杂度的masking力度，取值范围 0.0-1.0
	    pCodecCtxEnc->spatial_cplx_masking = 0.3;

	    //运动场景预判功能的力度，数值越大编码时间越长
	    pCodecCtxEnc->me_pre_cmp = 2;

	    //采用（qmin/qmax的比值来控制码率，1表示局部采用此方法，）
	    pCodecCtxEnc->rc_qsquish = 1;

	    //设置 i帧、p帧与B帧之间的量化系数q比例因子，这个值越大，B帧越不清楚
	    //B帧量化系数 = 前一个P帧的量化系数q * b_quant_factor + b_quant_offset
	    pCodecCtxEnc->b_quant_factor = 1.25;

	    //i帧、p帧与B帧的量化系数便宜量，便宜越大，B帧越不清楚
	    pCodecCtxEnc->b_quant_offset = 1.25;

	    //p和i的量化系数比例因子，越接近1，P帧越清楚
	    //p的量化系数 = I帧的量化系数 * i_quant_factor + i_quant_offset
	    pCodecCtxEnc->i_quant_factor = 0.8;
	    pCodecCtxEnc->i_quant_offset = 0.0;

	    //码率控制测率，宏定义，查API
	    pCodecCtxEnc->rc_strategy = 2;

	    //b帧的生成策略
	    pCodecCtxEnc->b_frame_strategy = 0;

	    //消除亮度和色度门限
	    pCodecCtxEnc->luma_elim_threshold = 0;
	    pCodecCtxEnc->chroma_elim_threshold = 0;

	    //DCT变换算法的设置，有7种设置，这个算法的设置是根据不同的CPU指令集来优化的取值范围在0-7之间
	    pCodecCtxEnc->dct_algo = 0;

	    //这两个参数表示对过亮或过暗的场景作masking的力度，0表示不作
	    pCodecCtxEnc->lumi_masking = 0.0;
	    pCodecCtxEnc->dark_masking = 0.0;
	    */
	            /*
	            int bitrate = 1000;
	            int fps = 25;
	   	        //pctx->codec_type = CODEC_TYPE_VIDEO;
	   	    	pctx->bit_rate = bitrate * 1000;
	   	    	pctx->width  = width;
	   	    	pctx->height = height;
	   	    	pctx->time_base.den = fps;
	   	    	pctx->time_base.num = 1;
	   	    	pctx->gop_size = fps * 10;
	   	    	//pctx->refs = 3;
	   	    	pctx->max_b_frames = 3;
	   	    	//pctx->trellis = 2;
	   	    	pctx->me_method = 8;
	   	    	pctx->me_range = 64;//16;
	   	    	//pctx->me_subpel_quality = 7;
	   	    	//pctx->qmin = 10;
	   	    	//pctx->qmax = 51;
	   	    	pctx->rc_initial_buffer_occupancy = 0.9;
	   	     	pctx->i_quant_factor = 1.0 / 1.40f; // x4->params.rc.f_ip_factor = 1 / fabs(avctx->i_quant_factor);
	   	     	pctx->b_quant_factor = 1.30f;
	   	    	//pctx->chromaoffset = 0;
	   	    	pctx->max_qdiff = 4;
	   	    	pctx->qcompress = 0.6f;
	   	    	pctx->qblur = 0.5f;
	   	    	pctx->pix_fmt = AV_PIX_FMT_YUV420P;

	   	     	//pctx->scenechange_threshold = 40;

	   	     	//pctx->flags |= CODEC_FLAG_LOOP_FILTER;
	   	     	//pctx->me_cmp = FF_CMP_CHROMA;

	   	     	//pctx->flags2 |= CODEC_FLAG_NORMALIZE_AQP;
	   	     	//pctx->keyint_min = 25;

	   	    	//pctx->level = 30;
	   	    	//pctx->b_frame_strategy = 2;
	   	    	//pctx->codec_tag = 7;
	    	   	*/
                        /*
	                    int bitrate = 1000;
	    	            int fps = 25;
	    	   	        //pctx->codec_type = CODEC_TYPE_VIDEO;
	    	   	    	pctx->bit_rate = bitrate * 1000;
	    	   	    	pctx->width  = width;
	    	   	    	pctx->height = height;
	    	   	    	pctx->time_base.den = fps;
	    	   	    	pctx->time_base.num = 1;
	    	   	    	pctx->gop_size = fps * 10;
	    	   	    	//pctx->refs = 3;
	    	   	    	pctx->max_b_frames = 3;
	    	   	    	//pctx->trellis = 2;

	    	   	    	//pctx->me_method = 8;
	    	   	    	//pctx->me_range = 64;//16;

	    	   	    	//pctx->me_subpel_quality = 7;
	    	   	    	//pctx->qmin = 10;
	    	   	    	//pctx->qmax = 51;
	    	   	    	//pctx->rc_initial_buffer_occupancy = 0.9;
	    	   	     	pctx->i_quant_factor = 1.0 / 1.40f; // x4->params.rc.f_ip_factor = 1 / fabs(avctx->i_quant_factor);
	    	   	     	pctx->b_quant_factor = 1.30f;
	    	   	    	//pctx->chromaoffset = 0;

	    	   	    	pctx->max_qdiff = 4;
	    	   	    	//pctx->qcompress = 0.6f;
	    	   	    	//pctx->qblur = 0.5f;

	    	   	    	pctx->pix_fmt = AV_PIX_FMT_YUV420P;

	    	   	     	//pctx->scenechange_threshold = 40;

	    	   	     	//pctx->flags |= CODEC_FLAG_LOOP_FILTER;
	    	   	     	//pctx->me_cmp = FF_CMP_CHROMA;

	    	   	     	//pctx->flags2 |= CODEC_FLAG_NORMALIZE_AQP;
	    	   	     	//pctx->keyint_min = 25;

	    	   	    	//pctx->level = 30;
	    	   	    	//pctx->b_frame_strategy = 2;
	    	   	    	//pctx->codec_tag = 7;
	    	   	    	*/

/*
1.如果设置了AVCodecContext中bit_rate的大小，则采用abr的控制方式；
2.如果没有设置AVCodecContext中的bit_rate，则默认按照crf方式编码，crf默认大小为23（此值类似于qp值，同样表示视频质量）；
3.如果用户想自己设置，则需要借助av_opt_set函数设置AVCodecContext的priv_data参数。下面给出三种控制方式的实现代码：
int bpsValue;                       //码流控制方式的对应值
int bpsMode;                        //码流控制方式，0表示平均码率(abr)，1表示固定码率(crf)，2表示固定质量（cqp）
AVCodecContext* pCodecCtx;
.......

//码率控制方式
string modeValue = int2String(bpsValue);
switch (bpsMode) {
case 0:
    pCodecCtx->bit_rate = bpsValue*1000;
    break;
case 1:
    av_opt_set(pCodecCtx->priv_data,"crf",modeValue.c_str(),AV_OPT_SEARCH_CHILDREN);
    break;
case 2:
    av_opt_set(pCodecCtx->priv_data,"qp",modeValue.c_str(),AV_OPT_SEARCH_CHILDREN);
    break;
default:
    pCodecCtx->bit_rate = bpsValue;
    break;
}
*/
	            int bitrate = 1000;
	            int br = 1000*1000;
	            int fps = 25;
	   	        //pctx->codec_type = CODEC_TYPE_VIDEO;
	            /*
	            ffmpeg中VBR（可变率控制）的设置：
	                            c->flags |= CODEC_FLAG_QSCALE;
	                            c->rc_min_rate =min;
	                            c->rc_max_rate = max;
	                            c->bit_rate = br;
	                            */
	            /*
	            ffmpeg中CBR（固定码率控制）的设置：
	                            c->bit_rate = br;
	                            c->rc_min_rate =br;
	                            c->rc_max_rate = br;
	                            c->bit_rate_tolerance = br;
	                            c->rc_buffer_size=br;
	                            c->rc_initial_buffer_occupancy = c->rc_buffer_size*3/4;
	                            c->rc_buffer_aggressivity= (float)1.0;
	                            c->rc_initial_cplx= 0.5;
	            */
                /*
	            pctx->bit_rate = br;
	            pctx->rc_min_rate =br;
	            pctx->rc_max_rate = br;
	            pctx->bit_rate_tolerance = br;
	            pctx->rc_buffer_size=br;
	            pctx->rc_initial_buffer_occupancy = pctx->rc_buffer_size*3/4;
	            pctx->rc_buffer_aggressivity= (float)1.0;
	            pctx->rc_initial_cplx= 0.5f;
                */
	            //av_opt_set(pctx->priv_data,"crf", "1", AV_OPT_SEARCH_CHILDREN);

	   	    	//pctx->bit_rate = bitrate * 1000;
	   	    	//pctx->bit_rate_tolerance = 8000000;
	   	    	//表示有多少bit的视频流可以偏移出目前的设定.这里的"设定"是指的cbr或者vbr.
	   	    	pctx->width  = width;
	   	    	pctx->height = height;
	   	    	pctx->time_base.den = fps;
	   	    	pctx->time_base.num = 1;
	   	    	pctx->gop_size = fps;// * 10;
	   	    	//pctx->refs = 3;
	   	    	pctx->max_b_frames = 3;
	   	    	//pctx->trellis = 2;

	   	    	//pctx->me_method = 8;
	   	    	//pctx->me_range = 64;//16;

	   	    	//pctx->me_subpel_quality = 7;
	   	    	//pctx->qmin = 10; //介于0~31之间，值越小，量化越精细，图像质量就越高，而产生的码流也越长。
	   	    	//pctx->qmax = 51;
	   	    	//pctx->rc_initial_buffer_occupancy = 0.9;
	   	     	pctx->i_quant_factor = 1.0 / 1.40f;
	   	        //p和i的量化系数比例因子，越接近1，P帧越清楚
	   	        //p的量化系数 = I帧的量化系数 * i_quant_factor + i_quant_offset
	   	     	//p和i的Q值比例因子,越接近1则P越优化.
	   	     	// x4->params.rc.f_ip_factor = 1 / fabs(avctx->i_quant_factor);
	   	     	pctx->b_quant_factor = 1.30f; //表示i/p与B的Q值比例因子,值越大B桢劣化越严重
	   	    	//pctx->chromaoffset = 0;
	   	        //设置 i帧、p帧与B帧之间的量化系数q比例因子，这个值越大，B帧越不清楚
	   	        //B帧量化系数 = 前一个P帧的量化系数q * b_quant_factor + b_quant_offset

	   	     	//pctx->max_qdiff = 4;
	   	    	//pctx->qcompress = 0.6f; ////浮点数值，表示在压制“容易压的场景”和“难压的场景”时，允许Q值之比值的变化范围。可选值是0.0-1.0。
	   	    	//pctx->qblur = 0.5f; //浮点数，表示Q值的比例随时间消减的程度，取之范围是0.0-1.0，取0就是不消减

	   	    	pctx->pix_fmt = AV_PIX_FMT_YUV420P;

	   	     	//pctx->scenechange_threshold = 40;

	   	     	//pctx->flags |= CODEC_FLAG_LOOP_FILTER;
	   	     	//pctx->me_cmp = FF_CMP_CHROMA;

	   	     	//pctx->flags2 |= CODEC_FLAG_NORMALIZE_AQP;
	   	     	//pctx->keyint_min = 25;

	   	    	//pctx->rc_qsquish=1.0	//采用Qmin/Qmax的比值来限定和控制码率的方法。选1表示局部（即一个clip)采用此方法,选1表示全部采用。
	   	    	//pctx->level = 30;
	   	    	//pctx->b_frame_strategy = 2;
	   	    	//pctx->codec_tag = 7;

	   	    	//480P分辨率仅需1-2M的码率即可达到远超AVI格式的画质，一部电影大小仅为1G；720P分辨率也只需要3-4M码率和2-3G的体积
	   	    	//720P ，码流大约在2到4或者5M；1080P 大约在4到8M
	   	    	//码流概念码流（Data Rate），是指视频文件在单位时间内使用的数据流量，也叫码率

	   	    	//VBR是动态码率。CBR是静态码率。
	   	    	//三种可选的码率控制方法(bitrate, CQP，CRF), 选择的顺序是 bitrate > QP > CRF
	   	    	//QP是固定量化参数，bitrate是固定文件大小，crf则是固定“质量"
	   	    	//–qp, –bitrate, --crf
	   	    	//X264_RC_CQP 动态比特率
	   	    	//X264_RC_CRF 恒定比特率
	   	    	//X264_RC_ABR 平均比特率
	   	    	//abr(平均码率)，crf（限制码率），cqp（固定质量）

	   	    	//高码率编码（如下）， 中码率 = 高码率/2, 极高码率 = 高码率*2，低码率 = 中码率/2
	   	    	//480P  --> 250kbps   720P  --> 500kbps   1080P --> 1mps  极低码率
	   	    	//480P  --> 500kbps   720P  --> 1mbps     1080P --> 2mps  低码率
	   	    	//480P  --> 1mbps     720P  --> 2mbps     1080P --> 4mps  中码率
	   	    	//480P  --> 2mbps     720P  --> 4mbps     1080P --> 8mps  高码率
	   	    	//480P  --> 4mbps     720P  --> 8mbps     1080P --> 16mps 极高码率

	   	    	//通常高编码码率：
	   	    	//480P	720X480	    1800Kbps
	   	    	//720P	1280X720	3500Kbps
	   	    	//1080P	1920X1080	8500Kbps
/*
1、
x264编码时延问题
vcodec_encode_video2函数输出的延时仅仅跟max_b_frames的设置有关，
想进行实时编码，将max_b_frames设置为0便没有编码延时了
2、
使用264的API设置编码速度
// ultrafast,superfast, veryfast, faster, fast, medium slow, slower, veryslow, placebo.　这是x264编码速度的选项
av_opt_set(m_context->priv_data,"preset","ultrafast",0);
*/
	   	/*
	   	//编码器预设
	   	AVDictionary *dictParam = 0;
	    if(pCodecCtx->codec_id == AV_CODEC_ID_H264)
	    {
	   	   av_dict_set(&dictParam,"preset","medium",0);
	   	   av_dict_set(&dictParam,"tune","zerolatency",0);
	   	   av_dict_set(&dictParam,"profile","main",0);
	   	}
	    */

	    if (codec_id == AV_CODEC_ID_H264) {
	   	    //av_opt_set(pctx->priv_data, "preset", "slow", 0); //ultrafast
	   	    av_opt_set(pctx->priv_data, "preset", "ultrafast", 0); //ultrafast
	   	    av_opt_set(pctx->priv_data, "tune", "zerolatency", 0);
	   	    //av_opt_set(pctx->priv_data, "profile", "main", 0);
	   	}

	    /* open it */
	    if (avcodec_open2(pctx, ptrcodec, NULL) < 0) {
	    	LOGI("Could not open codec\n");
	        exit(1);
	    }

	    ptrf = fopen(filename, "wb");
	    if (!ptrf) {
	    	LOGI("Could not open %s\n", filename);
	        exit(1);
	    }

	    ptrframe = av_frame_alloc();
	    if (!ptrframe) {
	    	LOGI("Could not allocate video frame\n");
	        exit(1);
	    }
	    ptrframe->format =  pctx->pix_fmt;
	    ptrframe->width  =  pctx->width;
	    ptrframe->height =  pctx->height;

	    /* the image can be allocated by any means and av_image_alloc() is
	     * just the most convenient way if av_malloc() is to be used */
	    ret = av_image_alloc(ptrframe->data, ptrframe->linesize, pctx->width, pctx->height,
	    		pctx->pix_fmt, 32);
	    if (ret < 0) {
	    	LOGI("Could not allocate raw picture buffer\n");
	        exit(1);
	    }

	    picture_size=  pctx->width*pctx->height*3/2;
	    picture_buf = (uint8_t *)av_malloc(picture_size);

	    int y_size = pctx->width*pctx->height;
	    LOGI(" w = %d, h = %d, picture_size= %d, y_size = %d\n", pctx->width, pctx->height, picture_size, y_size);


	    ptrframe->data[0] = picture_buf;              // Y
	    ptrframe->data[1] = picture_buf+ y_size;      // U
	    ptrframe->data[2] = picture_buf+ y_size*5/4;  // V

	    av_init_packet(&avpkt);
	    avpkt.data = NULL;    // packet data will be allocated by the encoder
	    avpkt.size = 0;

	    framecnt  = 0;
	    ffmpeg_ce = 0;
	    frame_pts = 0;
	    (*env)->ReleaseStringUTFChars(env, h264_file, h264_file_n);
}

int total_st;
int total_stream;

JNIEXPORT void JNICALL Java_com_example_mymp4v2h264_MainActivity0_Mp4FpgCE
 (JNIEnv *env, jclass clz, jbyteArray data, jint size)
{
	    unsigned char *buf = (unsigned char *)(*env)->GetByteArrayElements(env, data, JNI_FALSE);

		memcpy(picture_buf, buf, picture_size);

		//PTS
		//ptrframe->pts= frame_pts++;
		//ptrframe->pts=frame_pts*1000/25;
		//ptrframe->pts=frame_pts++*(pctx->time_base.num*1000/pctx->time_base.den);
	    //frame_pts++;

		int got_picture=0;

		av_init_packet(&avpkt);
	    avpkt.data = NULL;    // packet data will be allocated by the encoder
		avpkt.size = 0;

		//视频时间戳
		//pts = inc++ *(1000/fps); //inc初始值为0，每次打完时间戳inc加1.
		//pkt.pts= m_nVideoTimeStamp++ * (pctx->time_base.num * 1000 / pctx->time_base.den);

		//Encode
		int ret = avcodec_encode_video2(pctx, &avpkt, ptrframe, &got_picture);
		if(ret < 0){
		    LOGI("              Mp4FpgCE Failed to encode!            ");
		    return ;
		}
		if (got_picture){   //if (got_picture==1){
			//LOGI("              Mp4FpgCE %d            ",frame_pts);
		    //framecnt++;

		    //if (pctx->coded_frame->pts != AV_NOPTS_VALUE) {
		    //	avpkt.pts= av_rescale_q(pctx->coded_frame->pts, pctx->time_base, ost->st->time_base);
		    //}
		    //pkt.stream_index = video_st0->index;
			//avpkt.pts = frame_pts*1000*/25;
			total_st++;
			total_stream += avpkt.size;
			if(total_st>=25){
				LOGI("      Mp4FpgCE %f k, %d  %d    ",total_stream/1000.0f, total_stream, avpkt.size);
				total_st = 0;
				total_stream  = 0;
			}
			avpkt.pts = frame_pts*(pctx->time_base.num*1000/pctx->time_base.den);
			frame_pts++;

		    fwrite(avpkt.data, 1, avpkt.size, ptrf);
		    av_packet_unref(&avpkt);//av_free_packet(&pkt);
		}

		ffmpeg_ce++;
		//LOGI("              Mp4FpgCE %d, %d, %d, %f            ",frame_pts, pctx->time_base.den, pctx->time_base.num, ptrframe->pts);

		(*env)->ReleaseByteArrayElements(env, data, (jbyte *)buf, 0);

		//(*env)->ReleaseByteArrayElements(env, data, (jbyte *)yuv_buffer, 0);
}

JNIEXPORT void JNICALL Java_com_example_mymp4v2h264_MainActivity0_Mp4FpgEE
 (JNIEnv *env, jclass clz)
{
	LOGI("              Mp4FpgEE              ");
	int got_output = 0;
	int ret = avcodec_encode_video2(pctx, &avpkt, NULL, &got_output);
	if (ret < 0) {
	   LOGI("Error encoding frame\n");
	   //exit(1);
	}

	if (got_output) {
	   //if (c->coded_frame->pts != AV_NOPTS_VALUE) {
	   //    	   pkt.pts= av_rescale_q(c->coded_frame->pts, c->time_base, ost->st->time_base);
	   //}
	   avpkt.pts = frame_pts*(pctx->time_base.num*1000/pctx->time_base.den);
	   frame_pts++;

	   fwrite(avpkt.data, 1, avpkt.size, ptrf);
	   av_packet_unref(&avpkt);
	}
	/* add sequence end code to have a real mpeg file */
	fwrite(endcode, 1, sizeof(endcode), ptrf);
	fclose(ptrf);

	avcodec_close(pctx);
	av_free(pctx);
	av_freep(&ptrframe->data[0]);
	av_frame_free(&ptrframe);

}

const char *outfilename;
int frame_count;
//#define INBUF_SIZE  10*1024
//uint8_t inbuf[INBUF_SIZE + AV_INPUT_BUFFER_PADDING_SIZE];

static AVFormatContext *i_fmt_ctx;
AVStream *i_video_stream=NULL;
AVFormatContext *o_fmt_ctx;
AVStream *o_video_stream = NULL;

JNIEXPORT void JNICALL Java_com_example_mymp4v2h264_MainActivity0_Mp4FpgDCC
 (JNIEnv *env, jclass clz, jstring outfile_name, jstring h264_file)
{
	    const char* h264_file_n = (*env)->GetStringUTFChars(env, h264_file, NULL);
	    const char* h264_file_o = (*env)->GetStringUTFChars(env, outfile_name, NULL);

	    filename = h264_file_n;
	    outfilename = h264_file_o;

	    int ret, i;
	    int codec_id = AV_CODEC_ID_H264;

	    av_register_all();
	    avcodec_register_all();

	    LOGI("              Mp4FpgCC start              ");
	    //memset(inbuf + INBUF_SIZE, 0, AV_INPUT_BUFFER_PADDING_SIZE);
	    //LOGI("              Mp4FpgCC %s             ",h264_file_n);
	    //LOGI("              Mp4FpgCC %s             ",h264_file_o);

	    LOGI("              Mp4FpgCC %s             ",filename);
		LOGI("              Mp4FpgCC %s             ",outfilename);
	    /* open input file, and allocate format context */
	    if (avformat_open_input(&i_fmt_ctx, filename, NULL, NULL) < 0) {
	    	LOGI("Could not open source file %s\n", filename);
	    	return;
	    }

	    /* retrieve stream information */
	    if (avformat_find_stream_info(i_fmt_ctx, NULL) < 0) {
	        LOGI("Could not find stream information\n");
	        return;
	    }

	    LOGI("   i_fmt_ctx->nb_streams = %d\n", i_fmt_ctx->nb_streams);

	    /* find first video stream */
	    for (i=0; i<i_fmt_ctx->nb_streams; i++){
	       if (i_fmt_ctx->streams[i]->codec->codec_type == AVMEDIA_TYPE_VIDEO){
	    	   LOGI(" find a video stream \n");
	           i_video_stream = i_fmt_ctx->streams[i];
	           break;
	       }
	    }
	    if (!i_video_stream){
	    	LOGI("didn't find any video stream\n");
	        return;
	    }

	    int width = i_video_stream->codec->width;
	    int height =  i_video_stream->codec->height;

	    LOGI("   width = %d, height = %d\n", width, height);

	    av_dump_format(i_fmt_ctx, 0, filename, 0);

	    pctx = i_video_stream->codec;

	    ptrcodec = avcodec_find_decoder(pctx->codec_id); //i_video_stream->codec->codec
	    /*
	    // find the mpeg1 video encoder
	    ptrcodec = avcodec_find_decoder(AV_CODEC_ID_H264);
	    if (!ptrcodec) {
	    	LOGI("Codec not found\n");
	        exit(1);
	    }

	    pctx = avcodec_alloc_context3(ptrcodec);
	    if (!pctx) {
	    	LOGI("Could not allocate video codec context\n");
	        exit(1);
	    }
        */

	    //if (ptrcodec->capabilities & AV_CODEC_CAP_TRUNCATED)
	    //	pctx->flags |= AV_CODEC_FLAG_TRUNCATED; // we do not send complete frames

	    //pctx->width  = 640;
	    //pctx->height = 480;

	    /* open it */
	    if (avcodec_open2(pctx, ptrcodec, NULL) < 0) {
	    	LOGI("Could not open codec\n");
	        exit(1);
	    }

	    /*
	    ptrf = fopen(filename, "rb");
	    if (!ptrf) {
	    	LOGI("Could not open %s\n", filename);
	        exit(1);
	    }
        */
	    ptrfo= fopen(outfilename, "w");
	    if (!ptrfo){
	    	LOGI("Could not open %s\n", outfilename);
	    	exit(1);
	    }

	    ptrframe = av_frame_alloc();
	    if (!ptrframe) {
	    	LOGI("Could not allocate video frame\n");
	        exit(1);
	    }

	    /*
 	    ptrframe->format =  AV_PIX_FMT_YUV420P;
	    ptrframe->width  =  width;
	    ptrframe->height =  height;

	    ret = av_image_alloc(ptrframe->data, ptrframe->linesize, width, height, AV_PIX_FMT_YUV420P, 32);
	    if (ret < 0) {
	    	LOGI("Could not allocate raw picture buffer\n");
	    	exit(1);
	    }
        */

	    av_init_packet(&avpkt);

	    frame_count  = 0;
	    ffmpeg_ce = 0;
	    frame_pts = 0;

	    LOGI("        Mp4FpgCC end     \n");

	    (*env)->ReleaseStringUTFChars(env, h264_file, h264_file_n);
	    (*env)->ReleaseStringUTFChars(env, outfile_name, h264_file_o);
}

/*
 * Video decoding example
 */

static void pgm_save(unsigned char *buf, int wrap, int xsize, int ysize, char *filename)
{
    FILE *f;
    int i;

    //f = fopen(filename,"w");
    //LOGI("  pgm_save start %s \n", filename);
    //LOGI(f, "P5\n%d %d\n%d\n", xsize, ysize, 255);
    for (i = 0; i < ysize; i++)
        fwrite(buf + i * wrap, 1, xsize, ptrfo);//f);
    //fclose(f);
    //LOGI("  pgm_save end\n");
}

static int decode_write_frame(/*const char *outfilename,*/ AVCodecContext *avctx,
                              AVFrame *frame, int *frame_count, AVPacket *pkt)
{
    int len, got_frame;
    //char buf[1024];

    len = avcodec_decode_video2(avctx, frame, &got_frame, pkt);
    if (len < 0) {
    	LOGI("Error while decoding frame %d\n", *frame_count);
        return len;
    }
    if (got_frame) {
    	//LOGI("Saving %sframe %3d\n", last ? "last " : "", *frame_count);
    	LOGI("Saving frame %d, w = %d, h = %d\n", *frame_count, frame->width, frame->height);
        //fflush(stdout);

        /* the picture is allocated by the decoder, no need to free it */
        //snprintf(buf, sizeof(buf), outfilename, *frame_count);
        //pgm_save(frame->data[0], frame->linesize[0], frame->width, frame->height, buf);

        pgm_save(frame->data[0], frame->linesize[0], frame->width, frame->height, NULL);


        (*frame_count)++;
    }
    if (pkt->data) {
        pkt->size -= len;
        pkt->data += len;
    }
    return 0;
}

static void pgm_save2(unsigned char *buf,int wrap, int xsize,int ysize,unsigned char *pDataOut)
{
    int i;
    for(i=0;i<ysize;i++)
    {
    	//memcpy(pDataOut+i*xsize, buf + /*(ysize-i)*/i * wrap, xsize);
    	//linesize[0] wrap = 320, xsize = 320, ysize = 240
    	//linesize[1] wrap = 160, xsize = 160, ysize = 120
    	//linesize[2] wrap = 160, xsize = 160, ysize = 120

        memcpy(pDataOut+i*xsize, buf +i*wrap, xsize);
    }
}

int got_frame;
//unsigned char *temp_store;

int frame_size,frame_size_l;
unsigned char *temp_store_a = NULL;
unsigned char *pRgbBuf;
jmethodID method1;
AVPacket pkt;

JNIEXPORT void JNICALL Java_com_example_mymp4v2h264_MainActivity0_Mp4FpgDCE
 (JNIEnv *env, jobject obj)
{
	int i, err=0;
	int len;
	ffmpeg_ce = 1;
	got_frame = 0;
	LOGI("              Mp4FpgDCE start              ");
	AVPacket pkt;
	av_init_packet(&pkt);
	pkt.size = 0;
	pkt.data = NULL;

    //char buf[1024];
    //snprintf(buf, sizeof(buf), outfilename);
	method1=0;
	frame_size=0;
	temp_store_a = NULL;
    //temp_store = (unsigned char *)malloc(pctx->width*pctx->height*3/2);
	yuv_size  =  pctx->width*pctx->height;
	jbyteArray result;

	while (ffmpeg_ce && av_read_frame(i_fmt_ctx, &pkt)>=0){
		while(pkt.size>0){
			    len = avcodec_decode_video2(pctx, ptrframe, &got_frame, &pkt);
			    if (pkt.data) {
			    	pkt.size -= len;
			        pkt.data += len;
			    }
			    if (len < 0) {
			    	LOGI("Error while decoding frame %d\n", frame_count);
			    	 av_packet_unref(&pkt);
			        //return len;
			    	break;
			    }
			    if (got_frame) {
			    	//LOGI("00 Saving frame %d\n",frame_count);

			    	//method 1
			    	//pgm_save(ptrframe->data[0], ptrframe->linesize[0], pctx->width, pctx->height, buf);
			    	//pgm_save(ptrframe->data[1], ptrframe->linesize[1], pctx->width/2, pctx->height/2, buf);
			    	//pgm_save(ptrframe->data[2], ptrframe->linesize[2], pctx->width/2, pctx->height/2, buf);

			    	//LOGI("11 Saving frame %d\n",frame_count);
			    	            if(frame_size==0){
			    					frame_size = ptrframe->width*ptrframe->height;
			    					frame_size_l = frame_size*3/2;
			    				}
			    				if(!temp_store_a && frame_size>0){
			    					temp_store_a = (unsigned char *)malloc(frame_size*3/2);

			    					result = (*env)->NewByteArray(env, frame_size_l);

			    					//pRgbBuf = (unsigned char *)malloc(frame_size*3/2);
			    					LOGI("22 Saving frame %d, %d, %d\n",frame_count, frame_size, frame_size_l);
			    				}

			    				pgm_save2(ptrframe->data[0], ptrframe->linesize[0], ptrframe->width, ptrframe->height, temp_store_a);

			    				pgm_save2(ptrframe->data[1], ptrframe->linesize[1], ptrframe->width/2, ptrframe->height/2, temp_store_a+frame_size);

			    			    pgm_save2(ptrframe->data[2], ptrframe->linesize[2], ptrframe->width/2, ptrframe->height/2, temp_store_a+frame_size*5/4);

			    				//LOGI(" ptrframe->linesize[0] = %d, %d, %d\n",ptrframe->linesize[0], ptrframe->linesize[1],ptrframe->linesize[2]);
			    				//LOGI(" width = %d, height = %d\n",ptrframe->width, ptrframe->height);
			    			    //memcpy(pRgbBuf, temp_store_a, frame_size*3/2);

			    				if(method1==0){
			    				//1 . 找到java代码的 class文件
			    				    //    jclass      (*FindClass)(JNIEnv*, const char*);
			    				    jclass dpclazz = (*env)->FindClass(env,"com/example/mymp4v2h264/MainActivity0"); //com_example_mymp4v2h264_MainActivity0_Mp4FpgDCE0
			    				    if(dpclazz==0){
			    				        LOGI("find class error");
			    				        return;
			    				    }
			    				    LOGI("find class ");

			    				    //2 寻找class里面的方法
			    				    //   jmethodID   (*GetMethodID)(JNIEnv*, jclass, const char*, const char*);
			    				    method1 = (*env)->GetMethodID(env,dpclazz,"updateYUV","([BII)V");
			    				    if(method1==0){
			    				        LOGI("find method1 error");
			    				        return;
			    				    }
			    				    LOGI("find method1 ");
			    				}else{
			    				    //3 .调用这个方法
			    				    //    void        (*CallVoidMethod)(JNIEnv*, jobject, jmethodID, ...);
			    					//unsigned char pRgbBuf[frame_size_l];
			    					//memcpy(pRgbBuf, temp_store_a, frame_size_l);


			    					//jbyteArray result = (*env)->NewByteArray(env, frame_size_l);
			    				    (*env)->SetByteArrayRegion(env, result, 0, frame_size_l, (jbyte *)temp_store_a); //temp_store_a

			    				    //LOGI("              Mp4FpgDCE0 call              ");
			    				    (*env)->CallVoidMethod(env,obj,method1,result,ptrframe->width,ptrframe->height);
                                    usleep(25000);
			    				     /*
			    				           函数原型：void (JNICALL *ReleaseByteArrayElements)(JNIEnv *env, jbyteArray array, jbyte *elems, jint mode)
			    				          函数说明：释放分配的jbyte数组
			    				          参数说明：jbyteArray array  Java中的byte数组
			    				              jbyte *elems 由GetByteArrayElements函数分配的空间
			    				              jint mode 释放模式 一般给0
			    				    */
			    				}
			        //method 3
			        for (i = 0; i < ptrframe->height; i++)
			               fwrite(ptrframe->data[0] + i * ptrframe->linesize[0], 1, ptrframe->width, ptrfo);

			        frame_count++;
			    }
		}
		av_packet_unref(&pkt);
	}
	LOGI("              Mp4FpgDCE  end              ");
	(*env)->CallVoidMethod(env,obj,method1,result,0,0);
	ffmpeg_ce = 0;
	if(ffmpeg_ce==0){
		LOGI("              Mp4FpgDCE exit 11              ");
		len = avcodec_decode_video2(pctx, ptrframe, &got_frame, &pkt);
		if (len < 0) {
		    LOGI("Error while decoding frame %d\n", frame_count);
			//return len;
			//break;
		}
		if (got_frame) {
			LOGI("Saving frame %d\n",frame_count);
			//pgm_save(ptrframe->data[0], ptrframe->linesize[0], pctx->width, pctx->height, NULL);
			for (i = 0; i < ptrframe->height; i++)
				fwrite(ptrframe->data[0] + i * ptrframe->linesize[0], 1, ptrframe->width, ptrfo);

		    frame_count++;
		}
		LOGI("              Mp4FpgDCE end 00             ");
		usleep(25000);
		LOGI("              Mp4FpgDCE end 11             ");
		//fclose(ptrf);
        fclose(ptrfo);
    	LOGI("              Mp4FpgDCE end 12             ");
		avcodec_close(pctx);
		av_free(pctx);
		LOGI("              Mp4FpgDCE end 22             ");
		av_packet_unref(&pkt);

		av_frame_free(&ptrframe);
		for (i = 0; i < i_fmt_ctx->nb_streams; i++) {
		    avcodec_close(i_fmt_ctx->streams[i]->codec);
		    //avcodec_close(video_st0->codec);
		    av_free(i_fmt_ctx->streams[i]->codec);
		}
		avformat_close_input(&i_fmt_ctx);
	}
	LOGI("              Mp4FpgDCE end 33             ");
	//(*env)->ReleaseByteArrayElements(env, result, (jbyte *)temp_store_a, 0);
	//(*env)->DeleteLocalRef(env, result);
	LOGI("              Mp4FpgDCE end              ");
}

JNIEXPORT void JNICALL Java_com_example_mymp4v2h264_MainActivity0_Mp4FpgDEE
 (JNIEnv *env, jclass clz)
{
	ffmpeg_ce = 0;
	LOGI("              Mp4FpgDEE              ");
}

uint8_t* packet_buf;

JNIEXPORT void JNICALL Java_com_example_mymp4v2h264_MainActivity0_Mp4FpgDCC0
 (JNIEnv *env, jclass clz, jstring outfile_name, jstring h264_file)
{
	    const char* h264_file_n = (*env)->GetStringUTFChars(env, h264_file, NULL);
	    const char* h264_file_o = (*env)->GetStringUTFChars(env, outfile_name, NULL);

	    filename = h264_file_n;
	    outfilename = h264_file_o;

	    int ret, i;
	    int codec_id = AV_CODEC_ID_H264;

	    avcodec_register_all();

	    LOGI("              Mp4FpgDCC0 start              ");

	    //memset(inbuf + INBUF_SIZE, 0, AV_INPUT_BUFFER_PADDING_SIZE);

	    LOGI("              Mp4FpgDCC0 %s             ",h264_file_n);
	    LOGI("              Mp4FpgDCC0 %s             ",h264_file_o);

	    LOGI("              Mp4FpgDCC0 %s             ",filename);
		LOGI("              Mp4FpgDCC0 %s             ",outfilename);

	    // find the mpeg1 video encoder
	    ptrcodec = avcodec_find_decoder(AV_CODEC_ID_H264);
	    if (!ptrcodec) {
	    	LOGI("Codec not found\n");
	        exit(1);
	    }

	    pctx = avcodec_alloc_context3(ptrcodec);
	    if (!pctx) {
	    	LOGI("Could not allocate video codec context\n");
	        exit(1);
	    }

	    if (ptrcodec->capabilities & AV_CODEC_CAP_TRUNCATED)
	      	pctx->flags |= AV_CODEC_FLAG_TRUNCATED; // we do not send complete frames

	    //pctx->width  = 640;
	    //pctx->height = 480;

	    /* open it */
	    if (avcodec_open2(pctx, ptrcodec, NULL) < 0) {
	    	LOGI("Could not open codec\n");
	        exit(1);
	    }

	    /*
	    ptrf = fopen(filename, "rb");
	    if (!ptrf) {
	    	LOGI("Could not open %s\n", filename);
	        exit(1);
	    }

	    ptrfo= fopen(outfilename, "w");
	    if (!ptrfo){
	    	LOGI("Could not open %s\n", outfilename);
	    	exit(1);
	    }
        */

	    ptrframe = av_frame_alloc();
	    if (!ptrframe) {
	    	LOGI("Could not allocate video frame\n");
	        exit(1);
	    }

	    /*
 	    ptrframe->format =  AV_PIX_FMT_YUV420P;
	    ptrframe->width  =  width;
	    ptrframe->height =  height;

	    ret = av_image_alloc(ptrframe->data, ptrframe->linesize, width, height, AV_PIX_FMT_YUV420P, 32);
	    if (ret < 0) {
	    	LOGI("Could not allocate raw picture buffer\n");
	    	exit(1);
	    }
        */

	    av_init_packet(&avpkt);

	    frame_count  = 0;
	    ffmpeg_ce = 0;
	    frame_pts = 0;

	    LOGI("    pctx->width = %d, pctx->height = %d     ",pctx->width,pctx->height);

	    packet_buf = (unsigned char *)malloc(320*240/2);
	    //(uint8_t *)av_malloc(pctx->width*pctx->height/2); //pctx->width*pctx->height*3/2

	    LOGI("        Mp4FpgDCC0 end     \n");

	    (*env)->ReleaseStringUTFChars(env, h264_file, h264_file_n);
	    (*env)->ReleaseStringUTFChars(env, outfile_name, h264_file_o);
}

/*
int frame_size,frame_size_l;
unsigned char *temp_store_a = NULL;
unsigned char *pRgbBuf;
jmethodID method1;
AVPacket pkt;
*/
JNIEXPORT void JNICALL Java_com_example_mymp4v2h264_MainActivity0_Mp4FpgDCE0
 (JNIEnv *env, jobject obj, jbyteArray data, jint size)
{
	//LOGI("              Mp4FpgDCE0 start %d             ",size);
	int len;
	unsigned char *buf = (unsigned char *)(*env)->GetByteArrayElements(env, data, JNI_FALSE);
    memcpy(packet_buf, buf, size);

    got_frame = 0;

    av_init_packet(&pkt);
    pkt.size = size;
    pkt.data = packet_buf;
	while (pkt.size > 0) {
		/*
	    if (decode_write_frame(pctx, ptrframe, &frame_count, &avpkt) < 0){
	       LOGI("              Mp4FpgDCE0 err              ");
	       break;
	    }
	    */
		len = avcodec_decode_video2(pctx, ptrframe, &got_frame, &pkt);
		if (pkt.data) {
			pkt.size -= len;
			pkt.data += len;
		}
		if (len < 0) {
			LOGI("Error while decoding frame %d\n", frame_count);
			//av_packet_unref(&pkt);
			 //return len;
			break;
		}
		if(got_frame){
			if(frame_size==0){
				frame_size = ptrframe->width*ptrframe->height;
				frame_size_l = frame_size*3/2;
			}
			if(!temp_store_a && frame_size>0){
				temp_store_a = (unsigned char *)malloc(frame_size*3/2);
				//pRgbBuf = (unsigned char *)malloc(frame_size*3/2);
				LOGI("22 Saving frame %d, %d, %d\n",frame_count, frame_size, frame_size_l);
			}

			pgm_save2(ptrframe->data[0], ptrframe->linesize[0], ptrframe->width, ptrframe->height, temp_store_a);

			pgm_save2(ptrframe->data[1], ptrframe->linesize[1], ptrframe->width/2, ptrframe->height/2, temp_store_a+frame_size);

		    pgm_save2(ptrframe->data[2], ptrframe->linesize[2], ptrframe->width/2, ptrframe->height/2, temp_store_a+frame_size*5/4);

			//LOGI(" ptrframe->linesize[0] = %d, %d, %d\n",ptrframe->linesize[0], ptrframe->linesize[1],ptrframe->linesize[2]);
			//LOGI(" width = %d, height = %d\n",ptrframe->width, ptrframe->height);
		    //memcpy(pRgbBuf, temp_store_a, frame_size*3/2);

			if(method1==0){
			//1 . 找到java代码的 class文件
			    //    jclass      (*FindClass)(JNIEnv*, const char*);
			    jclass dpclazz = (*env)->FindClass(env,"com/example/mymp4v2h264/MainActivity0"); //com_example_mymp4v2h264_MainActivity0_Mp4FpgDCE0
			    if(dpclazz==0){
			        LOGI("find class error");
			        return;
			    }
			    LOGI("find class ");

			    //2 寻找class里面的方法
			    //   jmethodID   (*GetMethodID)(JNIEnv*, jclass, const char*, const char*);
			    method1 = (*env)->GetMethodID(env,dpclazz,"updateYUV","([BII)V");
			    if(method1==0){
			        LOGI("find method1 error");
			        return;
			    }
			    LOGI("find method1 ");
			}else{
			    //3 .调用这个方法
			    //    void        (*CallVoidMethod)(JNIEnv*, jobject, jmethodID, ...);
				//unsigned char pRgbBuf[frame_size_l];
				//memcpy(pRgbBuf, temp_store_a, frame_size_l);

				jbyteArray result = (*env)->NewByteArray(env, frame_size_l);

				//jbyteArray result = (*env)->NewByteArray(env, frame_size_l);
			    (*env)->SetByteArrayRegion(env, result, 0, frame_size_l, (jbyte *)temp_store_a); //temp_store_a

			    //LOGI("              Mp4FpgDCE0 call              ");
			    (*env)->CallVoidMethod(env,obj,method1,result,ptrframe->width,ptrframe->height);

			     /*
			           函数原型：void (JNICALL *ReleaseByteArrayElements)(JNIEnv *env, jbyteArray array, jbyte *elems, jint mode)
			          函数说明：释放分配的jbyte数组
			          参数说明：jbyteArray array  Java中的byte数组
			              jbyte *elems 由GetByteArrayElements函数分配的空间
			              jint mode 释放模式 一般给0
			    */

			    //(*env)->ReleaseByteArrayElements(env, result, (jbyte *)temp_store_a, 0);
			    //(*env)->DeleteLocalRef(env, result);
			    //LOGI("              Mp4FpgDCE0 call end              ");
			}
			frame_count++;

			//free(temp_store);
		}
	}


	//jbyte *jy0=(jbyte*)pRgbBuf;

	//jbyteArray result = (*env)->NewByteArray(env, frame_size);
	//(*env)->SetByteArrayRegion(env, result, 0, frame_size, temp_store_a);

	(*env)->ReleaseByteArrayElements(env, data, (jbyte *)buf, 0);

	//return result;
	//LOGI("              Mp4FpgDCE0 end              ");
}

JNIEXPORT void JNICALL Java_com_example_mymp4v2h264_MainActivity0_Mp4FpgDEE0
 (JNIEnv *env, jclass clz)
{
	LOGI("              Mp4FpgDEE0              ");

	//avpkt.data = NULL;
	//avpkt.size = 0;
	//decode_write_frame(pctx, ptrframe, &frame_count, &avpkt);

	LOGI("              Mp4FpgDEE0  00            ");
	//fclose(ptrf);
	//fclose(ptrfo);


	avcodec_close(pctx);
	av_free(pctx);
	av_frame_free(&ptrframe);
	LOGI("              Mp4FpgDEE0  11            ");
}

JNIEXPORT bool JNICALL Java_com_example_mymp4v2h264_MainActivity0_Mp4Start
 (JNIEnv *env, jclass clz, jstring mp4)
{
	const char* mp4_title = (*env)->GetStringUTFChars(env,mp4, NULL);
    if(mp4_title == NULL)
	{
		return false;
	}

    fileHandle = CreateMP4File(mp4_title, 640, 480);//240,320);

	if(fileHandle == NULL)
	{
		//printf("ERROR:Create file failed!");
		LOGI("              MP4_INVALID_FILE_HANDLE NULL             ");
		(*env)->ReleaseStringUTFChars(env, mp4, mp4_title);
		return false;
	}

	uint32_t samplesPerSecond;
	uint8_t profile;
	uint8_t channelConfig;

	samplesPerSecond = 44100;
	profile = 2; // AAC LC

	/*
	0: Null
	1: AAC Main
	2: AAC LC (Low Complexity)
	3: AAC SSR (Scalable Sample Rate)
	4: AAC LTP (Long Term Prediction)
	5: SBR (Spectral Band Replication)
	6: AAC Scalable
    */
	channelConfig = 1;

	uint8_t* pConfig = NULL;
	uint32_t configLength = 0;

    //m_audio = MP4AddAudioTrack(m_file, 44100, 1024, MP4_MPEG2_AAC_MAIN_AUDIO_TYPE );
	audio = MP4AddAudioTrack(fileHandle, 44100, 1024, MP4_MPEG2_AAC_LC_AUDIO_TYPE);//MP4_MPEG4_AUDIO_TYPE);//MP4_MPEG2_AAC_LC_AUDIO_TYPE
	//MP4_MPEG2_AAC_LC_AUDIO_TYPE);//16000 1024
	if(audio == MP4_INVALID_TRACK_ID)
	{
		MP4Close(fileHandle, 0);
		return false;
	}
	MP4SetAudioProfileLevel(fileHandle, 0x02);
	LOGI("              MP4AddAudioTrack ok                ");

	MP4AacGetConfiguration(&pConfig, &configLength, profile, samplesPerSecond, channelConfig);
	//free(pConfig);
	MP4SetTrackESConfiguration(fileHandle, audio, pConfig, configLength);

	(*env)->ReleaseStringUTFChars(env, mp4, mp4_title);
	return true;
}

//添加视频帧的方法
JNIEXPORT void JNICALL Java_com_example_mymp4v2h264_MainActivity0_Mp4PackV
(JNIEnv *env, jclass clz, jbyteArray data, jint size, jint keyframe)
{
	unsigned char *buf = (unsigned char *)(*env)->GetByteArrayElements(env, data, JNI_FALSE);

	unsigned char type;
	type = buf[4]&0x1f;
	//LOGI(" 0x%x 0x%x 0x%x 0x%x 0x%x ",buf[0],buf[1],buf[2],buf[3], type);
	if(type == 0x07) // sps
	{
				// track
				m_videoId = MP4AddH264VideoTrack(fileHandle,
					m_nTimeScale,
					m_nTimeScale / m_nFrameRate,
					m_nWidth,     // width
					m_nHeight,    // height
					buf[5], // sps[1] AVCProfileIndication
					buf[6], // sps[2] profile_compat
					buf[7], // sps[3] AVCLevelIndication
					3);           // 4 bytes length before each NAL unit
				if (m_videoId == MP4_INVALID_TRACK_ID)
				{
					printf("add video track failed.\n");
					//MP4Close(mMp4File, 0);
					//return 0;
				}else{
				    MP4SetVideoProfileLevel(fileHandle, 0x7F); //  Simple Profile @ Level 3 = 2

				    MP4AddH264SequenceParameterSet(fileHandle, m_videoId, &buf[4], size-4);
				    LOGI("              write sps                ");
				}
	}
	else if(type == 0x08) // pps
	{
				MP4AddH264PictureParameterSet(fileHandle, m_videoId, &buf[4], size-4);
				m_samplesWritten = 0;
				m_lastTime = 0;
				LOGI("              write pps                ");
    }
    else
    {
		        int nalsize = size-4;
		 		bool ret = false;
		 		/*
		        buf[0] = (nalsize >> 24) & 0xff;
		 		buf[1] = (nalsize >> 16) & 0xff;
		 		buf[2] = (nalsize >> 8)& 0xff;
		 		buf[3] =  nalsize & 0xff;
                */
		 		//LOGI(" 0x%02x 0x%02x 0x%02x 0x%02x %d ", buf[0],buf[1],buf[2],buf[3],nalsize);
		 		buf[0] = (nalsize&0xff000000)>>24;
		 		buf[1] = (nalsize&0x00ff0000)>>16;
		 		buf[2] = (nalsize&0x0000ff00)>>8;
		 		buf[3] = nalsize&0x000000ff;

		 		/*
		 		m_samplesWritten++;
		 		double thiscalc;
		 		thiscalc = m_samplesWritten;
		 		thiscalc *= m_nTimeScale;
		 		thiscalc /= m_nFrameRate;

		 		m_thisTime = (MP4Duration)thiscalc;
		 		MP4Duration dur;
		 		dur = m_thisTime - m_lastTime;
                */

		 		//ret = MP4WriteSample(fileHandle, video, buf, size, dur, 0, keyframe); //MP4_INVALID_DURATION keyframe

                if(keyframe){
		 	     	//LOGI("       type = %d, size = %d, %d       ",type, size, keyframe);
                }
		 		ret = MP4WriteSample(fileHandle, m_videoId, buf, size, MP4_INVALID_DURATION, 0, keyframe);
		 		//ret = MP4WriteSample(fileHandle, m_videoId, buf, size, dur, 0, keyframe);
		 		//m_lastTime = m_thisTime;
		 		if(!ret){
		 			//LOGI(stderr,	"can't write video frame %u\n",	m_samplesWritten );
		 			LOGI("              MP4_INVALID_TRACK_ID = %d               ",ret);
		 			//MP4DeleteTrack(fileHandle, m_videoId);
		 			//return MP4_INVALID_TRACK_ID;
		 		}
	}
	(*env)->ReleaseByteArrayElements(env, data, (jbyte *)buf, 0);
}

//添加音频帧的方法
JNIEXPORT void JNICALL Java_com_example_mymp4v2h264_MainActivity0_Mp4PackA
(JNIEnv *env, jclass clz, jbyteArray data, jint size)
{
	uint8_t *bufaudio = (uint8_t *)(*env)->GetByteArrayElements(env, data, JNI_FALSE);
	//LOGI("       Mp4PackA = %d       ", size);
	//MP4WriteSample(fileHandle, audio, &bufaudio[7], size-7);
	MP4WriteSample(fileHandle, audio, &bufaudio[7], size-7, MP4_INVALID_DURATION, 0, 1);
	/*
	bool MP4WriteSample(
		MP4FileHandle hFile,
		MP4TrackId trackId,
		const u_int8_t* pBytes,
		u_int32_t numBytes,
		MP4Duration duration DEFAULT(MP4_INVALID_DURATION),
		MP4Duration renderingOffset DEFAULT(0),
		bool isSyncSample DEFAULT(true));
		*/
    //减去7为了删除adts头部的7个字节
	(*env)->ReleaseByteArrayElements(env, data, (jbyte *)bufaudio, 0);
}


//视频录制结束调用
JNIEXPORT void JNICALL Java_com_example_mymp4v2h264_MainActivity0_Mp4End
(JNIEnv *env, jclass clz)
{
	MP4Close(fileHandle, 0);
	fileHandle = NULL;
	LOGI("              mp4close              ");
	LOGI("              mp4close              ");
}

//视频录制的调用,实现初始化
JNIEXPORT bool JNICALL Java_com_example_mymp4v2h264_MainActivity0_mp4init
(JNIEnv *env, jclass clz, jstring title)
{
	const char* local_title = (*env)->GetStringUTFChars(env,title, NULL);
	uint32_t samplesPerSecond;
	uint8_t profile;
	uint8_t channelConfig;

	samplesPerSecond = 44100;
	profile = 1; // AAC LC
    /*
0: Null
1: AAC Main
2: AAC LC (Low Complexity)
3: AAC SSR (Scalable Sample Rate)
4: AAC LTP (Long Term Prediction)
5: SBR (Spectral Band Replication)
6: AAC Scalable
     */
	channelConfig = 2;
	/*
	MP4V2_EXPORT MP4FileHandle MP4CreateEx(
	    const char* fileName,
	    uint32_t    flags DEFAULT(0),
	    int         add_ftyp DEFAULT(1),
	    int         add_iods DEFAULT(1),
	    char*       majorBrand DEFAULT(0),
	    uint32_t    minorVersion DEFAULT(0),
	    char**      compatibleBrands DEFAULT(0),
	    uint32_t    compatibleBrandsCount DEFAULT(0) );
    MP4V2_EXPORT MP4FileHandle MP4CreateEx(
    const char* fileName,
    uint32_t    flags DEFAULT(0),
    int         add_ftyp DEFAULT(1),
    int         add_iods DEFAULT(1),
    char*       majorBrand DEFAULT(0),
    uint32_t    minorVersion DEFAULT(0),
    char**      compatibleBrands DEFAULT(0),
    uint32_t    compatibleBrandsCount DEFAULT(0) );

   trak / mdia / minf / stbl
   - stsd: 编码器CODEC信息
   - stsz: 用于sample的划分，通常一个sample可以对应于frame。
   - stsc: 多个sample组成一个trunk，不过实际操作中可以让一个sample直接构成一个trunk
   - stco: trunk在文件中的位置，用于定位。
   - stts / ctts: 指定每个sample的PTS, DTS
    */

	//创建mp4文件
	//fileHandle = MP4Create(local_title, 0);
	//m_mp4file = MP4CreateEx(".\\Data\\2.mp4", MP4_DETAILS_ALL, 0, 1, (char*)1, 0, 0, 0);
	//m_mp4file = MP4CreateEx(".\\Data\\2.mp4", MP4_DETAILS_ALL, 0, 1, 1, 0, 0, 0);
	//fileHandle = MP4CreateEx(local_title, MP4_DETAILS_ALL, 0, 1, (char*)1, 0, 0, 0);

	char *p[4];
	p[0] = "isom";
	p[1] = "iso2";
	p[2] = "avc1";
	p[3] = "mp41";
	//fileHandle = MP4CreateEx(local_title, 0, 1, 1, "isom", 0x00000200, p, 4);
	fileHandle = MP4CreateEx(local_title, 9, 0, 1, (char*)1, 0, 0, 0);

	if(fileHandle == MP4_INVALID_FILE_HANDLE)
	{
		return false;
	}
	LOGI("              MP4CreateEx ok                ");

	//memcpy(sps_pps, sps_pps_640, 17);

	video_width = 640;
	video_height = 480;

	//设置mp4文件的时间单位
	MP4SetTimeScale(fileHandle, 90000);

	//创建视频track //根据ISO/IEC 14496-10 可知sps的第二个，第三个，第四个字节分别是 AVCProfileIndication,profile_compat,AVCLevelIndication     其中90000/20  中的20>是fps
	video = MP4AddH264VideoTrack(fileHandle,
			m_nMp4TimeScale,
			m_nMp4TimeScale/m_nVideoFrameRate,
			video_width,
			video_height,
			0x42,//sps_pps[1],
			0x80,//sps_pps[2],
			0x1e,//sps_pps[3],
			3);
	if(video == MP4_INVALID_TRACK_ID)
	{
		MP4Close(fileHandle, 0);
		return false;
	}
	MP4SetVideoProfileLevel(fileHandle, 0x7F);
	//设置sps和pps
	//67 42 80 1e e9 01 40 7b 20 sps
	//68 ce 06 e2 pps

	//uint8_t  ubuffer[2];
	//ubuffer = 0x1220;
	//ubuffer[0] = 0x12;
	//ubuffer[1] = 0x20;
	//MP4SetTrackESConfiguration(fileHandle, audio, ubuffer, 2);

	MP4AddH264SequenceParameterSet(fileHandle, video, sps, 9);//sps_pps, 13);
	MP4AddH264PictureParameterSet(fileHandle, video, pps, 4);//sps_pps+13, 4);

	LOGI("              MP4AddH264VideoTrack ok                ");
    /*
	uint32_t m_nVerbosity=1;
	if (MP4GetNumberOfTracks(fileHandle, MP4_VIDEO_TRACK_TYPE) == 1)
	{
		uint32_t new_verb = m_nVerbosity & ~(MP4_DETAILS_ERROR);
		MP4SetVerbosity(fileHandle, new_verb);
		MP4SetVideoProfileLevel(fileHandle, 0x7f);
		MP4SetVerbosity(fileHandle, m_nVerbosity);

		LOGI("              MP4_VIDEO_TRACK_TYPE                ");
	}
    */

	//ADTS = Audio Data Transport Stream 7 bytes:
	//adts_fixed_header();
	//adts_variable_header();
	audio = MP4AddAudioTrack(fileHandle, samplesPerSecond, 2048, MP4_MPEG2_AAC_LC_AUDIO_TYPE);//16000 1024
	if(audio == MP4_INVALID_TRACK_ID)
	{
		MP4Close(fileHandle, 0);
		return false;
	}
	MP4SetAudioProfileLevel(fileHandle, 0x02);
	LOGI("              MP4AddAudioTrack ok                ");
	/*
	if (MP4GetNumberOfTracks(fileHandle, MP4_AUDIO_TRACK_TYPE) == 1)
	{
		MP4SetAudioProfileLevel(fileHandle, 0x0F);
	}
	*/
	uint8_t* pConfig = NULL;
	uint32_t configLength = 0;

	MP4AacGetConfiguration(&pConfig, &configLength, profile, samplesPerSecond, channelConfig);
	//free(pConfig);
	MP4SetTrackESConfiguration(fileHandle, audio, pConfig, configLength);

	video_type = 1;
	(*env)->ReleaseStringUTFChars(env, title, local_title);

	LOGI("              mp4init                ");
	LOGI("              mp4init                ");

	return true;
}

uint16_t len_rec;

//添加视频帧的方法
JNIEXPORT void JNICALL Java_com_example_mymp4v2h264_MainActivity0_mp4packVideo
(JNIEnv *env, jclass clz, jbyteArray data, jint size, jint keyframe)
{
	unsigned char *buf = (unsigned char *)(*env)->GetByteArrayElements(env, data, JNI_FALSE);
	if(video_type == 1){
		int nalsize = size;
		bool ret = false;
		if(len_rec<5){
			len_rec++;
		    LOGI("              nalsize = %d, %d , %d              ",nalsize, size, keyframe);
	    }
		/*
		buf[0] = (nalsize & 0xff000000) >> 24;
		buf[1] = (nalsize & 0x00ff0000) >> 16;
		buf[2] = (nalsize & 0x0000ff00) >> 8;
		buf[3] =  nalsize & 0x000000ff;
		*/
		buf[0] = (nalsize >> 24) & 0xff;
		buf[1] = (nalsize >> 16) & 0xff;
		buf[2] = (nalsize >> 8)& 0xff;
		buf[3] =  nalsize & 0xff;

		m_samplesWritten++;
		double thiscalc;
		thiscalc = m_samplesWritten;
		thiscalc *= m_nMp4TimeScale;
		thiscalc /= m_nVideoFrameRate;

		m_thisTime = (MP4Duration)thiscalc;
		MP4Duration dur;
		dur = m_thisTime - m_lastTime;

		//ret = MP4WriteSample(fileHandle, video, buf, size, dur, 0, keyframe); //MP4_INVALID_DURATION keyframe
		ret = MP4WriteSample(fileHandle, video, buf, size, MP4_INVALID_DURATION, 0, 1);
		m_lastTime = m_thisTime;
		if(!ret)
		{
			//LOGI(stderr,	"can't write video frame %u\n",	m_samplesWritten );
			LOGI("              MP4_INVALID_TRACK_ID = %d               ",m_samplesWritten);
			MP4DeleteTrack(fileHandle, video);
			//return MP4_INVALID_TRACK_ID;
		}
	}
	(*env)->ReleaseByteArrayElements(env, data, (jbyte *)buf, 0);
}

//添加音频帧的方法
JNIEXPORT void JNICALL Java_com_example_mymp4v2h264_MainActivity0_mp4packAudio
(JNIEnv *env, jclass clz, jbyteArray data, jint size)
{
	uint8_t *bufaudio = (uint8_t *)(*env)->GetByteArrayElements(env, data, JNI_FALSE);
	//MP4WriteSample(fileHandle, audio, &bufaudio[7], size-7);
	MP4WriteSample(fileHandle, audio, &bufaudio[7], size-7, MP4_INVALID_DURATION, 0, 1);
	/*
	bool MP4WriteSample(
		MP4FileHandle hFile,
		MP4TrackId trackId,
		const u_int8_t* pBytes,
		u_int32_t numBytes,
		MP4Duration duration DEFAULT(MP4_INVALID_DURATION),
		MP4Duration renderingOffset DEFAULT(0),
		bool isSyncSample DEFAULT(true));
		*/
    //减去7为了删除adts头部的7个字节
	(*env)->ReleaseByteArrayElements(env, data, (jbyte *)bufaudio, 0);
}

//视频录制结束调用
JNIEXPORT void JNICALL Java_com_example_mymp4v2h264_MainActivity0_mp4close
(JNIEnv *env, jclass clz)
{
	MP4Close(fileHandle, 0);
	fileHandle = NULL;
	LOGI("              mp4close              ");
	LOGI("              mp4close              ");
}

/*
static void* writeThread(void* arg)
{
     rtp_s* p_rtp = (rtp_s*) arg;
     if (p_rtp == NULL)
     {
         printf("ERROR!\n");
         return;
     }

     MP4FileHandle file = MP4CreateEx("test.mp4", MP4_DETAILS_ALL, 0, 1, 1, 0, 0, 0, 0);
     if (file == MP4_INVALID_FILE_HANDLE)
     {
         printf("open file fialed.\n");
         return;
     }

     MP4SetTimeScale(file, 90000);

     //添加h264 track
     MP4TrackId video = MP4AddH264VideoTrack(file, 90000, 90000/25, 320, 240,
          0x64, //sps[1] AVCProfileIndication
          0x00, //sps[2] profile_compat
          0x1f, //sps[3] AVCLevelIndication
             3); // 4 bytes length before each NAL unit
     if (video == MP4_INVALID_TRACK_ID)
     {
         printf("add video track failed.\n");
         return;
     }
     MP4SetVideoProfileLevel(file, 0x7F);

     //添加aac音频
     MP4TrackId audio = MP4AddAudioTrack(file, 48000, 1024, MP4_MPEG4_AUDIO_TYPE);
     if (video == MP4_INVALID_TRACK_ID)
     {
         printf("add audio track failed.\n");
         return;
     }
     MP4SetAudioProfileLevel(file, 0x2);


     int ncount = 0;
     while (1)
     {
         frame_t* pf = NULL; //frame
         pthread_mutex_lock(&p_rtp->mutex);
         pf = p_rtp->p_frame_header;
         if (pf != NULL)
         {
             if (pf->i_type == 1)//video
             {
             //(1)h264流中的NAL，头四个字节是0x00000001;
             //(2)mp4中的h264track，头四个字节要求是NAL的长度，并且是大端顺序；
             // 将每个sample(也就是NAL)的头四个字节内容改成NAL的长度
                 if(pf->i_frame_size >= 4)
                 {
                    uint32_t* p = (&pf->p_frame[0]);
                    *p = htonl(pf->i_frame_size -4);//大端,去掉头部四个字节
                 }
                 MP4WriteSample(file, video, pf->p_frame, pf->i_frame_size, MP4_INVALID_DURATION, 0, 1);
             }
             else if (pf->i_type == 2)//audio
             {
                 MP4WriteSample(file, audio, pf->p_frame, pf->i_frame_size , MP4_INVALID_DURATION, 0, 1);
             }

             ncount++;

             //clear frame.
             p_rtp->i_buf_num--;
             p_rtp->p_frame_header = pf->p_next;
             if (p_rtp->i_buf_num <= 0)
             {
                 p_rtp->p_frame_buf = p_rtp->p_frame_header;
             }
             free_frame(&pf);
             pf = NULL;

             if (ncount >= 1000)
             {
                 break;
             }
         }
         else
         {
             //printf("BUFF EMPTY, p_rtp->i_buf_num:%d\n", p_rtp->i_buf_num);
         }
         pthread_mutex_unlock(&p_rtp->mutex);
         usleep(10000);
     }
     MP4Close(file);
}
*/

