// melonDS-android 2.0.1, GPL-3.0-or-later. Modified by Calamansi 2026-10-12: report failed native EGL initialization.
#include "JniEnvHandler.h"
#include "UriFileHandler.h"
#include "MelonDS.h"
#include "OpenGLContext.h"

JniEnvHandler* jniEnvHandler;

JavaVM* vm;
jobject androidUriFileHandler;
UriFileHandler* fileHandler;

extern "C"
{
JNIEXPORT void JNICALL
Java_me_magnum_melonds_MelonDSAndroidInterface_setup(JNIEnv* env, jobject thiz, jobject uriFileHandler)
{
    env->GetJavaVM(&vm);
    jniEnvHandler = new JniEnvHandler(vm);
    androidUriFileHandler = env->NewGlobalRef(uriFileHandler);
    fileHandler = new UriFileHandler(jniEnvHandler, androidUriFileHandler);

    auto* openGlContext = new OpenGLContext();
    if (!openGlContext->InitContext(0)) {
        delete openGlContext;
        delete fileHandler;
        delete jniEnvHandler;
        env->DeleteGlobalRef(androidUriFileHandler);
        androidUriFileHandler = nullptr;
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Nintendo DS EGL initialization failed");
        return;
    }

    MelonDSAndroid::openGlContext = openGlContext;
    MelonDSAndroid::fileHandler = fileHandler;
}

JNIEXPORT jlong JNICALL
Java_me_magnum_melonds_MelonDSAndroidInterface_getEmulatorGlContext(JNIEnv* env, jobject thiz)
{
    return (jlong) MelonDSAndroid::openGlContext->GetContext();
}

JNIEXPORT void JNICALL
Java_me_magnum_melonds_MelonDSAndroidInterface_cleanup(JNIEnv* env, jobject thiz)
{
    env->DeleteGlobalRef(androidUriFileHandler);
    androidUriFileHandler = nullptr;
    vm = nullptr;

    MelonDSAndroid::openGlContext->DeInit();

    delete MelonDSAndroid::openGlContext;
    delete fileHandler;
    delete jniEnvHandler;

    MelonDSAndroid::openGlContext = nullptr;
}
}