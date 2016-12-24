/******************************************************************** 
filename:   RTSPStream.h
created:    2013-08-01
author:     firehood 
purpose:    通过live555实现H264 RTSP直播
*********************************************************************/ 
#pragma once
#include <stdio.h>
#ifdef WIN32
#include <windows.h>
#else
#include <pthread.h>
#endif

#ifdef WIN32
typedef HANDLE       ThreadHandle;
#define mSleep(ms)   Sleep(ms)
#else
typedef unsigned int SOCKET;
typedef pthread_t    ThreadHandle;
#define mSleep(ms)   usleep(ms*1000)
#endif

#define FILEBUFSIZE (1024 * 1024) 


class CRTSPStream
{
public:
	CRTSPStream(void);
	~CRTSPStream(void);
public:
	// 初始化
	bool Init();
    // 卸载
	void Uninit();
	// 发送H264文件
	bool SendH264File(const char *pFileName);
	// 发送H264数据帧
    int SendH264Data(const unsigned char *data,unsigned int size);
};
