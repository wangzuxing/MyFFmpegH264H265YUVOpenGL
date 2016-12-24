LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := mp4v2
LOCAL_SRC_FILES := libmp4v2.so
include $(PREBUILT_SHARED_LIBRARY) 

include $(CLEAR_VARS)
LOCAL_MODULE := faac
LOCAL_SRC_FILES := libfaac.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := lame
LOCAL_SRC_FILES := libmp3lame.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := x264
LOCAL_SRC_FILES := libx264.so
include $(PREBUILT_SHARED_LIBRARY) 

include $(CLEAR_VARS)
LOCAL_MODULE := rtmp
LOCAL_SRC_FILES := librtmp.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := ffmpeg
LOCAL_SRC_FILES := libffmpeg.so
include $(PREBUILT_SHARED_LIBRARY) 

include $(CLEAR_VARS)
LOCAL_MODULE := live555
LOCAL_SRC_FILES := liblive555.so
include $(PREBUILT_SHARED_LIBRARY) 

include $(CLEAR_VARS)
LOCAL_MODULE := sdl
LOCAL_SRC_FILES := libSDL2.so
include $(PREBUILT_SHARED_LIBRARY) 

include $(CLEAR_VARS)
LOCAL_MODULE := de265
LOCAL_SRC_FILES := libde265.a
include $(PREBUILT_STATIC_LIBRARY) 

include $(CLEAR_VARS)
LOCAL_MODULE := x265
LOCAL_SRC_FILES := libx265.a
include $(PREBUILT_STATIC_LIBRARY) 

include $(CLEAR_VARS)

LOCAL_C_INCLUDES += \
     $(LOCAL_PATH) \
     $(LOCAL_PATH)/live555/BasicUsageEnvironment \
     $(LOCAL_PATH)/live555/BasicUsageEnvironment/include \
     $(LOCAL_PATH)/live555/groupsock \
     $(LOCAL_PATH)/live555/groupsock/include \
     $(LOCAL_PATH)/live555/liveMedia \
     $(LOCAL_PATH)/live555/liveMedia/include \
     $(LOCAL_PATH)/live555/UsageEnvironment \
     $(LOCAL_PATH)/live555/UsageEnvironment/include \
     $(LOCAL_PATH)/live555/mediaServer \
     $(LOCAL_PATH)/lame \
     $(LOCAL_PATH)/ffmpeg \
     $(LOCAL_PATH)/mp4v2 \
     $(LOCAL_PATH)/faac \
     $(LOCAL_PATH)/x264 \
     $(LOCAL_PATH)/x265 \
     $(LOCAL_PATH)/x265/common \
     $(LOCAL_PATH)/x265/encoder \
     $(LOCAL_PATH)/x265/compat \
     $(LOCAL_PATH)/SDL/include \

LOCAL_SHARED_LIBRARIES := mp4v2 faac lame x264 rtmp ffmpeg live555 sdl 
LOCAL_STATIC_LIBRARIES += x265 de265
LOCAL_MODULE := myx265
LOCAL_SRC_FILES += mp.c mp_x.cpp streamer.cpp yuv.c dec265.cpp ./SDL/src/main/android/SDL_android_main.c
LOCAL_LDLIBS += -lGLESv1_CM -llog

include $(BUILD_SHARED_LIBRARY)
