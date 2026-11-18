#include <jni.h>
#include <android/bitmap.h>
#include "webp/encode.h"
#include "webp/mux.h"

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_figmaker_app_WebpEncoder_encode(JNIEnv* env, jobject, jobjectArray bitmaps,
                                         jintArray durations, jint quality, jboolean lossless) {
    jsize n = env->GetArrayLength(bitmaps);
    if (n <= 0) return nullptr;

    jint* dur = env->GetIntArrayElements(durations, nullptr);

    AndroidBitmapInfo info;
    jobject first = env->GetObjectArrayElement(bitmaps, 0);
    if (AndroidBitmap_getInfo(env, first, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        env->DeleteLocalRef(first);
        env->ReleaseIntArrayElements(durations, dur, JNI_ABORT);
        return nullptr;
    }
    env->DeleteLocalRef(first);
    const int w = (int) info.width;
    const int h = (int) info.height;

    WebPAnimEncoderOptions opts;
    if (!WebPAnimEncoderOptionsInit(&opts)) {
        env->ReleaseIntArrayElements(durations, dur, JNI_ABORT);
        return nullptr;
    }
    opts.anim_params.loop_count = 0;  // loop infinito
    opts.allow_mixed = 0;

    WebPAnimEncoder* enc = WebPAnimEncoderNew(w, h, &opts);
    WebPConfig config;
    WebPConfigInit(&config);
    config.lossless = lossless ? 1 : 0;
    config.quality = (float) quality;
    config.method = 4;
    config.exact = lossless ? 1 : 0;

    bool ok = enc != nullptr && WebPValidateConfig(&config);
    int timestamp = 0;

    for (jsize i = 0; ok && i < n; i++) {
        jobject bmp = env->GetObjectArrayElement(bitmaps, i);
        AndroidBitmapInfo bi;
        void* pixels = nullptr;
        if (AndroidBitmap_getInfo(env, bmp, &bi) != ANDROID_BITMAP_RESULT_SUCCESS ||
            (int) bi.width != w || (int) bi.height != h ||
            AndroidBitmap_lockPixels(env, bmp, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
            env->DeleteLocalRef(bmp);
            ok = false;
            break;
        }
        WebPPicture pic;
        WebPPictureInit(&pic);
        pic.width = w;
        pic.height = h;
        pic.use_argb = 1;
        if (!WebPPictureImportRGBA(&pic, (const uint8_t*) pixels, (int) bi.stride)) {
            ok = false;
        } else if (!WebPAnimEncoderAdd(enc, &pic, timestamp, &config)) {
            ok = false;
        }
        WebPPictureFree(&pic);
        AndroidBitmap_unlockPixels(env, bmp);
        env->DeleteLocalRef(bmp);
        timestamp += dur[i];
    }

    jbyteArray result = nullptr;
    if (ok) {
        ok = WebPAnimEncoderAdd(enc, nullptr, timestamp, nullptr) != 0;
    }
    if (ok) {
        WebPData data;
        WebPDataInit(&data);
        if (WebPAnimEncoderAssemble(enc, &data)) {
            result = env->NewByteArray((jsize) data.size);
            env->SetByteArrayRegion(result, 0, (jsize) data.size, (const jbyte*) data.bytes);
        }
        WebPDataClear(&data);
    }
    if (enc) WebPAnimEncoderDelete(enc);
    env->ReleaseIntArrayElements(durations, dur, JNI_ABORT);
    return result;
}
