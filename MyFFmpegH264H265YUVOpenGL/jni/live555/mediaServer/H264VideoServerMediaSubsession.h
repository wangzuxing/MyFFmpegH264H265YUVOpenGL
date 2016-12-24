#pragma once

#include "liveMedia.hh"
#include "BasicUsageEnvironment.hh"
#include "GroupsockHelper.hh"

#include "OnDemandServerMediaSubsession.hh"
#include "H264VideoSource.h"

class H264VideoServerMediaSubsession : public OnDemandServerMediaSubsession
{
public:
	H264VideoServerMediaSubsession(UsageEnvironment & env, FramedSource * source);
	~H264VideoServerMediaSubsession(void);

public:
	virtual char const * getAuxSDPLine(RTPSink * rtpSink, FramedSource * inputSource);
	virtual FramedSource * createNewStreamSource(unsigned clientSessionId, unsigned & estBitrate); // "estBitrate" is the stream's estimated bitrate, in kbps
	virtual RTPSink * createNewRTPSink(Groupsock * rtpGroupsock, unsigned char rtpPayloadTypeIfDynamic, FramedSource * inputSource);

	static H264VideoServerMediaSubsession * createNew(UsageEnvironment & env, FramedSource * source);

	static void afterPlayingDummy(void * ptr);

	static void chkForAuxSDPLine(void * ptr);
	void chkForAuxSDPLine1();

private:
	FramedSource * m_pSource;
	char * m_pSDPLine;
	RTPSink * m_pDummyRTPSink;
	char m_done;
};