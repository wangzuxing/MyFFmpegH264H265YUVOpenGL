LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_WHOLE_STATIC_LIBRARIES := libavutil libavformat libavcodec libavfilter libpostproc libswscale
LOCAL_LDLIBS := -LF:/cygwin64/android-ndk-r8d/platforms/android-8/arch-arm/usr/lib -lfaac -lmp3lame -lx264 -lrtmp -lz
LOCAL_MODULE := ffmpeg
include $(BUILD_SHARED_LIBRARY)
include $(call all-makefiles-under,$(LOCAL_PATH))