/*
 * Starts OpenJDK in a separate process. Keeping the launcher in the APK's native
 * library directory lets Android execute it under the normal app sandbox.
 */
#include <dlfcn.h>
#include <jni.h>
#include <limits.h>
#include <malloc.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/prctl.h>
#include <unistd.h>

typedef jint (*create_vm_fn)(JavaVM **, void **, void *);

static void stage(const char *name) {
    fprintf(stderr, "NFD_RUNTIME %s\n", name);
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fputs("usage: launcher <runtime-image> <classpath> [daemon arguments]\n", stderr);
        return 2;
    }
    pid_t parent = getppid();
    if (parent == 1 || prctl(PR_SET_PDEATHSIG, SIGTERM) != 0 || getppid() != parent) {
        return 2;
    }
#if defined(__aarch64__)
    // HotSpot passes untagged addresses back to the allocator. Disable heap
    // tagging only in this launcher process, before loading any JVM libraries.
    // Older Android versions ignore the option and do not tag heap pointers.
    if (!mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, M_HEAP_TAGGING_LEVEL_NONE)) {
        stage("heap-tagging-unavailable");
    }
#endif
    char vm_path[PATH_MAX];
    char home[PATH_MAX + 16];
    char temp[PATH_MAX + 20];
    const char *tmp = getenv("TMPDIR");
    if (snprintf(vm_path, sizeof(vm_path), "%s/lib/server/libjvm.so", argv[1]) >= (int)sizeof(vm_path) ||
        snprintf(home, sizeof(home), "-Djava.home=%s", argv[1]) >= (int)sizeof(home) ||
        snprintf(temp, sizeof(temp), "-Djava.io.tmpdir=%s", tmp == NULL ? "/data/local/tmp" : tmp) >= (int)sizeof(temp)) {
        return 2;
    }
    stage("load");
    void *library = dlopen(vm_path, RTLD_NOW | RTLD_GLOBAL);
    if (library == NULL) {
        stage("load-failed");
        fprintf(stderr, "OpenJDK load failed: %s\n", dlerror());
        return 3;
    }
    create_vm_fn create_vm = (create_vm_fn)dlsym(library, "JNI_CreateJavaVM");
    if (create_vm == NULL) {
        stage("load-failed");
        return 3;
    }

    char *classpath;
    if (asprintf(&classpath, "-Djava.class.path=%s", argv[2]) < 0) {
        return 2;
    }
    JavaVMOption options[] = {
        {home, NULL},
        {temp, NULL},
        {classpath, NULL},
        {"-XX:+UseSerialGC", NULL},
        {"-XX:-UsePerfData", NULL},
        {"-XX:+DisableAttachMechanism", NULL},
        {"-XX:-CreateCoredumpOnCrash", NULL},
        {"-XX:+ErrorFileToStderr", NULL},
        {"-Xms16m", NULL},
        {"-Xmx256m", NULL},
        {"-Djava.awt.headless=true", NULL},
        // The linked JRE omits jspawnhelper. The downloader is a standalone executable.
        {"-Djdk.lang.Process.launchMechanism=fork", NULL},
        {"-Dvertx.disableDnsResolver=true", NULL},
        {"-Dvertx.disableFileCPResolving=true", NULL},
#if defined(__aarch64__)
        // Some Android app sandboxes block the prctl calls HotSpot uses to configure SVE.
        {"-XX:UseSVE=0", NULL},
#endif
        {getenv("NFD_JVM_COMPAT") != NULL ? "-Xint" : "-Xmixed", NULL},
    };
    JavaVMInitArgs vm_args = {
        .version = JNI_VERSION_1_6,
        .nOptions = (jint)(sizeof(options) / sizeof(options[0])),
        .options = options,
        .ignoreUnrecognized = JNI_FALSE,
    };
    JavaVM *vm;
    JNIEnv *env;
    stage("init");
    jint result = create_vm(&vm, (void **)&env, &vm_args);
    free(classpath);
    if (result != JNI_OK) {
        stage("init-failed");
        return 4;
    }
    stage("java-ready");
    jclass main_class = (*env)->FindClass(env, "cn/qaiu/nfd/daemon/DaemonMain");
    jclass string_class = main_class == NULL ? NULL : (*env)->FindClass(env, "java/lang/String");
    jmethodID main_method = string_class == NULL ? NULL
        : (*env)->GetStaticMethodID(env, main_class, "main", "([Ljava/lang/String;)V");
    jobjectArray arguments = main_method == NULL ? NULL
        : (*env)->NewObjectArray(env, argc - 3, string_class, NULL);
    if (arguments != NULL) {
        for (int i = 3; i < argc && !(*env)->ExceptionCheck(env); i++) {
            jstring value = (*env)->NewStringUTF(env, argv[i]);
            if (value != NULL) {
                (*env)->SetObjectArrayElement(env, arguments, i - 3, value);
                (*env)->DeleteLocalRef(env, value);
            }
        }
        if (!(*env)->ExceptionCheck(env)) {
            (*env)->CallStaticVoidMethod(env, main_class, main_method, arguments);
        }
    }
    if ((*env)->ExceptionCheck(env)) {
        stage("java-failed");
        (*env)->ExceptionDescribe(env);
        return 5;
    }
    if (arguments == NULL) {
        return 5;
    }
    return (*vm)->DestroyJavaVM(vm) == JNI_OK ? 0 : 6;
}
