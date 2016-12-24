#include <stdio.h>
#include <jni.h>
#include <malloc.h>
#include <string.h>
#include <android/log.h>
#include <string.h>

#include "x265.h"

#define LOG_TAG "libmp4"
#define LOGD(...) ((void)__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__))
#define LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__))
#define LOGW(...) ((void)__android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__))

#ifdef __cplusplus
extern "C" {
#endif

FILE *h265_file;
int ret;
x265_nal *pNals=NULL;
uint32_t iNal=0;
x265_param* pParam=NULL;
x265_encoder* pHandle=NULL;
x265_picture *pPic_in=NULL;
int y_size;
int y_size0;
int frame_num=0;
int csp=X265_CSP_I420;

unsigned char *yuv_buff;

JNIEXPORT bool JNICALL Java_com_example_mymp4v2h264_MainActivity0_X265Start
 (JNIEnv *env, jclass clz, jstring file_name)
{
    LOGI("              X265Start              ");
    int width=640,height=480;
	const char* h265_title = env->GetStringUTFChars(file_name, NULL);
	h265_file=fopen(h265_title,"w");
    if(!h265_file){
    	LOGI("              Open File Error              ");
		return false;
	}
        pParam=x265_param_alloc();
        x265_param_default(pParam);
        pParam->bRepeatHeaders=1;//write sps,pps before keyframe
        pParam->internalCsp=csp;
        pParam->sourceWidth=width;
        pParam->sourceHeight=height;
        pParam->fpsNum=25;
        pParam->fpsDenom=1;
        //Init
        pHandle=x265_encoder_open(pParam);
        if(pHandle==NULL){
        	LOGI("x265_encoder_open err\n");
            return false;
        }
        y_size0= pParam->sourceWidth * pParam->sourceHeight;
        y_size = (y_size0*3)/2;

        pPic_in = x265_picture_alloc();
        x265_picture_init(pParam,pPic_in);

        yuv_buff=(unsigned char *)malloc(y_size);
        //pPic_in->planes[0] = yuv_buff;
        //pPic_in->planes[1] = pPic_in->planes[0] + width * height;
        //pPic_in->planes[2] = pPic_in->planes[1] + width * height / 4;


        pPic_in->planes[0]=yuv_buff;
        pPic_in->planes[1]=yuv_buff+y_size0;
        pPic_in->planes[2]=yuv_buff+y_size0*5/4;

        pPic_in->stride[0]=width;
        pPic_in->stride[1]=width/2;
        pPic_in->stride[2]=width/2;

    LOGI("              X265Start end             ");
	env->ReleaseStringUTFChars(file_name, h265_title);
	return true;
}

JNIEXPORT void JNICALL Java_com_example_mymp4v2h264_MainActivity0_X265Enc
(JNIEnv *env, jclass clz, jbyteArray data, jint size)
{
	int j;
	uint8_t *buf = (uint8_t *)env->GetByteArrayElements(data, JNI_FALSE);
    memcpy(yuv_buff, buf, y_size);

	frame_num++;
	ret=x265_encoder_encode(pHandle,&pNals,&iNal,pPic_in,NULL);
	LOGI("Succeed encode %d frames, Nals = %d\n", frame_num, iNal);

	for(j=0;j<iNal;j++){
	    fwrite(pNals[j].payload,1,pNals[j].sizeBytes,h265_file);
	}

	env->ReleaseByteArrayElements(data, (jbyte *)buf, 0);
}

JNIEXPORT void JNICALL Java_com_example_mymp4v2h264_MainActivity0_X265End
(JNIEnv *env, jclass clz)
{
	LOGI("              X265End              ");
	//Flush Decoder
	    while(1){
	    	int j;
	    	static int kk = 0;
	        ret=x265_encoder_encode(pHandle,&pNals,&iNal,NULL,NULL);
	        if(ret<0){
	            break;
	        }
	        LOGI("Flush 1 frame.\n");

	        kk++;
	        if(kk>=2){
	        	kk = 0;
	        	LOGI("Flush end.\n");
	        	break;
	        }
	        for(j=0;j<iNal;j++){
	            fwrite(pNals[j].payload,1,pNals[j].sizeBytes,h265_file);
	        }
	    }

	    x265_encoder_close(pHandle);
	    x265_picture_free(pPic_in);
	    x265_param_free(pParam);
	    free(yuv_buff);
	    fclose(h265_file);

	    LOGI("              X265End              ");
}

#ifdef __cplusplus
}
#endif
