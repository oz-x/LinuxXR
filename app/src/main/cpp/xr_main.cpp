// LinuxXR immersive mode: OpenXR stereo renderer for the Linux desktops.
//
// Each X display is drawn as a curved screen on a cylinder around the user, rendered
// separately for each eye (true stereo 3D, head-tracked). Controllers point at the screens:
// trigger = left click, grip = right click, thumbstick = scroll, A/X = toggle passthrough,
// B/Y = recenter. Framebuffer uploads happen in Kotlin (ImmersiveActivity.onFrame) on this
// thread, with this EGL context current.

#include <jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#define LOG_TAG "LinuxXR-XR"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::atomic<bool> g_exitRequested{false};

// ---------------------------------------------------------------------------------------------
// Math (column-major 4x4, OpenGL conventions)

struct Vec3 { float x, y, z; };
Vec3 operator+(Vec3 a, Vec3 b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
Vec3 operator-(Vec3 a, Vec3 b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
Vec3 operator*(Vec3 a, float s) { return {a.x * s, a.y * s, a.z * s}; }

struct Mat4 { float m[16]; };

Mat4 identity() { Mat4 r{}; r.m[0] = r.m[5] = r.m[10] = r.m[15] = 1; return r; }

Mat4 mul(const Mat4& a, const Mat4& b) {
    Mat4 r{};
    for (int c = 0; c < 4; ++c)
        for (int row = 0; row < 4; ++row) {
            float s = 0;
            for (int k = 0; k < 4; ++k) s += a.m[k * 4 + row] * b.m[c * 4 + k];
            r.m[c * 4 + row] = s;
        }
    return r;
}

Vec3 rotate(const XrQuaternionf& q, Vec3 v) {
    // v' = v + 2w(u x v) + 2u x (u x v)
    Vec3 u{q.x, q.y, q.z};
    auto cross = [](Vec3 a, Vec3 b) { return Vec3{a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; };
    Vec3 t = cross(u, v) * 2.f;
    return v + t * q.w + cross(u, t);
}

// Rigid transform matrix from an OpenXR pose.
Mat4 poseMatrix(const XrPosef& p) {
    const auto& q = p.orientation;
    Mat4 r = identity();
    r.m[0] = 1 - 2 * (q.y * q.y + q.z * q.z);
    r.m[1] = 2 * (q.x * q.y + q.z * q.w);
    r.m[2] = 2 * (q.x * q.z - q.y * q.w);
    r.m[4] = 2 * (q.x * q.y - q.z * q.w);
    r.m[5] = 1 - 2 * (q.x * q.x + q.z * q.z);
    r.m[6] = 2 * (q.y * q.z + q.x * q.w);
    r.m[8] = 2 * (q.x * q.z + q.y * q.w);
    r.m[9] = 2 * (q.y * q.z - q.x * q.w);
    r.m[10] = 1 - 2 * (q.x * q.x + q.y * q.y);
    r.m[12] = p.position.x; r.m[13] = p.position.y; r.m[14] = p.position.z;
    return r;
}

// Inverse of a rigid transform (rotation + translation).
Mat4 rigidInverse(const Mat4& a) {
    Mat4 r = identity();
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j) r.m[j * 4 + i] = a.m[i * 4 + j];
    for (int i = 0; i < 3; ++i)
        r.m[12 + i] = -(r.m[i] * a.m[12] + r.m[4 + i] * a.m[13] + r.m[8 + i] * a.m[14]);
    return r;
}

Vec3 transformPoint(const Mat4& a, Vec3 p) {
    return {a.m[0] * p.x + a.m[4] * p.y + a.m[8] * p.z + a.m[12],
            a.m[1] * p.x + a.m[5] * p.y + a.m[9] * p.z + a.m[13],
            a.m[2] * p.x + a.m[6] * p.y + a.m[10] * p.z + a.m[14]};
}

Vec3 transformDir(const Mat4& a, Vec3 d) {
    return {a.m[0] * d.x + a.m[4] * d.y + a.m[8] * d.z,
            a.m[1] * d.x + a.m[5] * d.y + a.m[9] * d.z,
            a.m[2] * d.x + a.m[6] * d.y + a.m[10] * d.z};
}

Mat4 projection(const XrFovf& fov, float nearZ, float farZ) {
    const float l = std::tan(fov.angleLeft), r = std::tan(fov.angleRight);
    const float d = std::tan(fov.angleDown), u = std::tan(fov.angleUp);
    const float w = r - l, h = u - d;
    Mat4 p{};
    p.m[0] = 2 / w;
    p.m[5] = 2 / h;
    p.m[8] = (r + l) / w;
    p.m[9] = (u + d) / h;
    p.m[10] = -(farZ + nearZ) / (farZ - nearZ);
    p.m[11] = -1;
    p.m[14] = -2 * farZ * nearZ / (farZ - nearZ);
    return p;
}

Mat4 yawTranslation(float yaw, Vec3 t) {
    Mat4 r = identity();
    r.m[0] = std::cos(yaw); r.m[2] = -std::sin(yaw);
    r.m[8] = std::sin(yaw); r.m[10] = std::cos(yaw);
    r.m[12] = t.x; r.m[13] = t.y; r.m[14] = t.z;
    return r;
}

// ---------------------------------------------------------------------------------------------
// GL helpers

GLuint compile(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetShaderInfoLog(s, sizeof log, nullptr, log);
        LOGE("shader: %s", log);
    }
    return s;
}

GLuint program(const char* vs, const char* fs) {
    GLuint p = glCreateProgram();
    glAttachShader(p, compile(GL_VERTEX_SHADER, vs));
    glAttachShader(p, compile(GL_FRAGMENT_SHADER, fs));
    glLinkProgram(p);
    return p;
}

const char* kScreenVs = R"(#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
uniform mat4 uMvp;
out vec2 vUv;
void main() { vUv = aUv; gl_Position = uMvp * vec4(aPos, 1.0); })";

const char* kScreenFs = R"(#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTex;
uniform vec4 uTint;      // a > 0: solid placeholder colour instead of the texture
out vec4 color;
void main() {
    vec3 c = uTint.a > 0.0 ? uTint.rgb : texture(uTex, vUv).rgb;
    // Thin bezel so screens read as objects in space.
    float e = min(min(vUv.x, 1.0 - vUv.x), min(vUv.y, 1.0 - vUv.y));
    color = vec4(mix(vec3(0.02), c, smoothstep(0.0, 0.003, e)), 1.0);
})";

const char* kColorVs = R"(#version 300 es
layout(location = 0) in vec3 aPos;
uniform mat4 uMvp;
uniform float uPointSize;
out vec3 vPos;
void main() { vPos = aPos; gl_PointSize = uPointSize; gl_Position = uMvp * vec4(aPos, 1.0); })";

const char* kColorFs = R"(#version 300 es
precision mediump float;
in vec3 vPos;
uniform vec4 uColor;
uniform vec3 uFadeCenter;
uniform float uFadeRadius;  // > 0: fade out with horizontal distance (floor grid)
out vec4 color;
void main() {
    float a = uColor.a;
    if (uFadeRadius > 0.0) a *= 1.0 - smoothstep(0.0, uFadeRadius, distance(vPos.xz, uFadeCenter.xz));
    color = vec4(uColor.rgb, a);
})";

// ---------------------------------------------------------------------------------------------

constexpr float kRadius = 1.8f;          // metres from the user to the screens
constexpr float kPixelsPerMetre = 1200;  // 1920 px wide desktop = 1.6 m
constexpr float kGap = 0.06f;            // radians between screens
constexpr int kSegments = 64;
constexpr int kMaxDisplays = 4;

struct ScreenGeom {
    float theta0 = 0, theta1 = 0;  // angular span (0 = straight ahead, positive to the right)
    float top = 0, bottom = 0;     // heights in layout space
    int width = 0, height = 0;     // framebuffer pixels
    GLuint texture = 0;
};

struct Hand {
    XrPath path = XR_NULL_PATH;
    XrSpace aimSpace = XR_NULL_HANDLE;
    bool active = false;
    Vec3 origin{}, dir{};
    bool hit = false;
    Vec3 hitPoint{};
    int hitDisplay = -1;
    float u = 0, v = 0;
    bool trigger = false, grip = false;
    bool primaryPrev = false, secondaryPrev = false;
};

#define XR_CHECK(call)                                                        \
    do {                                                                      \
        XrResult _r = (call);                                                 \
        if (XR_FAILED(_r)) { LOGE("%s failed: %d", #call, _r); return false; } \
    } while (0)

class App {
public:
    App(JNIEnv* env, jobject activity) : env_(env), activity_(activity) {
        jclass cls = env->GetObjectClass(activity);
        onFrame_ = env->GetMethodID(cls, "onFrame", "()[I");
        onPointer_ = env->GetMethodID(cls, "onPointer", "(IFFI)V");
        onScroll_ = env->GetMethodID(cls, "onScroll", "(IFFZ)V");
    }

    bool run(JavaVM* vm) {
        if (!initInstance(vm) || !initEgl() || !initSession() || !initSwapchains() || !initActions()) return false;
        initPassthrough();
        initGl();
        loop();
        return true;
    }

    ~App() {
        if (passthroughLayer_) destroyPassthroughLayer_(passthroughLayer_);
        if (passthrough_) destroyPassthrough_(passthrough_);
        for (auto& sc : swapchains_) if (sc.handle) xrDestroySwapchain(sc.handle);
        if (session_) xrDestroySession(session_);
        if (instance_) xrDestroyInstance(instance_);
        if (eglDisplay_ != EGL_NO_DISPLAY) {
            eglMakeCurrent(eglDisplay_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (eglContext_ != EGL_NO_CONTEXT) eglDestroyContext(eglDisplay_, eglContext_);
            if (eglSurface_ != EGL_NO_SURFACE) eglDestroySurface(eglDisplay_, eglSurface_);
            eglTerminate(eglDisplay_);
        }
    }

private:
    struct Swapchain {
        XrSwapchain handle = XR_NULL_HANDLE;
        int32_t width = 0, height = 0;
        std::vector<XrSwapchainImageOpenGLESKHR> images;
        std::vector<GLuint> fbos;
    };

    JNIEnv* env_;
    jobject activity_;
    jmethodID onFrame_, onPointer_, onScroll_;

    XrInstance instance_ = XR_NULL_HANDLE;
    XrSystemId system_ = XR_NULL_SYSTEM_ID;
    XrSession session_ = XR_NULL_HANDLE;
    XrSpace local_ = XR_NULL_HANDLE, view_ = XR_NULL_HANDLE;
    XrSessionState state_ = XR_SESSION_STATE_UNKNOWN;
    bool running_ = false;
    Swapchain swapchains_[2];
    std::vector<XrViewConfigurationView> viewConfigs_;

    EGLDisplay eglDisplay_ = EGL_NO_DISPLAY;
    EGLConfig eglConfig_ = nullptr;
    EGLContext eglContext_ = EGL_NO_CONTEXT;
    EGLSurface eglSurface_ = EGL_NO_SURFACE;

    XrActionSet actionSet_ = XR_NULL_HANDLE;
    XrAction aimAction_ = XR_NULL_HANDLE, triggerAction_ = XR_NULL_HANDLE, gripAction_ = XR_NULL_HANDLE,
             stickAction_ = XR_NULL_HANDLE, primaryAction_ = XR_NULL_HANDLE, secondaryAction_ = XR_NULL_HANDLE,
             hapticAction_ = XR_NULL_HANDLE;
    Hand hands_[2];
    int activeHand_ = 1;

    bool hasPassthrough_ = false;
    bool passthroughOn_ = false;
    XrPassthroughFB passthrough_ = XR_NULL_HANDLE;
    XrPassthroughLayerFB passthroughLayer_ = XR_NULL_HANDLE;
    PFN_xrCreatePassthroughFB createPassthrough_ = nullptr;
    PFN_xrDestroyPassthroughFB destroyPassthrough_ = nullptr;
    PFN_xrCreatePassthroughLayerFB createPassthroughLayer_ = nullptr;
    PFN_xrDestroyPassthroughLayerFB destroyPassthroughLayer_ = nullptr;
    PFN_xrPassthroughLayerPauseFB pauseLayer_ = nullptr;
    PFN_xrPassthroughLayerResumeFB resumeLayer_ = nullptr;

    GLuint screenProg_ = 0, colorProg_ = 0;
    GLuint screenVbo_ = 0, lineVbo_ = 0, gridVbo_ = 0;
    GLsizei gridVertexCount_ = 0;

    ScreenGeom screens_[kMaxDisplays];
    int screenCount_ = 0;
    bool layoutValid_ = false;
    Mat4 layout_ = identity(), layoutInv_ = identity();
    float floorY_ = -1.6f;

    int lastPointerDisplay_ = -1, lastPx_ = -1, lastPy_ = -1, lastButtons_ = -1;
    std::chrono::steady_clock::time_point lastScroll_{};

    // --- setup -------------------------------------------------------------------------------

    bool initInstance(JavaVM* vm) {
        PFN_xrInitializeLoaderKHR initLoader = nullptr;
        xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR", (PFN_xrVoidFunction*)&initLoader);
        if (initLoader) {
            XrLoaderInitInfoAndroidKHR li{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
            li.applicationVM = vm;
            li.applicationContext = activity_;
            initLoader((XrLoaderInitInfoBaseHeaderKHR*)&li);
        }

        uint32_t n = 0;
        xrEnumerateInstanceExtensionProperties(nullptr, 0, &n, nullptr);
        std::vector<XrExtensionProperties> exts(n, {XR_TYPE_EXTENSION_PROPERTIES});
        xrEnumerateInstanceExtensionProperties(nullptr, n, &n, exts.data());
        auto has = [&](const char* name) {
            for (auto& e : exts) if (!strcmp(e.extensionName, name)) return true;
            return false;
        };
        std::vector<const char*> enabled{XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME, XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME};
        hasPassthrough_ = has(XR_FB_PASSTHROUGH_EXTENSION_NAME);
        if (hasPassthrough_) enabled.push_back(XR_FB_PASSTHROUGH_EXTENSION_NAME);

        XrInstanceCreateInfoAndroidKHR android{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
        android.applicationVM = vm;
        android.applicationActivity = activity_;
        XrInstanceCreateInfo ci{XR_TYPE_INSTANCE_CREATE_INFO};
        ci.next = &android;
        strcpy(ci.applicationInfo.applicationName, "LinuxXR");
        ci.applicationInfo.applicationVersion = 1;
        strcpy(ci.applicationInfo.engineName, "LinuxXR");
        ci.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 0);
        ci.enabledExtensionCount = (uint32_t)enabled.size();
        ci.enabledExtensionNames = enabled.data();
        XR_CHECK(xrCreateInstance(&ci, &instance_));

        XrSystemGetInfo si{XR_TYPE_SYSTEM_GET_INFO};
        si.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
        XR_CHECK(xrGetSystem(instance_, &si, &system_));
        return true;
    }

    bool initEgl() {
        PFN_xrGetOpenGLESGraphicsRequirementsKHR getReq = nullptr;
        XR_CHECK(xrGetInstanceProcAddr(instance_, "xrGetOpenGLESGraphicsRequirementsKHR", (PFN_xrVoidFunction*)&getReq));
        XrGraphicsRequirementsOpenGLESKHR req{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
        XR_CHECK(getReq(instance_, system_, &req));

        eglDisplay_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        eglInitialize(eglDisplay_, nullptr, nullptr);
        const EGLint attribs[] = {EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
                                  EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
        EGLint count = 0;
        if (!eglChooseConfig(eglDisplay_, attribs, &eglConfig_, 1, &count) || count == 0) { LOGE("eglChooseConfig"); return false; }
        const EGLint ctx[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        eglContext_ = eglCreateContext(eglDisplay_, eglConfig_, EGL_NO_CONTEXT, ctx);
        const EGLint pb[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
        eglSurface_ = eglCreatePbufferSurface(eglDisplay_, eglConfig_, pb);
        if (eglContext_ == EGL_NO_CONTEXT || !eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_)) {
            LOGE("EGL context");
            return false;
        }
        return true;
    }

    bool initSession() {
        XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
        binding.display = eglDisplay_;
        binding.config = eglConfig_;
        binding.context = eglContext_;
        XrSessionCreateInfo ci{XR_TYPE_SESSION_CREATE_INFO};
        ci.next = &binding;
        ci.systemId = system_;
        XR_CHECK(xrCreateSession(instance_, &ci, &session_));

        XrReferenceSpaceCreateInfo rs{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
        rs.poseInReferenceSpace.orientation.w = 1;
        rs.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
        XR_CHECK(xrCreateReferenceSpace(session_, &rs, &local_));
        rs.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW;
        XR_CHECK(xrCreateReferenceSpace(session_, &rs, &view_));
        return true;
    }

    bool initSwapchains() {
        uint32_t n = 0;
        XR_CHECK(xrEnumerateViewConfigurationViews(instance_, system_, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 0, &n, nullptr));
        viewConfigs_.assign(n, {XR_TYPE_VIEW_CONFIGURATION_VIEW});
        XR_CHECK(xrEnumerateViewConfigurationViews(instance_, system_, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, n, &n, viewConfigs_.data()));
        if (n != 2) { LOGE("expected 2 views, got %u", n); return false; }

        uint32_t fc = 0;
        xrEnumerateSwapchainFormats(session_, 0, &fc, nullptr);
        std::vector<int64_t> formats(fc);
        xrEnumerateSwapchainFormats(session_, fc, &fc, formats.data());
        int64_t format = GL_RGBA8;
        for (auto f : formats) if (f == GL_SRGB8_ALPHA8) format = GL_SRGB8_ALPHA8;

        for (int eye = 0; eye < 2; ++eye) {
            auto& sc = swapchains_[eye];
            sc.width = (int32_t)viewConfigs_[eye].recommendedImageRectWidth;
            sc.height = (int32_t)viewConfigs_[eye].recommendedImageRectHeight;
            XrSwapchainCreateInfo ci{XR_TYPE_SWAPCHAIN_CREATE_INFO};
            ci.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
            ci.format = format;
            ci.sampleCount = 1;
            ci.width = sc.width;
            ci.height = sc.height;
            ci.faceCount = 1;
            ci.arraySize = 1;
            ci.mipCount = 1;
            XR_CHECK(xrCreateSwapchain(session_, &ci, &sc.handle));
            uint32_t ic = 0;
            XR_CHECK(xrEnumerateSwapchainImages(sc.handle, 0, &ic, nullptr));
            sc.images.assign(ic, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
            XR_CHECK(xrEnumerateSwapchainImages(sc.handle, ic, &ic, (XrSwapchainImageBaseHeader*)sc.images.data()));
            sc.fbos.resize(ic);
            glGenFramebuffers((GLsizei)ic, sc.fbos.data());
            for (uint32_t i = 0; i < ic; ++i) {
                glBindFramebuffer(GL_FRAMEBUFFER, sc.fbos[i]);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, sc.images[i].image, 0);
            }
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return true;
    }

    XrPath path(const char* s) {
        XrPath p = XR_NULL_PATH;
        xrStringToPath(instance_, s, &p);
        return p;
    }

    bool createAction(XrAction* out, XrActionType type, const char* name, const char* localized) {
        XrActionCreateInfo ci{XR_TYPE_ACTION_CREATE_INFO};
        ci.actionType = type;
        strcpy(ci.actionName, name);
        strcpy(ci.localizedActionName, localized);
        XrPath subs[2] = {hands_[0].path, hands_[1].path};
        ci.countSubactionPaths = 2;
        ci.subactionPaths = subs;
        XR_CHECK(xrCreateAction(actionSet_, &ci, out));
        return true;
    }

    bool initActions() {
        hands_[0].path = path("/user/hand/left");
        hands_[1].path = path("/user/hand/right");
        XrActionSetCreateInfo asci{XR_TYPE_ACTION_SET_CREATE_INFO};
        strcpy(asci.actionSetName, "desktop");
        strcpy(asci.localizedActionSetName, "Desktop");
        XR_CHECK(xrCreateActionSet(instance_, &asci, &actionSet_));
        if (!createAction(&aimAction_, XR_ACTION_TYPE_POSE_INPUT, "aim", "Aim") ||
            !createAction(&triggerAction_, XR_ACTION_TYPE_FLOAT_INPUT, "click", "Click") ||
            !createAction(&gripAction_, XR_ACTION_TYPE_FLOAT_INPUT, "right_click", "Right click") ||
            !createAction(&stickAction_, XR_ACTION_TYPE_VECTOR2F_INPUT, "scroll", "Scroll") ||
            !createAction(&primaryAction_, XR_ACTION_TYPE_BOOLEAN_INPUT, "toggle_passthrough", "Toggle passthrough") ||
            !createAction(&secondaryAction_, XR_ACTION_TYPE_BOOLEAN_INPUT, "recenter", "Recenter") ||
            !createAction(&hapticAction_, XR_ACTION_TYPE_VIBRATION_OUTPUT, "haptic", "Haptic"))
            return false;

        std::vector<XrActionSuggestedBinding> touch{
            {aimAction_, path("/user/hand/left/input/aim/pose")}, {aimAction_, path("/user/hand/right/input/aim/pose")},
            {triggerAction_, path("/user/hand/left/input/trigger/value")}, {triggerAction_, path("/user/hand/right/input/trigger/value")},
            {gripAction_, path("/user/hand/left/input/squeeze/value")}, {gripAction_, path("/user/hand/right/input/squeeze/value")},
            {stickAction_, path("/user/hand/left/input/thumbstick")}, {stickAction_, path("/user/hand/right/input/thumbstick")},
            {primaryAction_, path("/user/hand/left/input/x/click")}, {primaryAction_, path("/user/hand/right/input/a/click")},
            {secondaryAction_, path("/user/hand/left/input/y/click")}, {secondaryAction_, path("/user/hand/right/input/b/click")},
            {hapticAction_, path("/user/hand/left/output/haptic")}, {hapticAction_, path("/user/hand/right/output/haptic")},
        };
        XrInteractionProfileSuggestedBinding sb{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
        sb.interactionProfile = path("/interaction_profiles/oculus/touch_controller");
        sb.countSuggestedBindings = (uint32_t)touch.size();
        sb.suggestedBindings = touch.data();
        XR_CHECK(xrSuggestInteractionProfileBindings(instance_, &sb));

        std::vector<XrActionSuggestedBinding> simple{
            {aimAction_, path("/user/hand/left/input/aim/pose")}, {aimAction_, path("/user/hand/right/input/aim/pose")},
            {triggerAction_, path("/user/hand/left/input/select/click")}, {triggerAction_, path("/user/hand/right/input/select/click")},
            {secondaryAction_, path("/user/hand/left/input/menu/click")},
            {hapticAction_, path("/user/hand/left/output/haptic")}, {hapticAction_, path("/user/hand/right/output/haptic")},
        };
        sb.interactionProfile = path("/interaction_profiles/khr/simple_controller");
        sb.countSuggestedBindings = (uint32_t)simple.size();
        sb.suggestedBindings = simple.data();
        XR_CHECK(xrSuggestInteractionProfileBindings(instance_, &sb));

        XrSessionActionSetsAttachInfo ai{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
        ai.countActionSets = 1;
        ai.actionSets = &actionSet_;
        XR_CHECK(xrAttachSessionActionSets(session_, &ai));

        for (auto& h : hands_) {
            XrActionSpaceCreateInfo sci{XR_TYPE_ACTION_SPACE_CREATE_INFO};
            sci.action = aimAction_;
            sci.subactionPath = h.path;
            sci.poseInActionSpace.orientation.w = 1;
            XR_CHECK(xrCreateActionSpace(session_, &sci, &h.aimSpace));
        }
        return true;
    }

    void initPassthrough() {
        if (!hasPassthrough_) return;
        xrGetInstanceProcAddr(instance_, "xrCreatePassthroughFB", (PFN_xrVoidFunction*)&createPassthrough_);
        xrGetInstanceProcAddr(instance_, "xrDestroyPassthroughFB", (PFN_xrVoidFunction*)&destroyPassthrough_);
        xrGetInstanceProcAddr(instance_, "xrCreatePassthroughLayerFB", (PFN_xrVoidFunction*)&createPassthroughLayer_);
        xrGetInstanceProcAddr(instance_, "xrDestroyPassthroughLayerFB", (PFN_xrVoidFunction*)&destroyPassthroughLayer_);
        xrGetInstanceProcAddr(instance_, "xrPassthroughLayerPauseFB", (PFN_xrVoidFunction*)&pauseLayer_);
        xrGetInstanceProcAddr(instance_, "xrPassthroughLayerResumeFB", (PFN_xrVoidFunction*)&resumeLayer_);
        XrPassthroughCreateInfoFB pci{XR_TYPE_PASSTHROUGH_CREATE_INFO_FB};
        pci.flags = XR_PASSTHROUGH_IS_RUNNING_AT_CREATION_BIT_FB;
        XrPassthroughLayerCreateInfoFB lci{XR_TYPE_PASSTHROUGH_LAYER_CREATE_INFO_FB};
        if (!createPassthrough_ || XR_FAILED(createPassthrough_(session_, &pci, &passthrough_))) {
            hasPassthrough_ = false;
            return;
        }
        lci.passthrough = passthrough_;
        lci.purpose = XR_PASSTHROUGH_LAYER_PURPOSE_RECONSTRUCTION_FB;
        lci.flags = 0;  // created paused; A/X resumes it
        if (XR_FAILED(createPassthroughLayer_(session_, &lci, &passthroughLayer_))) hasPassthrough_ = false;
    }

    void initGl() {
        screenProg_ = program(kScreenVs, kScreenFs);
        colorProg_ = program(kColorVs, kColorFs);
        glGenBuffers(1, &screenVbo_);
        glGenBuffers(1, &lineVbo_);
        glGenBuffers(1, &gridVbo_);
    }

    // --- per frame ----------------------------------------------------------------------------

    void pollEvents() {
        XrEventDataBuffer ev{XR_TYPE_EVENT_DATA_BUFFER};
        while (xrPollEvent(instance_, &ev) == XR_SUCCESS) {
            if (ev.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                state_ = reinterpret_cast<XrEventDataSessionStateChanged*>(&ev)->state;
                if (state_ == XR_SESSION_STATE_READY) {
                    XrSessionBeginInfo bi{XR_TYPE_SESSION_BEGIN_INFO};
                    bi.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                    running_ = XR_SUCCEEDED(xrBeginSession(session_, &bi));
                } else if (state_ == XR_SESSION_STATE_STOPPING) {
                    xrEndSession(session_);
                    running_ = false;
                } else if (state_ == XR_SESSION_STATE_EXITING || state_ == XR_SESSION_STATE_LOSS_PENDING) {
                    g_exitRequested = true;
                }
            } else if (ev.type == XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING) {
                g_exitRequested = true;
            }
            ev = {XR_TYPE_EVENT_DATA_BUFFER};
        }
    }

    void loop() {
        bool exitSent = false;
        while (true) {
            pollEvents();
            if (g_exitRequested && !exitSent && running_) {
                xrRequestExitSession(session_);
                exitSent = true;
            }
            if (g_exitRequested && (!running_ || state_ == XR_SESSION_STATE_EXITING)) break;
            if (!running_) {
                std::this_thread::sleep_for(std::chrono::milliseconds(20));
                continue;
            }
            frame();
        }
    }

    void frame() {
        XrFrameState fs{XR_TYPE_FRAME_STATE};
        if (XR_FAILED(xrWaitFrame(session_, nullptr, &fs))) return;
        xrBeginFrame(session_, nullptr);

        std::vector<XrCompositionLayerBaseHeader*> layers;
        XrCompositionLayerPassthroughFB ptLayer{XR_TYPE_COMPOSITION_LAYER_PASSTHROUGH_FB};
        XrCompositionLayerProjection proj{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
        XrCompositionLayerProjectionView projViews[2]{{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW},
                                                      {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}};

        if (fs.shouldRender) {
            XrViewState vs{XR_TYPE_VIEW_STATE};
            XrViewLocateInfo li{XR_TYPE_VIEW_LOCATE_INFO};
            li.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
            li.displayTime = fs.predictedDisplayTime;
            li.space = local_;
            XrView views[2]{{XR_TYPE_VIEW}, {XR_TYPE_VIEW}};
            uint32_t vc = 0;
            xrLocateViews(session_, &li, &vs, 2, &vc, views);
            bool tracked = (vs.viewStateFlags & XR_VIEW_STATE_POSITION_VALID_BIT) &&
                           (vs.viewStateFlags & XR_VIEW_STATE_ORIENTATION_VALID_BIT);

            if (!layoutValid_ && tracked) recenter(fs.predictedDisplayTime);
            updateScreens();
            handleInput(fs.predictedDisplayTime);

            if (tracked) {
                for (int eye = 0; eye < 2; ++eye) {
                    renderEye(eye, views[eye]);
                    auto& sc = swapchains_[eye];
                    projViews[eye].pose = views[eye].pose;
                    projViews[eye].fov = views[eye].fov;
                    projViews[eye].subImage.swapchain = sc.handle;
                    projViews[eye].subImage.imageRect = {{0, 0}, {sc.width, sc.height}};
                }
                if (passthroughOn_) {
                    ptLayer.layerHandle = passthroughLayer_;
                    layers.push_back((XrCompositionLayerBaseHeader*)&ptLayer);
                    proj.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
                }
                proj.space = local_;
                proj.viewCount = 2;
                proj.views = projViews;
                layers.push_back((XrCompositionLayerBaseHeader*)&proj);
            }
        }

        XrFrameEndInfo ei{XR_TYPE_FRAME_END_INFO};
        ei.displayTime = fs.predictedDisplayTime;
        ei.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
        ei.layerCount = (uint32_t)layers.size();
        ei.layers = layers.data();
        xrEndFrame(session_, &ei);
    }

    void recenter(XrTime time) {
        XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION};
        if (XR_FAILED(xrLocateSpace(view_, local_, time, &loc)) ||
            !(loc.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT)) return;
        Vec3 fwd = rotate(loc.pose.orientation, {0, 0, -1});
        float yaw = std::atan2(-fwd.x, -fwd.z);  // rotation about +Y that maps -Z to fwd
        Vec3 head{loc.pose.position.x, loc.pose.position.y, loc.pose.position.z};
        layout_ = yawTranslation(yaw, head);
        layoutInv_ = rigidInverse(layout_);
        floorY_ = -1.6f;  // grid ~1.6 m under eye level (LOCAL space has no floor)
        layoutValid_ = true;
    }

    // Asks Kotlin to upload new framebuffer content and lays out one screen per display.
    void updateScreens() {
        auto arr = (jintArray)env_->CallObjectMethod(activity_, onFrame_);
        if (env_->ExceptionCheck()) { env_->ExceptionDescribe(); env_->ExceptionClear(); }
        if (!arr) return;
        jsize len = env_->GetArrayLength(arr);
        jint* d = env_->GetIntArrayElements(arr, nullptr);
        screenCount_ = std::min<int>(len / 3, kMaxDisplays);
        float total = 0;
        for (int i = 0; i < screenCount_; ++i) {
            auto& s = screens_[i];
            s.texture = (GLuint)d[i * 3];
            s.width = d[i * 3 + 1] > 0 ? d[i * 3 + 1] : 1920;
            s.height = d[i * 3 + 2] > 0 ? d[i * 3 + 2] : 1080;
            total += (s.width / kPixelsPerMetre) / kRadius + (i ? kGap : 0);
        }
        env_->ReleaseIntArrayElements(arr, d, JNI_ABORT);
        env_->DeleteLocalRef(arr);
        float theta = -total / 2;
        for (int i = 0; i < screenCount_; ++i) {
            auto& s = screens_[i];
            float span = (s.width / kPixelsPerMetre) / kRadius;
            float h = s.height / kPixelsPerMetre;
            s.theta0 = theta;
            s.theta1 = theta + span;
            s.top = h / 2 - 0.1f;
            s.bottom = s.top - h;
            theta += span + kGap;
        }
    }

    bool intersect(Vec3 o, Vec3 d, Vec3& hit) const {
        // Ray vs vertical cylinder of radius kRadius around the layout origin (inside -> far root).
        float a = d.x * d.x + d.z * d.z;
        if (a < 1e-6f) return false;
        float b = 2 * (o.x * d.x + o.z * d.z);
        float c = o.x * o.x + o.z * o.z - kRadius * kRadius;
        float disc = b * b - 4 * a * c;
        if (disc < 0) return false;
        float t = (-b + std::sqrt(disc)) / (2 * a);
        if (t <= 0) return false;
        hit = o + d * t;
        return true;
    }

    void handleInput(XrTime time) {
        XrActiveActionSet active{actionSet_, XR_NULL_PATH};
        XrActionsSyncInfo si{XR_TYPE_ACTIONS_SYNC_INFO};
        si.countActiveActionSets = 1;
        si.activeActionSets = &active;
        if (XR_FAILED(xrSyncActions(session_, &si))) return;

        for (int i = 0; i < 2; ++i) {
            Hand& h = hands_[i];
            XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
            gi.subactionPath = h.path;

            XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION};
            h.active = XR_SUCCEEDED(xrLocateSpace(h.aimSpace, local_, time, &loc)) &&
                       (loc.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) &&
                       (loc.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT);
            if (h.active) {
                h.origin = {loc.pose.position.x, loc.pose.position.y, loc.pose.position.z};
                h.dir = rotate(loc.pose.orientation, {0, 0, -1});
            }

            XrActionStateFloat f{XR_TYPE_ACTION_STATE_FLOAT};
            gi.action = triggerAction_;
            xrGetActionStateFloat(session_, &gi, &f);
            bool trigger = f.isActive && f.currentState > (h.trigger ? 0.4f : 0.7f);
            gi.action = gripAction_;
            f = {XR_TYPE_ACTION_STATE_FLOAT};
            xrGetActionStateFloat(session_, &gi, &f);
            bool grip = f.isActive && f.currentState > (h.grip ? 0.4f : 0.7f);
            if ((trigger && !h.trigger) || (grip && !h.grip)) {
                activeHand_ = i;
                haptic(i);
            }
            h.trigger = trigger;
            h.grip = grip;

            XrActionStateBoolean bstate{XR_TYPE_ACTION_STATE_BOOLEAN};
            gi.action = primaryAction_;
            xrGetActionStateBoolean(session_, &gi, &bstate);
            if (bstate.currentState && !h.primaryPrev) togglePassthrough();
            h.primaryPrev = bstate.currentState;
            bstate = {XR_TYPE_ACTION_STATE_BOOLEAN};
            gi.action = secondaryAction_;
            xrGetActionStateBoolean(session_, &gi, &bstate);
            if (bstate.currentState && !h.secondaryPrev) layoutValid_ = false;  // recenter next frame
            h.secondaryPrev = bstate.currentState;

            // Hit test in layout space.
            h.hit = false;
            h.hitDisplay = -1;
            if (h.active && layoutValid_) {
                Vec3 o = transformPoint(layoutInv_, h.origin), d = transformDir(layoutInv_, h.dir), p{};
                if (intersect(o, d, p)) {
                    float theta = std::atan2(p.x, -p.z);
                    for (int s = 0; s < screenCount_; ++s) {
                        const auto& sc = screens_[s];
                        if (theta >= sc.theta0 && theta <= sc.theta1 && p.y <= sc.top && p.y >= sc.bottom) {
                            h.hit = true;
                            h.hitDisplay = s;
                            h.u = (theta - sc.theta0) / (sc.theta1 - sc.theta0);
                            h.v = (sc.top - p.y) / (sc.top - sc.bottom);
                            h.hitPoint = transformPoint(layout_, p);
                            break;
                        }
                    }
                }
            }
        }

        // Only the active hand drives the X pointer.
        Hand& h = hands_[activeHand_];
        if (!h.hit) return;
        int buttons = (h.trigger ? 1 : 0) | (h.grip ? 4 : 0);
        const auto& sc = screens_[h.hitDisplay];
        int px = (int)(h.u * sc.width), py = (int)(h.v * sc.height);
        if (h.hitDisplay != lastPointerDisplay_ || px != lastPx_ || py != lastPy_ || buttons != lastButtons_) {
            env_->CallVoidMethod(activity_, onPointer_, h.hitDisplay, h.u, h.v, buttons);
            lastPointerDisplay_ = h.hitDisplay; lastPx_ = px; lastPy_ = py; lastButtons_ = buttons;
        }

        XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
        gi.action = stickAction_;
        gi.subactionPath = h.path;
        XrActionStateVector2f stick{XR_TYPE_ACTION_STATE_VECTOR2F};
        xrGetActionStateVector2f(session_, &gi, &stick);
        auto now = std::chrono::steady_clock::now();
        if (stick.isActive && std::fabs(stick.currentState.y) > 0.6f && now - lastScroll_ > std::chrono::milliseconds(90)) {
            env_->CallVoidMethod(activity_, onScroll_, h.hitDisplay, h.u, h.v, (jboolean)(stick.currentState.y > 0));
            lastScroll_ = now;
        }
    }

    void haptic(int hand) {
        XrHapticVibration v{XR_TYPE_HAPTIC_VIBRATION};
        v.amplitude = 0.25f;
        v.duration = 15'000'000;  // 15 ms
        v.frequency = XR_FREQUENCY_UNSPECIFIED;
        XrHapticActionInfo hi{XR_TYPE_HAPTIC_ACTION_INFO};
        hi.action = hapticAction_;
        hi.subactionPath = hands_[hand].path;
        xrApplyHapticFeedback(session_, &hi, (XrHapticBaseHeader*)&v);
    }

    void togglePassthrough() {
        if (!hasPassthrough_) return;
        passthroughOn_ = !passthroughOn_;
        if (passthroughOn_) resumeLayer_(passthroughLayer_);
        else pauseLayer_(passthroughLayer_);
    }

    // --- rendering ----------------------------------------------------------------------------

    void drawColor(const Mat4& mvp, const std::vector<float>& verts, GLenum mode, const float color[4],
                   float pointSize = 1, float fadeRadius = 0, Vec3 fadeCenter = {}) {
        glUseProgram(colorProg_);
        glUniformMatrix4fv(glGetUniformLocation(colorProg_, "uMvp"), 1, GL_FALSE, mvp.m);
        glUniform4fv(glGetUniformLocation(colorProg_, "uColor"), 1, color);
        glUniform1f(glGetUniformLocation(colorProg_, "uPointSize"), pointSize);
        glUniform1f(glGetUniformLocation(colorProg_, "uFadeRadius"), fadeRadius);
        glUniform3f(glGetUniformLocation(colorProg_, "uFadeCenter"), fadeCenter.x, fadeCenter.y, fadeCenter.z);
        glBindBuffer(GL_ARRAY_BUFFER, lineVbo_);
        glBufferData(GL_ARRAY_BUFFER, (GLsizeiptr)(verts.size() * sizeof(float)), verts.data(), GL_STREAM_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 12, nullptr);
        glEnableVertexAttribArray(0);
        glDisableVertexAttribArray(1);
        glDrawArrays(mode, 0, (GLsizei)(verts.size() / 3));
    }

    void drawGrid(const Mat4& viewProj) {
        if (gridVertexCount_ == 0) {
            std::vector<float> g;
            for (int i = -20; i <= 20; ++i) {
                g.insert(g.end(), {(float)i, 0, -20.f, (float)i, 0, 20.f, -20.f, 0, (float)i, 20.f, 0, (float)i});
            }
            glBindBuffer(GL_ARRAY_BUFFER, gridVbo_);
            glBufferData(GL_ARRAY_BUFFER, (GLsizeiptr)(g.size() * sizeof(float)), g.data(), GL_STATIC_DRAW);
            gridVertexCount_ = (GLsizei)(g.size() / 3);
        }
        Mat4 model = mul(layout_, yawTranslation(0, {0, floorY_, 0}));
        Mat4 mvp = mul(viewProj, model);
        const float color[4] = {0.25f, 0.45f, 0.9f, 0.55f};
        glUseProgram(colorProg_);
        glUniformMatrix4fv(glGetUniformLocation(colorProg_, "uMvp"), 1, GL_FALSE, mvp.m);
        glUniform4fv(glGetUniformLocation(colorProg_, "uColor"), 1, color);
        glUniform1f(glGetUniformLocation(colorProg_, "uPointSize"), 1);
        glUniform1f(glGetUniformLocation(colorProg_, "uFadeRadius"), 14);
        glUniform3f(glGetUniformLocation(colorProg_, "uFadeCenter"), 0, 0, 0);
        glBindBuffer(GL_ARRAY_BUFFER, gridVbo_);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 12, nullptr);
        glEnableVertexAttribArray(0);
        glDisableVertexAttribArray(1);
        glDrawArrays(GL_LINES, 0, gridVertexCount_);
    }

    void drawScreens(const Mat4& viewProj) {
        Mat4 mvp = mul(viewProj, layout_);
        glUseProgram(screenProg_);
        glUniformMatrix4fv(glGetUniformLocation(screenProg_, "uMvp"), 1, GL_FALSE, mvp.m);
        glUniform1i(glGetUniformLocation(screenProg_, "uTex"), 0);
        glActiveTexture(GL_TEXTURE0);
        for (int s = 0; s < screenCount_; ++s) {
            const auto& sc = screens_[s];
            std::vector<float> v;
            v.reserve((kSegments + 1) * 10);
            for (int i = 0; i <= kSegments; ++i) {
                float u = (float)i / kSegments;
                float th = sc.theta0 + u * (sc.theta1 - sc.theta0);
                float x = kRadius * std::sin(th), z = -kRadius * std::cos(th);
                v.insert(v.end(), {x, sc.top, z, u, 0.f, x, sc.bottom, z, u, 1.f});
            }
            const float placeholder[4] = {0.12f, 0.12f, 0.16f, 1.f}, none[4] = {0, 0, 0, 0};
            glUniform4fv(glGetUniformLocation(screenProg_, "uTint"), 1, sc.texture ? none : placeholder);
            glBindTexture(GL_TEXTURE_2D, sc.texture);
            glBindBuffer(GL_ARRAY_BUFFER, screenVbo_);
            glBufferData(GL_ARRAY_BUFFER, (GLsizeiptr)(v.size() * sizeof(float)), v.data(), GL_STREAM_DRAW);
            glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 20, nullptr);
            glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 20, (void*)12);
            glEnableVertexAttribArray(0);
            glEnableVertexAttribArray(1);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, (GLsizei)(v.size() / 5));
        }
    }

    void drawPointers(const Mat4& viewProj) {
        for (int i = 0; i < 2; ++i) {
            const Hand& h = hands_[i];
            if (!h.active) continue;
            Vec3 end = h.hit ? h.hitPoint : h.origin + h.dir * 3.f;
            const float ray[4] = {0.9f, 0.9f, 1.f, i == activeHand_ ? 0.8f : 0.35f};
            drawColor(viewProj, {h.origin.x, h.origin.y, h.origin.z, end.x, end.y, end.z}, GL_LINES, ray);
            if (h.hit) {
                const float dot[4] = {1.f, h.trigger ? 0.4f : 1.f, h.trigger ? 0.4f : 1.f, 1.f};
                drawColor(viewProj, {end.x, end.y, end.z}, GL_POINTS, dot, 14);
            }
        }
    }

    void renderEye(int eye, const XrView& view) {
        auto& sc = swapchains_[eye];
        uint32_t index = 0;
        XrSwapchainImageAcquireInfo ai{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
        xrAcquireSwapchainImage(sc.handle, &ai, &index);
        XrSwapchainImageWaitInfo wi{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
        wi.timeout = XR_INFINITE_DURATION;
        xrWaitSwapchainImage(sc.handle, &wi);

        glBindFramebuffer(GL_FRAMEBUFFER, sc.fbos[index]);
        glViewport(0, 0, sc.width, sc.height);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        if (passthroughOn_) glClearColor(0, 0, 0, 0);
        else glClearColor(0.008f, 0.009f, 0.015f, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

        Mat4 viewProj = mul(projection(view.fov, 0.05f, 100.f), rigidInverse(poseMatrix(view.pose)));
        if (layoutValid_) {
            if (!passthroughOn_) drawGrid(viewProj);
            drawScreens(viewProj);
        }
        drawPointers(viewProj);

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
        xrReleaseSwapchainImage(sc.handle, &ri);
    }
};

}  // namespace

extern "C" {

JNIEXPORT void JNICALL Java_com_linuxxr_ui_ImmersiveActivity_nativeRun(JNIEnv* env, jobject thiz) {
    g_exitRequested = false;
    JavaVM* vm = nullptr;
    env->GetJavaVM(&vm);
    jobject activity = env->NewGlobalRef(thiz);
    {
        App app(env, activity);
        if (!app.run(vm)) LOGE("OpenXR initialisation failed");
    }
    env->DeleteGlobalRef(activity);
}

JNIEXPORT void JNICALL Java_com_linuxxr_ui_ImmersiveActivity_nativeRequestExit(JNIEnv*, jobject) {
    g_exitRequested = true;
}

}  // extern "C"
