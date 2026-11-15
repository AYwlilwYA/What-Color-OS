#include <jni.h>
#include <pthread.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <android/log.h>

#define LOG_TAG "lg-native-probe"
#define LOGI(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define OUT_MAX 512

static JavaVM* gVm = nullptr;
static jobject gView = nullptr;
static int gRadius = 60;

static void append(char* out, const char* s) {
    size_t len = strlen(out);
    if (len >= OUT_MAX - 1) return;
    strncat(out + len, s, OUT_MAX - 1 - len);
}

static jobject step(JNIEnv* env, jobject obj, const char* name, const char* sig, char* out) {
    out[0] = '\0';
    if (env->ExceptionCheck()) env->ExceptionClear();
    jclass cls = env->GetObjectClass(obj);
    if (cls == nullptr) {
        append(out, "NO_CLASS");
        return nullptr;
    }
    jfieldID fid = env->GetFieldID(cls, name, sig);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        append(out, "EXCEPTION");
        return nullptr;
    }
    if (fid == nullptr) {
        append(out, "NULL_FIELD");
        return nullptr;
    }
    jobject v = env->GetObjectField(obj, fid);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        append(out, "GET_EXCEPTION");
        return nullptr;
    }
    append(out, v ? "non-null" : "null");
    return v;
}

static void* threadMain(void*) {
    JNIEnv* env = nullptr;
    if (gVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    char out[OUT_MAX];
    char res[64];
    out[0] = '\0';

    jobject ai = step(env, gView, "mAttachInfo", "Landroid/view/View$AttachInfo;", res);
    append(out, "mAttachInfo=");
    append(out, res);
    append(out, ";");
    jobject vr = ai ? step(env, ai, "mViewRootImpl", "Landroid/view/ViewRootImpl;", res) : nullptr;
    append(out, "mViewRootImpl=");
    append(out, res);
    append(out, ";");
    jobject sc = vr ? step(env, vr, "mSurfaceControl", "Landroid/view/SurfaceControl;", res) : nullptr;
    append(out, "mSurfaceControl=");
    append(out, res);
    append(out, ";");

    if (sc != nullptr) {
        jclass scCls = env->FindClass("android/view/SurfaceControl");
        jclass txCls = env->FindClass("android/view/SurfaceControl$Transaction");
        if (scCls == nullptr || txCls == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            append(out, "class=FIND_FAIL");
        } else {
            jmethodID ctor = env->GetMethodID(txCls, "<init>", "()V");
            jmethodID setBlur = env->GetMethodID(txCls, "setBackgroundBlurRadius",
                                                "(Landroid/view/SurfaceControl;I)Landroid/view/SurfaceControl$Transaction;");
            jmethodID apply = env->GetMethodID(txCls, "apply", "()V");
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                append(out, "method=EXCEPTION");
            } else if (ctor == nullptr || setBlur == nullptr || apply == nullptr) {
                append(out, "method=NULL");
            } else {
                jobject tx = env->NewObject(txCls, ctor);
                env->CallObjectMethod(tx, setBlur, sc, (jint) gRadius);
                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                    append(out, "setBlur=EXCEPTION;");
                } else {
                    append(out, "setBlur=ok;");
                }
                env->CallVoidMethod(tx, apply);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    append(out, "apply=EXCEPTION;");
                } else {
                    append(out, "apply=ok;");
                }
            }
        }
    }

    if (env->ExceptionCheck()) env->ExceptionClear();
    LOGI("native-thread result: %s", out);
    gVm->DetachCurrentThread();
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_coloros16_liquidglass_hook_LgNativeProbe_probe(JNIEnv* env, jclass, jobject view, jint radius) {
    char out[OUT_MAX];
    out[0] = '\0';
    gRadius = radius;
    if (env->GetJavaVM(&gVm) == JNI_OK) {
        if (gView != nullptr) env->DeleteGlobalRef(gView);
        gView = env->NewGlobalRef(view);
        pthread_t t;
        if (pthread_create(&t, nullptr, threadMain, nullptr) == 0) {
            pthread_join(t, nullptr);
            append(out, "thread done");
        } else {
            append(out, "thread FAILED");
        }
    } else {
        append(out, "no vm");
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    return env->NewStringUTF(out);
}

static void* dumpThread(void*) {
    JNIEnv* env = nullptr;
    if (gVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();
    jclass cls = env->FindClass("android/view/ViewRootImpl");
    if (cls == nullptr || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGI("dump: FindClass ViewRootImpl FAILED");
        gVm->DetachCurrentThread();
        return nullptr;
    }
    jclass classCls = env->FindClass("java/lang/Class");
    jclass fieldCls = env->FindClass("java/lang/reflect/Field");
    jclass modCls = env->FindClass("java/lang/reflect/Modifier");
    jmethodID getDeclaredFields = env->GetMethodID(classCls, "getDeclaredFields", "()[Ljava/lang/reflect/Field;");
    jmethodID getName = env->GetMethodID(fieldCls, "getName", "()Ljava/lang/String;");
    jmethodID getModifiers = env->GetMethodID(fieldCls, "getModifiers", "()I");
    jmethodID getType = env->GetMethodID(fieldCls, "getType", "()Ljava/lang/Class;");
    jmethodID isStatic = env->GetStaticMethodID(modCls, "isStatic", "(I)Z");
    if (env->ExceptionCheck()) { env->ExceptionClear(); LOGI("dump: method lookup FAILED"); gVm->DetachCurrentThread(); return nullptr; }
    jobjectArray arr = (jobjectArray) env->CallObjectMethod(cls, getDeclaredFields);
    if (env->ExceptionCheck()) { env->ExceptionClear(); LOGI("dump: getDeclaredFields FAILED"); gVm->DetachCurrentThread(); return nullptr; }
    jsize n = env->GetArrayLength(arr);
    int printed = 0;
    for (jsize i = 0; i < n && printed < 60; i++) {
        jobject f = env->GetObjectArrayElement(arr, i);
        jint mods = env->CallIntMethod(f, getModifiers);
        jboolean st = env->CallStaticBooleanMethod(modCls, isStatic, mods);
        if (!st) continue;
        jstring jn = (jstring) env->CallObjectMethod(f, getName);
        jobject jt = env->CallObjectMethod(f, getType);
        jclass tc = (jclass) jt;
        jstring jtn = (jstring) env->CallObjectMethod(tc, env->GetMethodID(classCls, "getName", "()Ljava/lang/String;"));
        const char* fn = env->GetStringUTFChars(jn, nullptr);
        const char* tn = env->GetStringUTFChars(jtn, nullptr);
        LOGI("dump static: %s : %s", fn, tn);
        env->ReleaseStringUTFChars(jn, fn);
        env->ReleaseStringUTFChars(jtn, tn);
        printed++;
    }
    LOGI("dump total static fields printed=%d of %d", printed, (int) n);
    if (env->ExceptionCheck()) env->ExceptionClear();
    gVm->DetachCurrentThread();
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_coloros16_liquidglass_hook_LgNativeProbe_dumpStatics(JNIEnv* env, jclass, jstring className) {
    const char* cn = env->GetStringUTFChars(className, nullptr);
    LOGI("dump request: %s", cn);
    env->ReleaseStringUTFChars(className, cn);
    pthread_t t;
    if (pthread_create(&t, nullptr, dumpThread, nullptr) == 0) pthread_join(t, nullptr);
    if (env->ExceptionCheck()) env->ExceptionClear();
    return env->NewStringUTF("dumped");
}

static int gBlurRadius = 60;

static void* blurThread(void*) {
    JNIEnv* env = nullptr;
    if (gVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();
    char out[512];
    out[0] = '\0';

    jclass wmg = env->FindClass("android/view/WindowManagerGlobal");
    if (wmg == nullptr || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGI("blur: FindClass WindowManagerGlobal FAILED");
        gVm->DetachCurrentThread();
        return nullptr;
    }
    jmethodID getInstance = env->GetStaticMethodID(wmg, "getInstance", "()Landroid/view/WindowManagerGlobal;");
    if (getInstance == nullptr || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGI("blur: getInstance FAILED");
        gVm->DetachCurrentThread();
        return nullptr;
    }
    jobject inst = env->CallStaticObjectMethod(wmg, getInstance);
    if (env->ExceptionCheck()) { env->ExceptionClear(); LOGI("blur: call getInstance FAILED"); gVm->DetachCurrentThread(); return nullptr; }

    jfieldID rootsFid = env->GetFieldID(wmg, "mRoots", "Ljava/util/ArrayList;");
    if (rootsFid == nullptr || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGI("blur: field mRoots FAILED");
        gVm->DetachCurrentThread();
        return nullptr;
    }
    jobject roots = env->GetObjectField(inst, rootsFid);
    jclass alCls = env->FindClass("java/util/ArrayList");
    jmethodID size = env->GetMethodID(alCls, "size", "()I");
    jmethodID get = env->GetMethodID(alCls, "get", "(I)Ljava/lang/Object;");
    jint n = env->CallIntMethod(roots, size);
    LOGI("blur: mRoots size=%d", (int) n);
    if (n <= 0) { gVm->DetachCurrentThread(); return nullptr; }

    jclass scCls = env->FindClass("android/view/SurfaceControl");
    jmethodID isValidM = env->GetMethodID(scCls, "isValid", "()Z");
    jmethodID getNameM = env->GetMethodID(scCls, "getName", "()Ljava/lang/String;");
    jobject validSc[16];
    int nValid = 0;
    for (jint i = 0; i < n && nValid < 16; i++) {
        jobject r = env->CallObjectMethod(roots, get, i);
        if (env->ExceptionCheck()) { env->ExceptionClear(); continue; }
        if (r == nullptr) continue;
        jobject cand = step(env, r, "mSurfaceControl", "Landroid/view/SurfaceControl;", out);
        jboolean ok = JNI_FALSE;
        const char* nm = "n/a";
        jstring jn = nullptr;
        if (cand != nullptr && isValidM != nullptr && !env->ExceptionCheck()) {
            ok = env->CallBooleanMethod(cand, isValidM);
            if (env->ExceptionCheck()) { env->ExceptionClear(); ok = JNI_FALSE; }
            if (ok && getNameM != nullptr) {
                jn = (jstring) env->CallObjectMethod(cand, getNameM);
                if (jn != nullptr) nm = env->GetStringUTFChars(jn, nullptr);
            }
        }
        LOGI("blur: root[%d] sc=%s valid=%d name=%s", (int) i, out, (int) ok, nm);
        if (jn != nullptr) env->ReleaseStringUTFChars(jn, nm);
        if (ok) { validSc[nValid] = env->NewLocalRef(cand); nValid++; }
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    LOGI("blur: valid=%d of %d", nValid, (int) n);
    if (nValid == 0) { LOGI("blur: no valid SurfaceControl in %d roots", (int) n); gVm->DetachCurrentThread(); return nullptr; }
    {
        jclass txCls = env->FindClass("android/view/SurfaceControl$Transaction");
        jmethodID ctor = env->GetMethodID(txCls, "<init>", "()V");
        jmethodID setBlur = env->GetMethodID(txCls, "setBackgroundBlurRadius",
                                            "(Landroid/view/SurfaceControl;I)Landroid/view/SurfaceControl$Transaction;");
        jmethodID apply = env->GetMethodID(txCls, "apply", "()V");
        jmethodID setRegions = env->GetMethodID(txCls, "setBlurRegions",
            "(Landroid/view/SurfaceControl;[[F)Landroid/view/SurfaceControl$Transaction;");
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            LOGI("blur: method lookup FAILED");
        } else if (ctor && setBlur && apply) {
            jobject tx = env->NewObject(txCls, ctor);
            jclass farrCls = env->FindClass("[F");
            for (int k = 0; k < nValid; k++) {
                env->CallObjectMethod(tx, setBlur, validSc[k], (jint) gBlurRadius);
                if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); LOGI("blur: setBlur[%d] EXCEPTION", k); }
                else LOGI("blur: setBlur[%d] ok radius=%d", k, gBlurRadius);
                if (setRegions != nullptr && farrCls != nullptr && !env->ExceptionCheck()) {
                    jobjectArray outer = env->NewObjectArray(1, farrCls, nullptr);
                    jfloat buf[32];
                    memset(buf, 0, sizeof(buf));
                    buf[0] = (jfloat) gBlurRadius;
                    buf[1] = 1.0f;
                    buf[4] = 5000.0f;
                    buf[5] = 5000.0f;
                    buf[6] = 40.0f; buf[7] = 40.0f; buf[8] = 40.0f; buf[9] = 40.0f;
                    jfloatArray inner = env->NewFloatArray(32);
                    env->SetFloatArrayRegion(inner, 0, 32, buf);
                    env->SetObjectArrayElement(outer, 0, inner);
                    env->CallObjectMethod(tx, setRegions, validSc[k], outer);
                    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); LOGI("blur: setBlurRegions[%d] EXCEPTION", k); }
                    else LOGI("blur: setBlurRegions[%d] ok", k);
                }
            }
            env->CallVoidMethod(tx, apply);
            if (env->ExceptionCheck()) { env->ExceptionClear(); LOGI("blur: apply EXCEPTION"); }
            else LOGI("blur: apply ok layers=%d", nValid);
        } else {
            if (env->ExceptionCheck()) env->ExceptionClear();
            LOGI("blur: ctor/setBlur/apply NULL");
        }
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    gVm->DetachCurrentThread();
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_coloros16_liquidglass_hook_LgNativeProbe_blurFirstWindow(JNIEnv* env, jclass, jint radius) {
    gBlurRadius = radius;
    if (env->GetJavaVM(&gVm) != JNI_OK) return env->NewStringUTF("no vm");
    pthread_t t;
    if (pthread_create(&t, nullptr, blurThread, nullptr) != 0) return env->NewStringUTF("thread failed");
    pthread_join(t, nullptr);
    if (env->ExceptionCheck()) env->ExceptionClear();
    return env->NewStringUTF("done");
}
