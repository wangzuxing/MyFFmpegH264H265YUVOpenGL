LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := mp3lame
LOCAL_SRC_FILES := libmp3lame.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := faac
LOCAL_SRC_FILES := libfaac.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := x264
LOCAL_SRC_FILES := libx264.so
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

LOCAL_C_INCLUDES := \
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
	$(LOCAL_PATH)/ffmpeg \
    $(LOCAL_PATH)/lame \
    $(LOCAL_PATH)/faac \
    $(LOCAL_PATH)/x264
	
LOCAL_SHARED_LIBRARIES := ffmpeg mp3lame faac x264 live555

LOCAL_MODULE := streamer
LOCAL_SRC_FILES := streamer.cpp
#LOCAL_MODULE := rtspclient
#LOCAL_SRC_FILES := live555test.cpp
LOCAL_LDLIBS    += -llog -lz

include $(BUILD_SHARED_LIBRARY)
