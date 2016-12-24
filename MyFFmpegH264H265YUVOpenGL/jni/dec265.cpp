/*
 * libde265 example application "dec265".
 * Copyright (c) 2013-2014 struktur AG, Dirk Farin <farin@struktur.de>
 *
 * This file is part of dec265, an example application using libde265.
 *
 * dec265 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * dec265 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with dec265.  If not, see <http://www.gnu.org/licenses/>.
 */

#define DO_MEMORY_LOGGING 0

#include "de265.h"
#include "config.h"
#include <stdio.h>
#include <stdlib.h>
#include <limits>
#include <getopt.h>
#include <malloc.h>
#include <signal.h>
#include <time.h>
#include <sys/time.h>
#include <unistd.h>
//#include "sdl_l.h"
#include "SDL.h"
#include "SDL_log.h"
#include "SDL_main.h"
#include <jni.h>
#include <android/log.h>

#define LOG_TAG "libde265"
#define LOGD(...) ((void)__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__))
#define LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__))
#define LOGW(...) ((void)__android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__))

#ifdef __cplusplus
extern "C" {
#endif

#define BUFFER_SIZE 40960 //40k
#define NUM_THREADS 4

int nThreads=0;
bool nal_input=false;
int quiet=0;
bool check_hash=false;
bool show_help=false;
bool dump_headers=false;
bool write_yuv=false;
bool output_with_videogfx=false;
bool logging=true;
bool no_acceleration=false;
const char *output_filename = "/storage/emulated/0/out0.yuv";
uint32_t max_frames=UINT32_MAX;
bool write_bytestream=false;
const char *bytestream_filename;
bool measure_quality=false;
bool show_ssim_map=false;
bool show_psnr_map=false;
const char* reference_filename;
FILE* reference_file;
int highestTID = 100;
int verbosity=0;
int disable_deblocking=0;
int disable_sao=0;

//SDL_YUV_Display sdlWin;
bool sdl_active=false;
static int width, height;
static uint32_t framecnt=0;

extern int screen_w;
extern int screen_h;
extern const int pixel_w;
extern const int pixel_h;

SDL_Renderer* sdlRenderer0;
SDL_Texture* sdlTexture0;
int yy_size0, yy_size_0;
unsigned char *yuv_buff0;
SDL_Rect sdlRect0;
int  sdl_ok;

void SdlStart()
{
	    LOGI("  SdlStart  start \n");
	    sdl_ok = 0;
	    if(SDL_Init(SDL_INIT_VIDEO)) {
	        LOGI( "Could not initialize SDL - %s\n", SDL_GetError());
	        return ;
	    }

	    int y_size0 = pixel_w*pixel_h*12/8;
	    yy_size0 = screen_w*screen_h;
	    yy_size_0 = yy_size0*3/2;
	    yuv_buff0 = (unsigned char *)malloc(yy_size_0);

	    if(!yuv_buff0) {
	   	    LOGI("SDL: could not create yuv_buff \n");
	   	    return ;
	   	}
	    LOGI(" y_size0 = %d, yy_size = %d\n", y_size0, yy_size0);
	    LOGI(" screen_w = %d, screen_h = %d, pixel_w = %d, pixel_h = %d\n", screen_w, screen_h, pixel_w,pixel_h);
	    //FIX: If window is resize
	   	sdlRect0.x = 0;
	   	sdlRect0.y = 0;
	   	sdlRect0.w = screen_w;
	    sdlRect0.h = screen_h;

	    SDL_Window *screen;
	    //SDL 2.0 Support for multiple windows
	    screen = SDL_CreateWindow("Simplest Video Play SDL2", SDL_WINDOWPOS_UNDEFINED, SDL_WINDOWPOS_UNDEFINED,
	    	        screen_w, screen_h,SDL_WINDOW_OPENGL|SDL_WINDOW_SHOWN); //SDL_WINDOW_SHOWN  SDL_WINDOW_OPENGL SDL_WINDOW_RESIZABLE
	    if(!screen) {
	        LOGI("SDL: could not create window - exiting:%s\n",SDL_GetError());
	        return ;
	    }

	    sdlRenderer0 = SDL_CreateRenderer(screen, -1, 0);
	    if(!sdlRenderer0) {
	    	LOGI("SDL: could not create renderer\n");
	        return ;
	    }

	    Uint32 pixformat=0;
	    pixformat= SDL_PIXELFORMAT_IYUV;

	    sdlTexture0 = SDL_CreateTexture(sdlRenderer0, pixformat, SDL_TEXTUREACCESS_STREAMING, pixel_w, pixel_h);
	    if(!sdlTexture0) {
	    	LOGI("SDL: could not create texture\n");
	    	return ;
	    }
	    sdl_ok = 1;
	    LOGI("  SdlStart  end \n");
}

void SdlEnc(unsigned char *buf, int size)
{
	    int ret;
	    static int run_n=0;
	    ret = SDL_UpdateTexture( sdlTexture0, NULL, buf, pixel_w);
        if(ret<0){
        	LOGI("  SDL_UpdateTexture failed  \n");
        	return;
        }

        SDL_RenderClear( sdlRenderer0 );
        ret = SDL_RenderCopy( sdlRenderer0, sdlTexture0, NULL, &sdlRect0);
	    if(ret<0){
	        LOGI("  SDL_RenderCopy failed  \n");
	        return;
	    }
	    SDL_RenderPresent( sdlRenderer0 );
	    //Delay 40ms
	    //SDL_Delay(40);
	    run_n++;
	    if(run_n>10){
	    	run_n = 0;
	    	//LOGI("  SdlEnc %d , %d\n",size, yy_size_0);
	    }
}

static void write_picture(const de265_image* img)
{
  static FILE* fh = NULL;
  if (fh==NULL) {
	  fh = fopen(output_filename, "wb");
	  LOGI( "write_picture  %s\n", output_filename);

	  int width  = de265_get_image_width(img,0);
	  int height = de265_get_image_height(img,0);

	  LOGI( "width = %d, height = %d\n", width, height);
  }
  for (int c=0;c<3;c++) {
    int stride;
    const uint8_t* p = de265_get_image_plane(img, c, &stride);
    int width = de265_get_image_width(img,c);

    if (de265_get_bits_per_pixel(img,c)<=8) {
      // --- save 8 bit YUV ---
      for (int y=0;y<de265_get_image_height(img,c);y++) {
        fwrite(p + y*stride, width, 1, fh);
      }
    }else {
      // --- save 16 bit YUV ---
      int bpp = (de265_get_bits_per_pixel(img,c)+7)/8;
      int pixelsPerLine = stride/bpp;

      uint8_t* buf = new uint8_t[width*2];
      uint16_t* p16 = (uint16_t*)p;

      for (int y=0;y<de265_get_image_height(img,c);y++) {
        for (int x=0;x<width;x++) {
          uint16_t pixel_value = (p16+y*pixelsPerLine)[x];
          buf[2*x+0] = pixel_value & 0xFF;
          buf[2*x+1] = pixel_value >> 8;
        }
        fwrite(buf, width*2, 1, fh);
      }
      delete[] buf;
    }
  }
  fflush(fh);
}

/*
static uint8_t* convert_to_8bit(const uint8_t* data, int width, int height, int pixelsPerLine, int bit_depth)
{
  const uint16_t* data16 = (const uint16_t*)data;
  uint8_t* out = new uint8_t[pixelsPerLine*height];

  for (int y=0;y<height;y++) {
    for (int x=0;x<width;x++) {
      out[y*pixelsPerLine + x] = *(data16 + y*pixelsPerLine +x) >> (bit_depth-8);
    }
  }
  return out;
}

bool display_sdl(const struct de265_image* img)
{
  int width  = de265_get_image_width(img,0);
  int height = de265_get_image_height(img,0);

  int chroma_width  = de265_get_image_width(img,1);
  int chroma_height = de265_get_image_height(img,1);

  de265_chroma chroma = de265_get_chroma_format(img);

  if (!sdl_active) {
    sdl_active=true;
    enum SDL_YUV_Display::SDL_Chroma sdlChroma;
    switch (chroma) {
       case de265_chroma_420:  sdlChroma = SDL_YUV_Display::SDL_CHROMA_420;  break;
       case de265_chroma_422:  sdlChroma = SDL_YUV_Display::SDL_CHROMA_422;  break;
       case de265_chroma_444:  sdlChroma = SDL_YUV_Display::SDL_CHROMA_444;  break;
       case de265_chroma_mono: sdlChroma = SDL_YUV_Display::SDL_CHROMA_MONO; break;
    }

    sdlWin.init(width, height, sdlChroma);
  }

  int stride,chroma_stride;
  const uint8_t* y = de265_get_image_plane(img,0,&stride);
  const uint8_t* cb =de265_get_image_plane(img,1,&chroma_stride);
  const uint8_t* cr =de265_get_image_plane(img,2,NULL);

  int bpp_y = (de265_get_bits_per_pixel(img,0)+7)/8;
  int bpp_c = (de265_get_bits_per_pixel(img,1)+7)/8;
  int ppl_y = stride/bpp_y;
  int ppl_c = chroma_stride/bpp_c;

  uint8_t* y16  = NULL;
  uint8_t* cb16 = NULL;
  uint8_t* cr16 = NULL;
  int bd;

  if ((bd=de265_get_bits_per_pixel(img, 0)) > 8) {
      y16  = convert_to_8bit(y,  width,height,ppl_y,bd); y=y16;
  }

  if (chroma != de265_chroma_mono) {
     if ((bd=de265_get_bits_per_pixel(img, 1)) > 8) {
        cb16 = convert_to_8bit(cb, chroma_width,chroma_height,ppl_c,bd); cb=cb16;
     }
     if ((bd=de265_get_bits_per_pixel(img, 2)) > 8) {
        cr16 = convert_to_8bit(cr, chroma_width,chroma_height,ppl_c,bd); cr=cr16;
     }
  }

  sdlWin.display(y, cb, cr, ppl_y, ppl_c);

  delete[] y16;
  delete[] cb16;
  delete[] cr16;

  return sdlWin.doQuit();
}
*/

bool output_image(const de265_image* img)
{
  bool stop=false;
  width  = de265_get_image_width(img,0);
  height = de265_get_image_height(img,0);

  framecnt++;
  //LOGI("SHOW POC: %d / PTS: %ld / integrity: %d\n",img->PicOrderCntVal, img->pts, img->integrity);
  if (0) {
    const char* nal_unit_name;
    int nuh_layer_id;
    int nuh_temporal_id;
    de265_get_image_NAL_header(img, NULL, &nal_unit_name, &nuh_layer_id, &nuh_temporal_id);

    LOGI("NAL: %s layer:%d temporal:%d\n",nal_unit_name, nuh_layer_id, nuh_temporal_id);
  }

  if (!quiet) {
      //stop = display_sdl(img);
	  int stride,chroma_stride;
	  const uint8_t* y = de265_get_image_plane(img,0,&stride);
	  const uint8_t* cb =de265_get_image_plane(img,1,&chroma_stride);
	  const uint8_t* cr =de265_get_image_plane(img,2,NULL);

	  memcpy(yuv_buff0, y, yy_size0);
	  memcpy(yuv_buff0+yy_size0, cb, yy_size0/4);
	  memcpy(yuv_buff0+yy_size0*5/4, cr, yy_size0/4);

	  SdlEnc(yuv_buff0, yy_size_0);
  }

  if (write_yuv) {
      //write_picture(img);
  }

  if ((framecnt%100)==0) {
      LOGI("frame %d\r",framecnt);
  }

  if (framecnt>=max_frames) {
      stop=true;
  }

  return stop;
}

bool stop;

JNIEXPORT void JNICALL Java_org_libsdl_app_SDLActivity_nativeDecodeEnd(JNIEnv *env, jclass clz)
{
  LOGI( "  SDLActivity_nativeDecode  end \n");
  bool stop=true;
}

JNIEXPORT jint JNICALL Java_org_libsdl_app_SDLActivity_nativeDecode(JNIEnv *env, jclass clz, jstring file_name)
{
  LOGI( "  SDLActivity_nativeDecode  \n");
  LOGI( "  SDLActivity_nativeDecode  \n");
  LOGI("    nativeDecode start  \n");
  LOGI("    nativeDecode start  \n");
  //check_hash=true;
  write_yuv=true;
  //dump_headers=true;
  //nal_input=false;
  SdlStart();
  if(sdl_ok==0){
	  return -1;
  }

  de265_error err =DE265_OK;

  de265_decoder_context* ctx = de265_new_decoder();

  de265_set_parameter_bool(ctx, DE265_DECODER_PARAM_BOOL_SEI_CHECK_HASH, check_hash);
  de265_set_parameter_bool(ctx, DE265_DECODER_PARAM_SUPPRESS_FAULTY_PICTURES, false);

  de265_set_parameter_bool(ctx, DE265_DECODER_PARAM_DISABLE_DEBLOCKING, disable_deblocking);
  de265_set_parameter_bool(ctx, DE265_DECODER_PARAM_DISABLE_SAO, disable_sao);

  if (dump_headers) {
    de265_set_parameter_int(ctx, DE265_DECODER_PARAM_DUMP_SPS_HEADERS, 1);
    de265_set_parameter_int(ctx, DE265_DECODER_PARAM_DUMP_VPS_HEADERS, 1);
    de265_set_parameter_int(ctx, DE265_DECODER_PARAM_DUMP_PPS_HEADERS, 1);
    de265_set_parameter_int(ctx, DE265_DECODER_PARAM_DUMP_SLICE_HEADERS, 1);
  }

  if (no_acceleration) {
    de265_set_parameter_int(ctx, DE265_DECODER_PARAM_ACCELERATION_CODE, de265_acceleration_SCALAR);
  }

  if (!logging) {
    de265_disable_logging();
  }

  de265_set_verbosity(verbosity);

  nThreads = 2;
  if (nThreads>0) {
      err = de265_start_worker_threads(ctx, nThreads);
  }

  de265_set_limit_TID(ctx, highestTID);

  const char* h265_title = env->GetStringUTFChars(file_name, NULL);

  //const char *yuv_title = "/storage/emulated/0/H265_01f.h265";//yuv_title

  LOGI( "yuv_title - %s\n", h265_title);

  FILE* fh = fopen(h265_title, "rb");
  if (fh==NULL) {
     LOGI("cannot open file!\n");
     return -1;
  }

  FILE* bytestream_fh = NULL;

  if (write_bytestream) {
     bytestream_fh = fopen(bytestream_filename, "wb");
  }

  struct timeval tv_start;
  gettimeofday(&tv_start, NULL);

  LOGI("nal_input  %d\n", nal_input);

  int pos=0;
  while (!stop)
  {
      //tid = (framecnt/1000) & 1;
      //de265_set_limit_TID(ctx, tid);
      if (nal_input) {
    	  uint8_t len[4];
    	  int n = fread(len,1,4,fh);
    	  int length = (len[0]<<24) + (len[1]<<16) + (len[2]<<8) + len[3];
    	  uint8_t* buf = (uint8_t*)malloc(length);
    	  n = fread(buf,1,length,fh);
    	  err = de265_push_NAL(ctx, buf,n,  pos, (void*)1);

    	  if (write_bytestream) {
    	      uint8_t sc[3] = { 0,0,1 };
    	      fwrite(sc ,1,3,bytestream_fh);
    	      fwrite(buf,1,n,bytestream_fh);
    	  }
    	  free(buf);
    	  pos+=n;
      }
      else {
        // read a chunk of input data
        uint8_t buf[BUFFER_SIZE];
        int n = fread(buf,1,BUFFER_SIZE,fh);

        // decode input data
        if (n) {
          err = de265_push_data(ctx, buf, n, pos, (void*)2);
          if (err != DE265_OK) {
              break;
          }
        }
        pos+=n;
        if (0) { // fake skipping
          if (pos>1000000) {
            LOGI("RESET\n");
            de265_reset(ctx);
            pos=0;
            fseek(fh,-200000,SEEK_CUR);
          }
        }
      }
      // LOGI("pending data: %d\n", de265_get_number_of_input_bytes_pending(ctx));
      if (feof(fh)) {
        err = de265_flush_data(ctx); // indicate end of stream
        stop = true;
      }
      // decoding / display loop
      int more=1;
      while (more)
      {
          more = 0;
          // decode some more
          err = de265_decode(ctx, &more);
          if (err != DE265_OK) {
            // if (quiet<=1) LOGI("ERROR: %s\n", de265_get_error_text(err));
            if (check_hash && err == DE265_ERROR_CHECKSUM_MISMATCH)
              stop = 1;
            more = 0;
            break;
          }
          // show available images
          const de265_image* img = de265_get_next_picture(ctx);
          if (img) {
              stop = output_image(img);
              if (stop)
            	  more=0;
              else
            	  more=1;
          }
          // show warnings
          for (;;) {
            de265_error warning = de265_get_warning(ctx);
            if (warning==DE265_OK) {
                break;
            }
            if (quiet<=1)
            	LOGI("WARNING: %s\n", de265_get_error_text(warning));
          }
       }
  }
  fclose(fh);
  if (write_bytestream) {
    fclose(bytestream_fh);
  }
  de265_free_decoder(ctx);

  struct timeval tv_end;
  gettimeofday(&tv_end, NULL);

  if (err != DE265_OK) {
    if (quiet<=1)
    	LOGI("decoding error: %s (code=%d)\n", de265_get_error_text(err), err);
  }

  double secs = tv_end.tv_sec-tv_start.tv_sec;
  secs += (tv_end.tv_usec - tv_start.tv_usec)*0.001*0.001;

  if (quiet<=1)
	  LOGI("nFrames decoded: %d (%dx%d @ %5.2f fps)\n",framecnt,width,height,framecnt/secs);

  LOGI("    nativeDecode end  \n");
  free(yuv_buff0);
  LOGI("    nativeDecode end  \n");

  env->ReleaseStringUTFChars(file_name, h265_title);

  return err==DE265_OK ? 0 : 10;
}

#ifdef __cplusplus
}
#endif
