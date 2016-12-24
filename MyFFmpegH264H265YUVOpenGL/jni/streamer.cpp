#include <jni.h>
#include <android/log.h>
#include <liveMedia.hh>
#include <BasicUsageEnvironment.hh>
#include <GroupsockHelper.hh>
#include <pthread.h>
#include "DynamicRTSPServer.hh"
#include "version.hh"
#include "RTSPStream.h"
#include "H264VideoSource.h"
#include "H264VideoServerMediaSubsession.h"

#ifdef __cplusplus
extern "C" {
#endif

#define LOG_TAG "streamer"
#define LOGW(a )  __android_log_write(ANDROID_LOG_WARN,LOG_TAG,a)
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO,LOG_TAG,__VA_ARGS__)
#define LOGD(...)  __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
void play();
void afterPlaying(void* /*clientData*/);
//}

UsageEnvironment* uEnv;
H264VideoStreamFramer* videoSource;
RTPSink* videoSink;
char quit;
char* url;
int port_number;
FILE *file;
char const *inputFilename;
Boolean reuseFirstSource  = False;
Boolean reuseFirstSource0 = True;
pthread_mutex_t   mutex;

//将const char类型转换成jstring类型
jstring CStr2Jstring0(JNIEnv* env, const char* pat )
{
    // 定义java String类 strClass
    jclass strClass = (env)->FindClass("java/lang/String;");
    // 获取java String类方法String(byte[],String)的构造器,用于将本地byte[]数组转换为一个新String
    jmethodID ctorID = (env)->GetMethodID(strClass, "<init>", "([BLjava/lang/String;)V");
    // 建立byte数组
    jbyteArray bytes = (env)->NewByteArray((jsize)strlen(pat));
    // 将char* 转换为byte数组
    (env)->SetByteArrayRegion(bytes, 0, (jsize)strlen(pat), (jbyte*)pat);
    //设置String, 保存语言类型,用于byte数组转换至String时的参数
    jstring encoding = (env)->NewStringUTF("GB2312");
    //将byte数组转换为java String,并输出
    return (jstring)(env)->NewObject(strClass, ctorID, bytes, encoding);

}
char * Jstring2CStr0( JNIEnv * env, jstring jstr )
{
    char * rtn = NULL;
    jclass clsstring = env->FindClass("java/lang/String");
    jstring strencode = env->NewStringUTF("GB2312");
    jmethodID mid = env->GetMethodID(clsstring, "getBytes", "(Ljava/lang/String;)[B");
    jbyteArray barr= (jbyteArray)env->CallObjectMethod(jstr,mid,strencode);
    jsize alen = env->GetArrayLength(barr);
    jbyte * ba = env->GetByteArrayElements(barr,JNI_FALSE);
    if(alen > 0)
    {
        rtn = (char*)malloc(alen+1); //new char[alen+1];
        memcpy(rtn,ba,alen);
        rtn[alen]=0;
    }
    env->ReleaseByteArrayElements(barr,ba,0);

    return rtn;
}

jstring CharTojstring0(JNIEnv* env, char* str)
{
    jsize len = strlen(str);
    jclass  clsstring = env->FindClass("java/lang/String");
    jstring  strencode = env->NewStringUTF("GB2312");
    jmethodID  mid = env->GetMethodID(clsstring,"<init>","([BLjava/lang/String;)V");
    jbyteArray  barr = env-> NewByteArray(len);
    env-> SetByteArrayRegion(barr,0,len,(jbyte*)str);
    return (jstring)env-> NewObject(clsstring,mid,barr,strencode);
}

void doExecuteVideoViewShow0(JNIEnv * env, jobject obj, jstring sdp) {
	// 1.找到java的MainActivity的class
	jclass clazz = env->FindClass("com/example/mylive55servermediacodec2/StreamerActivity2");
	if (clazz == 0) {
		LOGI("can't find clazz");
	}
	LOGI(" find clazz");

	//2 找到class 里面的方法定义
	jmethodID methodid = env->GetMethodID(clazz, "PlayRtspStream", "(Ljava/lang/String;)V");
	if (methodid == 0) {
		LOGI("can't find methodid");
	}
	LOGI(" find methodid");

	//3 .调用方法
	env->CallVoidMethod(clazz, methodid, sdp);
}

static void announceStream0(RTSPServer* rtspServer, ServerMediaSession* sms,
		char const* streamName, char const* inputFileName)
{
	char* url = rtspServer->rtspURL(sms);
	char buf[255] = { 0 };
	UsageEnvironment& env = rtspServer->envir();
	sprintf(buf,
			"%s-->stream, from the file-->%s,Play this stream using the URL:%s",
			streamName, inputFileName, url);
	LOGW(buf);
	delete[] url;
}

CRTSPStream *rtspSender;

int count_run = 15;

JNIEXPORT void JNICALL Java_com_qjie_study_StreamerActivity2_RtspH264Data
(JNIEnv *env, jobject obj, jbyteArray data, jint size )
{
	unsigned char *buf = (unsigned char *)env->GetByteArrayElements(data, JNI_FALSE);
	rtspSender->SendH264Data(buf, size);
	count_run++;
	if(count_run>15){
	   count_run = 0;
	   LOGE("SendH264Data  %d  ", size);
	}
	env->ReleaseByteArrayElements(data, (jbyte *)buf, 0);
}

JNIEXPORT void JNICALL Java_com_qjie_study_StreamerActivity2_RtspLiving
(JNIEnv *env, jobject obj, jstring filename)
{
	const char* inputFilename = env->GetStringUTFChars(filename, NULL);
	LOGE("  RtspLiving %s  ", inputFilename);
	LOGE("  RtspLiving %s  ", inputFilename);
	rtspSender->SendH264File(inputFilename);	//"test.h264"
	LOGE("  RtspLiving End  ");
	env->ReleaseStringUTFChars(filename, inputFilename);
}

JNIEXPORT void JNICALL Java_com_qjie_study_StreamerActivity2_RtspLive
(JNIEnv *env1, jobject obj)
{
	rtspSender = new CRTSPStream();

	bool bRet = rtspSender->Init();//fix temp file dir
	if(bRet<0){
		LOGE("              rtspSender.Init() error        ");
		exit(1);
	}

    // Begin by setting up our usage environment:
    TaskScheduler* scheduler = BasicTaskScheduler::createNew();
    UsageEnvironment* env = BasicUsageEnvironment::createNew(*scheduler);

    UserAuthenticationDatabase* authDB = NULL;
    /*
#ifdef ACCESS_CONTROL
    // To implement client access control to the RTSP server, do the following:
    authDB = new UserAuthenticationDatabase;
    authDB->addUserRecord("username1", "password1"); // replace these with real strings
    // Repeat the above with each <username>, <password> that you wish to allow
    // access to the server.
#endif
    */

    // Create the RTSP server:
    RTSPServer* rtspServer = RTSPServer::createNew(*env, 8554, NULL);
    if (rtspServer == NULL) {
        *env << "Failed to create RTSP server: " << env->getResultMsg() << "\n";
        exit(1);
    }

    // Add live stream
    // frame_rate = 15;
    H264VideoSource * videoSource = 0;

    ServerMediaSession * sms = ServerMediaSession::createNew(*env, "live", "live", "www live test");
    sms->addSubsession(H264VideoServerMediaSubsession::createNew(*env, videoSource));
    rtspServer->addServerMediaSession(sms);

    char * url = rtspServer->rtspURL(sms);
    //*env << "using url \"" << url << "\"\n";
    LOGI("Play this stream using the URL \"%s\"", url);

    delete[] url;

    if (rtspServer->setUpTunnelingOverHTTP(80)
    			|| rtspServer->setUpTunnelingOverHTTP(8000)
    			|| rtspServer->setUpTunnelingOverHTTP(8080)) {
    		LOGI("we use port-->%d", rtspServer->httpServerPortNum());
    } else {
    		LOGI("RTSP-over-HTTP tunneling is not available.");
    }

    port_number = rtspServer->httpServerPortNum();
    LOGI("we use port-->%d", port_number);

	quit = 0;
    // Run loop
    env->taskScheduler().doEventLoop(&quit);

    rtspServer->removeServerMediaSession(sms);
    Medium::close(rtspServer);

    env->reclaim();
    env = NULL;
    delete scheduler;
    scheduler = NULL;

    LOGI("......End......\n");
    LOGI("......End......\n");
}

JNIEXPORT jint JNICALL Java_com_qjie_study_StreamerActivity2_RtspServer
(JNIEnv *env1, jobject obj, jstring filename)
{
	TaskScheduler* scheduler = BasicTaskScheduler::createNew();
	UsageEnvironment* env = BasicUsageEnvironment::createNew(*scheduler);

	UserAuthenticationDatabase* authDB = NULL;

	RTSPServer* rtspServer = RTSPServer::createNew(*env, 8554, authDB);
	if (rtspServer == NULL) {
		LOGI("Failed to create RTSP server:%s", env->getResultMsg());
		return -1;
	} else {
		LOGI("create RTSP server successful in 8554!");
	}

	char const* descriptionString =
			"Session streamed by \"testOnDemandRTSPServer\"";

	ServerMediaSession* sms = NULL;
	// A H.264 video elementary stream:
	{
		char const* streamName = "mstreamer";
		char const* inputFileName = env1->GetStringUTFChars(filename, NULL); // "/sdcard/test.264";

		LOGI("   inputFileName = %s", inputFileName);
		/*
		fp1 = fopen(inputFileName, "wt+");
		if(!fp1){
		   //printf("ERROR:open file failed!");
		   LOGI("              h264 fopen error                ");
		   return -1;
		}
        */
		//(*env)->GetStringUTFChars(env, filename, NULL);
	    sms = ServerMediaSession::createNew(*env,
				streamName, streamName, descriptionString);
		sms->addSubsession(
				H264VideoFileServerMediaSubsession::createNew(*env,
						inputFileName, reuseFirstSource0));
		rtspServer->addServerMediaSession(sms);

		announceStream0(rtspServer, sms, streamName, inputFileName);

		env1->ReleaseStringUTFChars(filename, inputFileName);
		//(*env)->ReleaseStringUTFChars(env, filename, inputFileName);

		//char *url = rtspServer->rtspURL(sms);

		//doExecuteVideoViewShow0(env1, obj, CharTojstring0(env1, url));
	}
    /*
	{
		char const* streamName = "ts";
		char const* inputFileName = "/sdcard/H264_720p.ts";
		char const* indexFileName = "test.tsx";
		ServerMediaSession* sms = ServerMediaSession::createNew(*env,
				streamName, streamName, descriptionString);
		sms->addSubsession(
				MPEG2TransportFileServerMediaSubsession::createNew(*env,
						inputFileName, indexFileName, reuseFirstSource0));
		rtspServer->addServerMediaSession(sms);

		announceStream0(rtspServer, sms, streamName, inputFileName);
	}
    */
	url = rtspServer->rtspURL(sms);
		LOGI("Play this stream using the URL \"%s\"", url);
		LOGI("Play this stream using the URL \"%s\"", url);

	if (rtspServer->setUpTunnelingOverHTTP(80)
			|| rtspServer->setUpTunnelingOverHTTP(8000)
			|| rtspServer->setUpTunnelingOverHTTP(8080)) {
		LOGI("we use port-->%d", rtspServer->httpServerPortNum());
	} else {
		LOGI("RTSP-over-HTTP tunneling is not available.");
	}

	port_number = rtspServer->httpServerPortNum();
	LOGI("we use port-->%d", port_number);
	delete[] url;

	quit = 0;
	env->taskScheduler().doEventLoop(&quit);

	rtspServer->removeServerMediaSession(sms);
	Medium::close(rtspServer);

	env->reclaim();
	env = NULL;
    delete scheduler;
	scheduler = NULL;

	LOGI("......End......\n");
	LOGI("......End......\n");

	return 0;
}

JNIEXPORT void JNICALL Java_com_qjie_study_StreamerActivity2_loop(JNIEnv *env,
		jobject obj, jstring addr) {
	// Begin by setting up our usage environment:
	TaskScheduler* scheduler = BasicTaskScheduler::createNew();
	uEnv = BasicUsageEnvironment::createNew(*scheduler);

	//inputFilename = env->GetStringUTFChars(h264file, NULL);
	//LOGE("  loop %s  ", inputFilename);
	//LOGE("  loop %s  ", inputFilename);

	// Create 'groupsocks' for RTP and RTCP:
	struct in_addr destinationAddress;
	const char *_addr = env->GetStringUTFChars(addr, NULL);
	destinationAddress.s_addr = our_inet_addr(_addr); /*chooseRandomIPv4SSMAddress(*uEnv);*/
	env->ReleaseStringUTFChars(addr, _addr);

	// Note: This is a multicast address.  If you wish instead to stream
	// using unicast, then you should use the "testOnDemandRTSPServer"
	// test program - not this test program - as a model.

	const unsigned short rtpPortNum = 18888;
	const unsigned short rtcpPortNum = rtpPortNum + 1;
	const unsigned char ttl = 255;

	const Port rtpPort(rtpPortNum);
	const Port rtcpPort(rtcpPortNum);

	Groupsock rtpGroupsock(*uEnv, destinationAddress, rtpPort, ttl);
	Groupsock rtcpGroupsock(*uEnv, destinationAddress, rtcpPort, ttl);

	// Create a 'H264 Video RTP' sink from the RTP 'groupsock':
	OutPacketBuffer::maxSize = 100000;
	videoSink = H264VideoRTPSink::createNew(*uEnv, &rtpGroupsock, 96);  //&rtpGroupsock

	// Create (and start) a 'RTCP instance' for this RTP sink:
	const unsigned estimatedSessionBandwidth = 250; // in kbps; for RTCP b/w share
	const unsigned maxCNAMElen = 100;
	unsigned char CNAME[maxCNAMElen + 1];
	gethostname((char*) CNAME, maxCNAMElen);
	CNAME[maxCNAMElen] = '\0'; // just in case

	LOGI(" CNAME = %s", (const char*)CNAME);

	RTCPInstance* rtcp = RTCPInstance::createNew(*uEnv, &rtcpGroupsock,
				estimatedSessionBandwidth, CNAME, videoSink,
				NULL /* we're a server */, True /* we're a SSM source */);
		// Note: This starts RTCP running automatically

	RTSPServer* rtspServer = RTSPServer::createNew(*uEnv, 8554);
	if (rtspServer == NULL) {
		LOGE("Failed to create RTSP server: %s", uEnv->getResultMsg());
		exit(1);
	}
	ServerMediaSession* sms = ServerMediaSession::createNew(*uEnv, "streamer",
				inputFilename, "Session streamed by \"testH264VideoStreamer\"",
				True /*SSM*/);

	sms->addSubsession(
			PassiveServerMediaSubsession::createNew(*videoSink, rtcp));
	rtspServer->addServerMediaSession(sms);

	url = rtspServer->rtspURL(sms);
	LOGI("Play this stream using the URL \"%s\"", url);
	LOGI("Play this stream using the URL \"%s\"", url);

	if (rtspServer->setUpTunnelingOverHTTP(80)
				|| rtspServer->setUpTunnelingOverHTTP(8000)
				|| rtspServer->setUpTunnelingOverHTTP(8080)) {
			LOGI("we use port-->%d", rtspServer->httpServerPortNum());
	} else {
			LOGI("RTSP-over-HTTP tunneling is not available.");
	}

	port_number = rtspServer->httpServerPortNum();
	LOGI("we use port-->%d", port_number);
	delete[] url;

	quit = 0;
	// Start the streaming:
	LOGI("Beginning streaming...\n");
	LOGI("Beginning streaming...\n");
	play();

	uEnv->taskScheduler().doEventLoop(&quit); // does not return

	rtspServer->removeServerMediaSession(sms);
	Medium::close(rtspServer);

    uEnv->reclaim();
    uEnv = NULL;
	delete scheduler;
	scheduler = NULL;

	LOGI("......End......\n");
	LOGI("......End......\n");
	//env->ReleaseStringUTFChars(h264file, inputFilename);
}

JNIEXPORT void JNICALL Java_com_qjie_study_StreamerActivity2_end(JNIEnv *env,
		jobject obj) {
	 LOGI("Ending streaming...\n");
	 LOGI("Ending streaming...\n");
	 quit = 1;
	 // Medium::close(rtspServer);
}

JNIEXPORT void JNICALL Java_com_qjie_study_StreamerActivity2_RtspTempFile
(JNIEnv *env, jobject obj, jstring filename)
{
	inputFilename = env->GetStringUTFChars(filename, NULL);

	file = fopen(inputFilename, "wb+");
	if (!file) {
		LOGE("couldn't open %s", inputFilename);
		exit(1);
	}

	LOGE("  open %s  ", inputFilename);
	LOGE("  open %s  ", inputFilename);

	pthread_mutex_init(&mutex, NULL);
	//env1->ReleaseStringUTFChars(filename, inputFileName);
}

//添加视频帧的方法
JNIEXPORT void JNICALL Java_com_qjie_study_StreamerActivity2_WriteRtspFrame(
		JNIEnv *env, jclass clz, jbyteArray data, jint size)
{
	unsigned char *buf = (unsigned char *)env->GetByteArrayElements(data, JNI_FALSE);
	//length
	pthread_mutex_lock(&mutex);
    int ret = fwrite(buf, 1, size, file);
    if(ret != 0){
	   //LOGI(" IDR = %d, %d", ret, size);
    }
    pthread_mutex_unlock(&mutex);
	env->ReleaseByteArrayElements(data, (jbyte *)buf, 0);
}

void play() {
	// Open the input file as a 'byte-stream file source':
	ByteStreamFileSource* fileSource = ByteStreamFileSource::createNew(*uEnv,
			inputFilename);
	if (fileSource == NULL) {
		LOGE(
				"Unable to open file \"%s\" as a byte-stream file source", inputFilename);
		exit(1);
	}

	FramedSource* videoES = fileSource;

	// Create a framer for the Video Elementary Stream:
	videoSource = H264VideoStreamFramer::createNew(*uEnv, videoES);

	// Finally, start playing:
	LOGI("Beginning to read from file...\n");
	videoSink->startPlaying(*videoSource, afterPlaying, videoSink);
}

unsigned char play_number;

void afterPlaying(void* /*clientData*/) {
	play_number++;
	LOGI("...done reading from file");
	/*
	if(play_number>25){
		play_number = 0;
	    //LOGI("...done reading from file");

		LOGI("URL %s , port: %d\n", url, port_number);
	}
	*/
	videoSink->stopPlaying();
	Medium::close(videoSource);
	// Note that this also closes the input file that this source read from.

	// Start playing once again:
	play();
}

#ifdef __cplusplus
}
#endif
