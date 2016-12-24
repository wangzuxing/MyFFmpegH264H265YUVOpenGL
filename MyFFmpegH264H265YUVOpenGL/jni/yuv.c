#include <stdio.h>
#include <jni.h>
#include <malloc.h>
#include <string.h>
#include <android/log.h>
#include <string.h>
#include "SDL.h"
#include "SDL_log.h"
#include "SDL_main.h"

#define LOG_TAG "libyuv"
#define LOGD(...) ((void)__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__))
#define LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__))
#define LOGW(...) ((void)__android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__))

//set '1' to choose a type of file to play
#define LOAD_BGRA    0
#define LOAD_RGB24   0
#define LOAD_BGR24   0
#define LOAD_YUV420P 1

//Bit per Pixel
#if LOAD_BGRA
const int bpp=32;
#elif LOAD_RGB24|LOAD_BGR24
const int bpp=24;
#elif LOAD_YUV420P
const int bpp=12;
#endif

int screen_w=640,screen_h=480;
const int pixel_w=640,pixel_h=480;

unsigned char *buffer;//[bpp_size];
unsigned char *buffer_convert;//[bpp_p]; //BPP=32


#define  V_ITEMS     2 //5
#define  V_BUF_SIZE  640*480*3/2
typedef struct {
	 uint8_t ptr[V_BUF_SIZE]; //uint8_t *ptr;
}rx_items;

typedef struct {
	 rx_items  item[V_ITEMS];
	 uint8_t   IndexR;
	 uint8_t   IndexW;
	 uint8_t   V_Len;
}rx_queues;

rx_queues  rx_queue;//, tx_queue;

//Convert RGB24/BGR24 to RGB32/BGR32
//And change Endian if needed
void CONVERT_24to32(unsigned char *image_in,unsigned char *image_out,int w,int h){
	int i, j;
    for(i =0;i<h;i++)
        for(j=0;j<w;j++){
            //Big Endian or Small Endian?
            //"ARGB" order:high bit -> low bit.
            //ARGB Format Big Endian (low address save high MSB, here is A) in memory : A|R|G|B
            //ARGB Format Little Endian (low address save low MSB, here is B) in memory : B|G|R|A
            if(SDL_BYTEORDER==SDL_LIL_ENDIAN){
                //Little Endian (x86): R|G|B --> B|G|R|A
                image_out[(i*w+j)*4+0]=image_in[(i*w+j)*3+2];
                image_out[(i*w+j)*4+1]=image_in[(i*w+j)*3+1];
                image_out[(i*w+j)*4+2]=image_in[(i*w+j)*3];
                image_out[(i*w+j)*4+3]='0';
            }else{
                //Big Endian: R|G|B --> A|R|G|B
                image_out[(i*w+j)*4]='0';
                memcpy(image_out+(i*w+j)*4+1,image_in+(i*w+j)*3,3);
            }
        }
}


//Refresh Event
#define REFRESH_EVENT  (SDL_USEREVENT + 1)

int thread_exit=0;

int refresh_video(void *opaque){
    while (thread_exit==0) {
        SDL_Event event;
        event.type = REFRESH_EVENT;
        SDL_PushEvent(&event);
        SDL_Delay(40);
    }
    return 0;
}

SDL_Renderer* sdlRenderer;
SDL_Texture* sdlTexture;
unsigned char test_ok;
int yy_size;
unsigned char *yuv_buff;
SDL_Rect sdlRect;

JNIEXPORT jint JNICALL Java_org_libsdl_app_SDLActivity_nativeTestStart(JNIEnv *env, jclass clz)
{
	    test_ok = 0;
	    if(SDL_Init(SDL_INIT_VIDEO)) {
	        LOGI( "Could not initialize SDL - %s\n", SDL_GetError());
	        return -1;
	    }
	    LOGI(" SDL_Init ok \n");
	    //const char* yuv_title = (*env)->GetStringUTFChars(env,file_name, NULL);
	    //const char *yuv_title = "/storage/emulated/0/h264.yuv";
	    //LOGI( "  yuv_title - %s\n", yuv_title);

	    int y_size0 = pixel_w*pixel_h*bpp/8;
	    yy_size = screen_w*screen_h*3/2;
	    yuv_buff = (unsigned char *)malloc(yy_size);

	    if(!yuv_buff) {
	   	    LOGI("SDL: could not create yuv_buff \n");
	   	    return -1;
	   	}

	    LOGI(" y_size0 = %d, yy_size = %d\n", y_size0, yy_size);

	    //FIX: If window is resize
	   	sdlRect.x = 0;
	   	sdlRect.y = 0;
	   	sdlRect.w = screen_w;
	    sdlRect.h = screen_h;

	    SDL_Window *screen;
	    //SDL 2.0 Support for multiple windows
	    screen = SDL_CreateWindow("Simplest Video Play SDL2", SDL_WINDOWPOS_UNDEFINED, SDL_WINDOWPOS_UNDEFINED,
	    	        screen_w, screen_h,SDL_WINDOW_OPENGL|SDL_WINDOW_SHOWN); //SDL_WINDOW_SHOWN  SDL_WINDOW_OPENGL SDL_WINDOW_RESIZABLE
	    if(!screen) {
	        LOGI("SDL: could not create window - exiting:%s\n",SDL_GetError());
	        return -1;
	    }
	    LOGI(" SDL_CreateWindow ok \n");

	    sdlRenderer = SDL_CreateRenderer(screen, -1, 0);

	    if(!sdlRenderer) {
	    	LOGI("SDL: could not create renderer\n");
	        return -1;
	    }
	    LOGI(" screen_w = %d, screen_h = %d, pixel_w = %d, pixel_h = %d\n", screen_w, screen_h, pixel_w,pixel_h);
	    /*
	    SDL_RENDERER_SOFTWARE ：使用软件渲染
	    SDL_RENDERER_ACCELERATED ：使用硬件加速
	    SDL_RENDERER_PRESENTVSYNC：和显示器的刷新率同步
	    SDL_RENDERER_TARGETTEXTURE ：不太懂
	    */

	    LOGI(" SDL_CreateTexture ok = %d \n",test_ok);

	    Uint32 pixformat=0;
	    //IYUV: Y + U + V  (3 planes)
	    //YV12: Y + V + U  (3 planes)
	    pixformat= SDL_PIXELFORMAT_IYUV;

	    sdlTexture = SDL_CreateTexture(sdlRenderer,pixformat, SDL_TEXTUREACCESS_STREAMING, pixel_w, pixel_h);

	    if(!sdlTexture) {
	    	LOGI("SDL: could not create texture\n");
	    	return -1;
	    }
	    /*
	    SDL_TEXTUREACCESS_STATIC         ：变化极少
	    SDL_TEXTUREACCESS_STREAMING        ：变化频繁
	    SDL_TEXTUREACCESS_TARGET       ：暂时没有理解
	    */
	    test_ok = 1;
	    LOGI(" SDL_CreateTexture ok = %d \n",test_ok);

	    return 0;
}

JNIEXPORT void JNICALL Java_org_libsdl_app_SDLActivity_nativeTestEnd(JNIEnv *env, jclass clz)
{
	    free(yuv_buff);
	    LOGI("  nativeTestEnd  \n");
}

int run_enc;
int mystrlen(unsigned char* str)
{
    unsigned char* temp = str;
    while('\0' != *str) str++;
    return str-temp;
}

JNIEXPORT void JNICALL Java_org_libsdl_app_SDLActivity_nativeTestEnc(JNIEnv *env, jclass clz, jbyteArray data, jint size)
{
	    if(test_ok==1)
	    {
	    unsigned char *buf = (unsigned char *)(*env)->GetByteArrayElements(env, data, JNI_FALSE);
	    //int leng = strlen((char *)buf);
	    memcpy(yuv_buff, buf, yy_size);

	    int ret;
	    //SDL_UpdateTexture( sdlTexture, NULL, buffer, pixel_w);
	    ret = SDL_UpdateTexture( sdlTexture, NULL, buf, pixel_w);
	    /*
	    int SDLCALL SDL_UpdateTexture(SDL_Texture * texture,  const SDL_Rect * rect,  const void *pixels, int pitch);
	    texture：目标纹理。
	    rect：更新像素的矩形区域。设置为NULL的时候更新整个区域。
	    pixels：像素数据。
	    pitch：一行像素数据的字节数。
	          成功的话返回0，失败的话返回-1。
	    */
        if(ret<0){
        	LOGI("  SDL_UpdateTexture failed  \n");
        	return;
        }

        SDL_RenderClear( sdlRenderer );
        ret = SDL_RenderCopy( sdlRenderer, sdlTexture, NULL, &sdlRect);
	    if(ret<0){
	        LOGI("  SDL_RenderCopy failed  \n");
	        return;
	    }
	    /*
	     * int SDLCALL SDL_RenderCopy(SDL_Renderer * renderer,
                                           SDL_Texture * texture,
                                           const SDL_Rect * srcrect,
                                           const SDL_Rect * dstrect);
	    renderer：渲染目标。
        texture：输入纹理。
        srcrect：选择输入纹理的一块矩形区域作为输入。设置为NULL的时候整个纹理作为输入。
        dstrect：选择渲染目标的一块矩形区域作为输出。设置为NULL的时候整个渲染目标作为输出。
                     成功的话返回0，失败的话返回-1。
	    */
	    SDL_RenderPresent( sdlRenderer );
	    //Delay 40ms
	    //SDL_Delay(40);

	    run_enc++;
	    if(run_enc>50){
	    	run_enc = 0;
	    	LOGI("  nativeTestEnc %d , %d\n",size, yy_size);
	        LOGI("  nativeTestEnc ok \n");
	    }
	    (*env)->ReleaseByteArrayElements(env, data, (jbyte *)buf, 0);
	    }
}

JNIEXPORT void JNICALL Java_org_libsdl_app_SDLActivity_nativeTestEnc0(JNIEnv *env, jclass clz, jbyteArray data, jint size)
{
	    unsigned char *buf = (unsigned char *)(*env)->GetByteArrayElements(env, data, JNI_FALSE);
	    //memcpy(yuv_buff, buf, yy_size);
        if(rx_queue.V_Len < V_ITEMS){
	       if(rx_queue.IndexW >= V_ITEMS){
              rx_queue.IndexW = 0;
           }
           memcpy(rx_queue.item[rx_queue.IndexW].ptr, buf, yy_size);
           rx_queue.IndexW++;
	       rx_queue.V_Len++;
        }
        (*env)->ReleaseByteArrayElements(env, data, (jbyte *)buf, 0);
}

JNIEXPORT void JNICALL Java_org_libsdl_app_SDLActivity_nativeTestEnd0(JNIEnv *env, jclass clz)
{
	    test_ok = 0;
	    LOGI("  nativeTestEnd0  \n");
}

JNIEXPORT jint JNICALL Java_org_libsdl_app_SDLActivity_nativeTest0(JNIEnv *env, jclass clz)
{
	    LOGI( "  nativeTest0  \n");
	    test_ok = 0;
	    if(SDL_Init(SDL_INIT_VIDEO)) {
	        LOGI( "Could not initialize SDL - %s\n", SDL_GetError());
	        return -1;
	    }
	    //const char* yuv_title = (*env)->GetStringUTFChars(env,file_name, NULL);
	    //const char *yuv_title = "/storage/emulated/0/h264.yuv";//

	    //LOGI( "  yuv_title - %s\n", yuv_title);

	    int n_num, c, nn;
	    int bpp_size = pixel_w*pixel_h*bpp/8;
	    int bpp_p = pixel_w*pixel_h*4;

	    yy_size = n_num = bpp_size;
	    buffer=(unsigned char *)malloc(bpp_size);
	    yuv_buff = buffer;

	    buffer_convert=(unsigned char *)malloc(bpp_p);

	    SDL_Window *screen;
	    //SDL 2.0 Support for multiple windows
	    screen = SDL_CreateWindow("Simplest Video Play SDL2", SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED, //SDL_WINDOWPOS_CENTERED SDL_WINDOWPOS_UNDEFINED
	        screen_w, screen_h,SDL_WINDOW_OPENGL|SDL_WINDOW_SHOWN); //SDL_WINDOW_SHOWN  SDL_WINDOW_OPENGL SDL_WINDOW_RESIZABLE
	    if(!screen) {
	        LOGI("SDL: could not create window - exiting:%s\n",SDL_GetError());
	        return -1;
	    }
	    SDL_Renderer* sdlRenderer = SDL_CreateRenderer(screen, -1, 0);

	    Uint32 pixformat=0;
	    pixformat= SDL_PIXELFORMAT_IYUV;

	    SDL_Texture* sdlTexture = SDL_CreateTexture(sdlRenderer,pixformat, SDL_TEXTUREACCESS_STREAMING, pixel_w, pixel_h);

	    /*
	    FILE *fp=NULL;
	    fp=fopen(yuv_title,"rb+");

	    if(fp==NULL){
	        LOGI("cannot open this file\n");
	        return -1;
	    }
        */
	    SDL_Rect sdlRect;
	    //FIX: If window is resize
	    sdlRect.x = (800-640)/2;
	    sdlRect.y = (1280-480)/2;
	    sdlRect.w = screen_w;
	    sdlRect.h = screen_h;

	    //SDL_Delay(2000);
	    test_ok = 1;
	    while(test_ok){
	        //Wait
	    	    /*
	            if (fread(buffer, 1, pixel_w*pixel_h*bpp/8, fp) != pixel_w*pixel_h*bpp/8){
	                // Loop
	                fseek(fp, 0, SEEK_SET);
	                fread(buffer, 1, pixel_w*pixel_h*bpp/8, fp);
	            }
                */
	            //fgets()函数用于从文件流中读取一行或指定个数的字符，其原型为：
	            //char * fgets(char * string, int size, FILE * stream);
	    	    /*
	    	    nn = 0;
	    	    while(nn<n_num && (c = fgetc(fp))!=EOF) {
	    	       //if ((*buffer++=  c) =='\n')
	    	       //    break;
	    	       buffer[nn] = c;
	    	       nn++;
	    	    }
                */
	    	    if(rx_queue.V_Len){
	    	    	memcpy(buffer, rx_queue.item[rx_queue.IndexR].ptr, yy_size);

	    	    	rx_queue.IndexR++;
	    	        if(rx_queue.IndexR >= V_ITEMS){
	    	    	   rx_queue.IndexR = 0;
	    	    	}
	    	    	rx_queue.V_Len--;
	    	    }
	            SDL_UpdateTexture( sdlTexture, NULL, buffer, pixel_w);

	            //SDL_UpdateYUVTexture(pTexture,&sdlRT,pY,width,pU,width/2, pV,width/2);

	            SDL_RenderClear( sdlRenderer );
	            SDL_RenderCopy( sdlRenderer, sdlTexture, NULL, &sdlRect);
	            SDL_RenderPresent( sdlRenderer );
	            //Delay 40ms
	            SDL_Delay(5);
	    }
	    free(buffer);
	    //(*env)->ReleaseStringUTFChars(env, file_name, yuv_title);

	    return 0;
}

JNIEXPORT jint JNICALL Java_org_libsdl_app_SDLActivity_nativeTest(JNIEnv *env, jclass clz)
{
	    LOGI( "  nativeTest  \n");
	    if(SDL_Init(SDL_INIT_VIDEO)) {
	        LOGI( "Could not initialize SDL - %s\n", SDL_GetError());
	        return -1;
	    }
	    //const char* yuv_title = (*env)->GetStringUTFChars(env,file_name, NULL);
	    const char *yuv_title = "/storage/emulated/0/h264.yuv";

	    LOGI( "  yuv_title - %s\n", yuv_title);

	    int bpp_size = pixel_w*pixel_h*bpp/8;
	    int bpp_p = pixel_w*pixel_h*4;

	    buffer=(unsigned char *)malloc(bpp_size);
	    buffer_convert=(unsigned char *)malloc(bpp_p);

	    SDL_Window *screen;
	    //SDL 2.0 Support for multiple windows
	    screen = SDL_CreateWindow("Simplest Video Play SDL2", SDL_WINDOWPOS_UNDEFINED, SDL_WINDOWPOS_UNDEFINED,
	        screen_w, screen_h,SDL_WINDOW_OPENGL|SDL_WINDOW_SHOWN); //SDL_WINDOW_SHOWN  SDL_WINDOW_OPENGL SDL_WINDOW_RESIZABLE
	    if(!screen) {
	        LOGI("SDL: could not create window - exiting:%s\n",SDL_GetError());
	        return -1;
	    }
	    SDL_Renderer* sdlRenderer = SDL_CreateRenderer(screen, -1, 0);

	    Uint32 pixformat=0;
	#if LOAD_BGRA
	    //Note: ARGB8888 in "Little Endian" system stores as B|G|R|A
	    pixformat= SDL_PIXELFORMAT_ARGB8888;
	#elif LOAD_RGB24
	    pixformat= SDL_PIXELFORMAT_RGB888;
	#elif LOAD_BGR24
	    pixformat= SDL_PIXELFORMAT_BGR888;
	#elif LOAD_YUV420P
	    //IYUV: Y + U + V  (3 planes)
	    //YV12: Y + V + U  (3 planes)
	    pixformat= SDL_PIXELFORMAT_IYUV;
	#endif

	    SDL_Texture* sdlTexture = SDL_CreateTexture(sdlRenderer,pixformat, SDL_TEXTUREACCESS_STREAMING, pixel_w, pixel_h);

	    FILE *fp=NULL;
	#if LOAD_BGRA
	    fp=fopen("../test_bgra_320x180.rgb","rb+");
	#elif LOAD_RGB24
	    fp=fopen("../test_rgb24_320x180.rgb","rb+");
	#elif LOAD_BGR24
	    fp=fopen("../test_bgr24_320x180.rgb","rb+");
	#elif LOAD_YUV420P
	    //fp=fopen("../test_yuv420p_320x180.yuv","rb+");
	    fp=fopen(yuv_title,"rb+");
	#endif
	    if(fp==NULL){
	        LOGI("cannot open this file\n");
	        return -1;
	    }

	    SDL_Rect sdlRect;

	    SDL_Thread *refresh_thread = SDL_CreateThread(refresh_video,NULL,NULL);
	    SDL_Event event;
	    while(1){
	        //Wait
	        SDL_WaitEvent(&event);
	        if(event.type==REFRESH_EVENT){
	            if (fread(buffer, 1, pixel_w*pixel_h*bpp/8, fp) != pixel_w*pixel_h*bpp/8){
	                // Loop
	                fseek(fp, 0, SEEK_SET);
	                fread(buffer, 1, pixel_w*pixel_h*bpp/8, fp);
	            }

	#if LOAD_BGRA
	            //We don't need to change Endian
	            //Because input BGRA pixel data(B|G|R|A) is same as ARGB8888 in Little Endian (B|G|R|A)
	            SDL_UpdateTexture( sdlTexture, NULL, buffer, pixel_w*4);
	#elif LOAD_RGB24|LOAD_BGR24
	            //change 24bit to 32 bit
	            //and in Windows we need to change Endian
	            CONVERT_24to32(buffer,buffer_convert,pixel_w,pixel_h);
	            SDL_UpdateTexture( sdlTexture, NULL, buffer_convert, pixel_w*4);
	#elif LOAD_YUV420P
	            SDL_UpdateTexture( sdlTexture, NULL, buffer, pixel_w);
	#endif
	            //FIX: If window is resize
	            sdlRect.x = 0;
	            sdlRect.y = 0;
	            sdlRect.w = screen_w;
	            sdlRect.h = screen_h;

	            SDL_RenderClear( sdlRenderer );
	            SDL_RenderCopy( sdlRenderer, sdlTexture, NULL, &sdlRect);
	            SDL_RenderPresent( sdlRenderer );
	            //Delay 40ms
	            SDL_Delay(40);

	        }else if(event.type==SDL_WINDOWEVENT){
	            //If Resize
	            SDL_GetWindowSize(screen,&screen_w,&screen_h);
	        }else if(event.type==SDL_QUIT){
	            break;
	        }
	    }
	    free(buffer);
	    free(buffer_convert);
	    //(*env)->ReleaseStringUTFChars(env, file_name, yuv_title);

	    return 0;
}

int main(int argc, char* argv[])
{
	LOGI("       sdl main      \n");
    return 0;
}
