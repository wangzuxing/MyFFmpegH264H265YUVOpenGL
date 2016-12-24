export NDK=F:/cygwin64/android-ndk-r8d
export PREBUILT=$NDK/toolchains/arm-linux-androideabi-4.4.3/prebuilt
export PLATFORM=$NDK/platforms/android-8/arch-arm 
export PREFIX=F:/cygwin64/videojni/x264/jni/android-x264
./configure --prefix=$PREFIX \
--enable-static \
--enable-shared \
--enable-pic \
--disable-asm \
--disable-cli \
--host=arm-linux \
--cross-prefix=$PREBUILT/windows/bin/arm-linux-androideabi- \
--sysroot=$PLATFORM