// LinuxXR immersive mode: OpenXR stereo renderer for the Linux desktops.
//
// Display :1 is a 360° desktop (six RandR monitors side by side) wrapped all around the user;
// other displays sit above it. Desktop tiles are OpenXR cylinder composition layers sampled
// directly by the compositor (sharpest path for text); passthrough is the background, and the
// bare X root window is transparent so windows float in the room. Controllers: trigger = left
// click, grip = right click, stick up/down = scroll, stick left/right = spin the desktop 30°,
// A/X = passthrough on/off, B/Y = recenter. Uploads happen in Kotlin (ImmersiveActivity.onFrame)
// on this thread, with this EGL context current.

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

const char* kColorVs = R"(#version 300 es
layout(location = 0) in vec3 aPos;
uniform mat4 uMvp;
uniform float uPointSize;
out vec3 vPos;
void main() { vPos = aPos; gl_PointSize = uPointSize; gl_Position = uMvp * vec4(aPos, 1.0); })";

// Premultiplied output: the overlay layer is composited with source alpha.
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
    color = vec4(uColor.rgb * a, a);
})";

// ---------------------------------------------------------------------------------------------

constexpr float kPi = 3.14159265358979f;
constexpr float kRadius = 1.8f;                    // metres from the user to the desktop
constexpr float kDefaultPxPerRad = 8640 / (2 * kPi);  // ~24 px/deg = Quest 3 panel density
constexpr int kMaxDisplays = 4;
constexpr int kMaxTiles = 8;

// A cylinder-layer swapchain holding one tile (a column) of a display's framebuffer. The
// compositor samples it directly at display resolution: no intermediate eye-buffer pass.
struct Tile {
    XrSwapchain swapchain = XR_NULL_HANDLE;
    int width = 0, height = 0;
    std::vector<XrSwapchainImageOpenGLESKHR> images;
    std::vector<GLuint> fbos;
    bool hasContent = false;
};

struct Screen {
    int width = 0, height = 0, tileW = 0;
    std::vector<GLuint> sources;  // Kotlin staging textures, one per tile
    std::vector<Tile> tiles;
    float theta0 = 0, span = 0;   // angular extent; 0 = straight ahead, positive to the right
    float top = 0, bottom = 0;    // heights relative to eye level (metres)
    bool visible = false;
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
    bool primaryPrev = false, secondaryPrev = false, stickXPrev = false;
};

float wrapPositive(float a) {
    a = std::fmod(a, 2 * kPi);
    return a < 0 ? a + 2 * kPi : a;
}

XrQuaternionf yawQuat(float yaw) { return {0, std::sin(yaw / 2), 0, std::cos(yaw / 2)}; }

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
        if (!initInstance(vm) || !initEgl() || !initSession() || !initActions()) return false;
        if (!createEyeSwapchains(env_layer_) || !createEyeSwapchains(overlay_)) return false;
        initPassthrough();
        colorProg_ = program(kColorVs, kColorFs);
        glGenBuffers(1, &lineVbo_);
        glGenBuffers(1, &gridVbo_);
        glGenFramebuffers(1, &readFbo_);
        loop();
        return true;
    }

    ~App() {
        for (auto& s : screens_) for (auto& t : s.tiles) destroyTile(t);
        if (passthroughLayer_) destroyPassthroughLayer_(passthroughLayer_);
        if (passthrough_) destroyPassthrough_(passthrough_);
        for (auto* set : {env_layer_, overlay_})
            for (int i = 0; i < 2; ++i) if (set[i].handle) xrDestroySwapchain(set[i].handle);
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
    int64_t format_ = GL_SRGB8_ALPHA8;
    Swapchain env_layer_[2];  // dark room + floor grid (only without passthrough), under the desktop
    Swapchain overlay_[2];    // controller rays and cursor, over the desktop
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

    bool hasLayerSettings_ = false;
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

    GLuint colorProg_ = 0, lineVbo_ = 0, gridVbo_ = 0, readFbo_ = 0;
    GLsizei gridVertexCount_ = 0;

    Screen screens_[kMaxDisplays];
    int screenCount_ = 0;
    bool layoutValid_ = false;
    float layoutYaw_ = 0;
    Vec3 layoutPos_{};
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
        if (!has(XR_KHR_COMPOSITION_LAYER_CYLINDER_EXTENSION_NAME)) {
            LOGE("runtime lacks %s", XR_KHR_COMPOSITION_LAYER_CYLINDER_EXTENSION_NAME);
            return false;
        }
        std::vector<const char*> enabled{XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME, XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
                                         XR_KHR_COMPOSITION_LAYER_CYLINDER_EXTENSION_NAME};
        hasPassthrough_ = has(XR_FB_PASSTHROUGH_EXTENSION_NAME);
        if (hasPassthrough_) enabled.push_back(XR_FB_PASSTHROUGH_EXTENSION_NAME);
        hasLayerSettings_ = has(XR_FB_COMPOSITION_LAYER_SETTINGS_EXTENSION_NAME);
        if (hasLayerSettings_) enabled.push_back(XR_FB_COMPOSITION_LAYER_SETTINGS_EXTENSION_NAME);

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

        uint32_t n = 0;
        XR_CHECK(xrEnumerateViewConfigurationViews(instance_, system_, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 0, &n, nullptr));
        viewConfigs_.assign(n, {XR_TYPE_VIEW_CONFIGURATION_VIEW});
        XR_CHECK(xrEnumerateViewConfigurationViews(instance_, system_, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, n, &n, viewConfigs_.data()));
        if (n != 2) { LOGE("expected 2 views, got %u", n); return false; }

        uint32_t fc = 0;
        xrEnumerateSwapchainFormats(session_, 0, &fc, nullptr);
        std::vector<int64_t> formats(fc);
        xrEnumerateSwapchainFormats(session_, fc, &fc, formats.data());
        format_ = GL_RGBA8;
        for (auto f : formats) if (f == GL_SRGB8_ALPHA8) format_ = GL_SRGB8_ALPHA8;
        return true;
    }

    bool createSwapchain(int w, int h, uint32_t mips, XrSwapchain* out, std::vector<XrSwapchainImageOpenGLESKHR>& images,
                         std::vector<GLuint>& fbos) {
        XrSwapchainCreateInfo ci{XR_TYPE_SWAPCHAIN_CREATE_INFO};
        ci.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT | XR_SWAPCHAIN_USAGE_TRANSFER_DST_BIT;
        ci.format = format_;
        ci.sampleCount = 1;
        ci.width = (uint32_t)w;
        ci.height = (uint32_t)h;
        ci.faceCount = 1;
        ci.arraySize = 1;
        ci.mipCount = mips;
        XR_CHECK(xrCreateSwapchain(session_, &ci, out));
        uint32_t ic = 0;
        XR_CHECK(xrEnumerateSwapchainImages(*out, 0, &ic, nullptr));
        images.assign(ic, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
        XR_CHECK(xrEnumerateSwapchainImages(*out, ic, &ic, (XrSwapchainImageBaseHeader*)images.data()));
        fbos.resize(ic);
        glGenFramebuffers((GLsizei)ic, fbos.data());
        for (uint32_t i = 0; i < ic; ++i) {
            glBindFramebuffer(GL_FRAMEBUFFER, fbos[i]);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, images[i].image, 0);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return true;
    }

    bool createEyeSwapchains(Swapchain (&set)[2]) {
        for (int eye = 0; eye < 2; ++eye) {
            auto& sc = set[eye];
            sc.width = (int32_t)viewConfigs_[eye].recommendedImageRectWidth;
            sc.height = (int32_t)viewConfigs_[eye].recommendedImageRectHeight;
            if (!createSwapchain(sc.width, sc.height, 1, &sc.handle, sc.images, sc.fbos)) return false;
        }
        return true;
    }

    void destroyTile(Tile& t) {
        if (!t.fbos.empty()) glDeleteFramebuffers((GLsizei)t.fbos.size(), t.fbos.data());
        if (t.swapchain) xrDestroySwapchain(t.swapchain);
        t = Tile{};
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
            !createAction(&stickAction_, XR_ACTION_TYPE_VECTOR2F_INPUT, "scroll_rotate", "Scroll / rotate") ||
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
        if (!createPassthrough_ || XR_FAILED(createPassthrough_(session_, &pci, &passthrough_))) {
            hasPassthrough_ = false;
            return;
        }
        // Passthrough is the default background: windows float in the real room.
        XrPassthroughLayerCreateInfoFB lci{XR_TYPE_PASSTHROUGH_LAYER_CREATE_INFO_FB};
        lci.passthrough = passthrough_;
        lci.purpose = XR_PASSTHROUGH_LAYER_PURPOSE_RECONSTRUCTION_FB;
        lci.flags = XR_PASSTHROUGH_IS_RUNNING_AT_CREATION_BIT_FB;
        if (XR_FAILED(createPassthroughLayer_(session_, &lci, &passthroughLayer_))) {
            hasPassthrough_ = false;
            return;
        }
        passthroughOn_ = true;
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
        XrCompositionLayerProjection envProj{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
        XrCompositionLayerProjection overlayProj{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
        XrCompositionLayerProjectionView envViews[2]{{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW},
                                                     {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}};
        XrCompositionLayerProjectionView overlayViews[2]{{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW},
                                                         {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}};
        std::vector<XrCompositionLayerCylinderKHR> cylinders;
        cylinders.reserve(kMaxDisplays * kMaxTiles);
        XrCompositionLayerSettingsFB settings{XR_TYPE_COMPOSITION_LAYER_SETTINGS_FB};
        // Compositor-side supersampling + sharpening: the recommended path for text on Quest.
        settings.layerFlags = XR_COMPOSITION_LAYER_SETTINGS_QUALITY_SUPER_SAMPLING_BIT_FB |
                              XR_COMPOSITION_LAYER_SETTINGS_QUALITY_SHARPENING_BIT_FB;

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
                if (passthroughOn_) {
                    ptLayer.layerHandle = passthroughLayer_;
                    layers.push_back((XrCompositionLayerBaseHeader*)&ptLayer);
                } else {
                    for (int eye = 0; eye < 2; ++eye) renderEye(env_layer_[eye], envViews[eye], views[eye], true);
                    envProj.space = local_;
                    envProj.viewCount = 2;
                    envProj.views = envViews;
                    layers.push_back((XrCompositionLayerBaseHeader*)&envProj);
                }
                if (layoutValid_) addCylinders(cylinders, settings, layers);
                for (int eye = 0; eye < 2; ++eye) renderEye(overlay_[eye], overlayViews[eye], views[eye], false);
                overlayProj.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
                overlayProj.space = local_;
                overlayProj.viewCount = 2;
                overlayProj.views = overlayViews;
                layers.push_back((XrCompositionLayerBaseHeader*)&overlayProj);
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
        layoutYaw_ = std::atan2(-fwd.x, -fwd.z);  // rotation about +Y that maps -Z to fwd
        layoutPos_ = {loc.pose.position.x, loc.pose.position.y, loc.pose.position.z};
        updateLayoutMatrix();
        layoutValid_ = true;
    }

    void updateLayoutMatrix() {
        layout_ = yawTranslation(layoutYaw_, layoutPos_);
        layoutInv_ = rigidInverse(layout_);
    }

    // Takes the Kotlin staging textures, copies changed tiles into their cylinder swapchains and
    // lays the screens out: display 1 as a 360° ring at eye level, others above it.
    void updateScreens() {
        auto arr = (jintArray)env_->CallObjectMethod(activity_, onFrame_);
        if (env_->ExceptionCheck()) { env_->ExceptionDescribe(); env_->ExceptionClear(); }
        if (!arr) return;
        jsize len = env_->GetArrayLength(arr);
        std::vector<jint> d(len);
        env_->GetIntArrayRegion(arr, 0, len, d.data());
        env_->DeleteLocalRef(arr);

        size_t p = 0;
        auto next = [&]() { return p < d.size() ? d[p++] : 0; };
        screenCount_ = std::min<int>(next(), kMaxDisplays);
        for (int i = 0; i < screenCount_; ++i) {
            Screen& s = screens_[i];
            s.width = next();
            s.height = next();
            s.tileW = next();
            int tiles = std::min(next(), kMaxTiles);
            int dirty = next();
            s.sources.assign(tiles, 0);
            for (int k = 0; k < tiles; ++k) s.sources[k] = (GLuint)next();
            s.visible = tiles > 0 && s.width > 0 && s.height > 0;
            if (!s.visible) continue;
            if ((int)s.tiles.size() != tiles) {
                for (auto& t : s.tiles) destroyTile(t);
                s.tiles.assign(tiles, Tile{});
            }
            for (int k = 0; k < tiles; ++k) {
                int tw = std::min(s.tileW, s.width - k * s.tileW);
                Tile& t = s.tiles[k];
                if (t.width != tw || t.height != s.height) {
                    destroyTile(t);
                    uint32_t mips = 1;
                    for (int m = std::max(tw, s.height); m > 1; m >>= 1) ++mips;
                    if (!createSwapchain(tw, s.height, mips, &t.swapchain, t.images, t.fbos)) continue;
                    t.width = tw;
                    t.height = s.height;
                    dirty |= 1 << k;
                }
                if (dirty & (1 << k)) copyTile(t, s.sources[k]);
            }
        }

        // Layout. Ring when display 1 is a wide multi-monitor desktop.
        float pxPerRad = kDefaultPxPerRad;
        Screen& ring = screens_[0];
        bool isRing = screenCount_ > 0 && ring.visible && ring.width >= 4 * ring.height;
        if (isRing) pxPerRad = ring.width / (2 * kPi);
        float metresPerPx = kRadius / pxPerRad;  // arc length per pixel at the screen radius
        float ringTop = 0;
        for (int i = 0; i < screenCount_; ++i) {
            Screen& s = screens_[i];
            int w = s.visible ? s.width : 1920, h = s.visible ? s.height : 1080;
            s.span = w / pxPerRad;
            float hm = h * metresPerPx;
            if (i == 0) {
                // Ring: first monitor centred in front of the user.
                s.theta0 = isRing ? -(s.tileW / 2.f) / pxPerRad : -s.span / 2;
                s.top = hm / 2 - 0.1f;
                s.bottom = s.top - hm;
                ringTop = s.top;
            } else {
                static const float centres[] = {0, kPi / 2, -kPi / 2};
                s.theta0 = centres[(i - 1) % 3] - s.span / 2;
                s.bottom = ringTop + 0.12f;
                s.top = s.bottom + hm;
            }
        }
    }

    void copyTile(Tile& t, GLuint source) {
        if (!t.swapchain || !source) return;
        uint32_t index = 0;
        XrSwapchainImageAcquireInfo ai{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
        if (XR_FAILED(xrAcquireSwapchainImage(t.swapchain, &ai, &index))) return;
        XrSwapchainImageWaitInfo wi{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
        wi.timeout = XR_INFINITE_DURATION;
        xrWaitSwapchainImage(t.swapchain, &wi);
        // Swapchain images are recycled, so always copy the whole tile, not just the dirty rect.
        glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo_);
        glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, source, 0);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, t.fbos[index]);
        // Staging rows are top-down (X order) but GL swapchain images are bottom-up: flip here.
        glBlitFramebuffer(0, 0, t.width, t.height, 0, t.height, t.width, 0, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        // Mips let the compositor minify cleanly when a window is far or seen at a grazing angle.
        glBindTexture(GL_TEXTURE_2D, t.images[index].image);
        glGenerateMipmap(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, 0);
        XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
        xrReleaseSwapchainImage(t.swapchain, &ri);
        t.hasContent = true;
    }

    void addCylinders(std::vector<XrCompositionLayerCylinderKHR>& cylinders, XrCompositionLayerSettingsFB& settings,
                      std::vector<XrCompositionLayerBaseHeader*>& layers) {
        for (int i = 0; i < screenCount_; ++i) {
            const Screen& s = screens_[i];
            if (!s.visible) continue;
            float pxPerRad = s.width / s.span;
            float height = s.top - s.bottom;
            for (size_t k = 0; k < s.tiles.size(); ++k) {
                const Tile& t = s.tiles[k];
                if (!t.hasContent) continue;
                float tSpan = t.width / pxPerRad;
                float centre = s.theta0 + (k * s.tileW) / pxPerRad + tSpan / 2;
                XrCompositionLayerCylinderKHR c{XR_TYPE_COMPOSITION_LAYER_CYLINDER_KHR};
                c.next = hasLayerSettings_ ? &settings : nullptr;
                // Windows are opaque, the bare root window is alpha 0 (passthrough shows through).
                c.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
                c.space = local_;
                c.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
                c.subImage.swapchain = t.swapchain;
                c.subImage.imageRect = {{0, 0}, {t.width, t.height}};
                c.pose.orientation = yawQuat(layoutYaw_ - centre);
                c.pose.position = {layoutPos_.x, layoutPos_.y + (s.top + s.bottom) / 2, layoutPos_.z};
                c.radius = kRadius;
                c.centralAngle = tSpan;
                c.aspectRatio = (kRadius * tSpan) / height;
                cylinders.push_back(c);
                layers.push_back((XrCompositionLayerBaseHeader*)&cylinders.back());
            }
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

            // Thumbstick left/right flick: spin the 360° desktop by 30° (no need to turn around).
            XrActionStateVector2f stick{XR_TYPE_ACTION_STATE_VECTOR2F};
            gi.action = stickAction_;
            xrGetActionStateVector2f(session_, &gi, &stick);
            bool flick = stick.isActive && std::fabs(stick.currentState.x) > 0.7f && std::fabs(stick.currentState.y) < 0.5f;
            if (flick && !h.stickXPrev && layoutValid_) {
                layoutYaw_ += stick.currentState.x > 0 ? -kPi / 6 : kPi / 6;
                updateLayoutMatrix();
            }
            h.stickXPrev = flick;

            // Hit test in layout space.
            h.hit = false;
            h.hitDisplay = -1;
            if (h.active && layoutValid_) {
                Vec3 o = transformPoint(layoutInv_, h.origin), dl = transformDir(layoutInv_, h.dir), pt{};
                if (intersect(o, dl, pt)) {
                    float theta = std::atan2(pt.x, -pt.z);
                    for (int s = 0; s < screenCount_; ++s) {
                        const auto& sc = screens_[s];
                        if (!sc.visible) continue;
                        float off = wrapPositive(theta - sc.theta0);
                        if (off <= sc.span && pt.y <= sc.top && pt.y >= sc.bottom) {
                            h.hit = true;
                            h.hitDisplay = s;
                            h.u = off / sc.span;
                            h.v = (sc.top - pt.y) / (sc.top - sc.bottom);
                            h.hitPoint = transformPoint(layout_, pt);
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

    void drawColor(const Mat4& mvp, const std::vector<float>& verts, GLenum mode, const float color[4], float pointSize = 1) {
        glUseProgram(colorProg_);
        glUniformMatrix4fv(glGetUniformLocation(colorProg_, "uMvp"), 1, GL_FALSE, mvp.m);
        glUniform4fv(glGetUniformLocation(colorProg_, "uColor"), 1, color);
        glUniform1f(glGetUniformLocation(colorProg_, "uPointSize"), pointSize);
        glUniform1f(glGetUniformLocation(colorProg_, "uFadeRadius"), 0);
        glBindBuffer(GL_ARRAY_BUFFER, lineVbo_);
        glBufferData(GL_ARRAY_BUFFER, (GLsizeiptr)(verts.size() * sizeof(float)), verts.data(), GL_STREAM_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 12, nullptr);
        glEnableVertexAttribArray(0);
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
        Mat4 mvp = mul(viewProj, mul(layout_, yawTranslation(0, {0, floorY_, 0})));
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
        glDrawArrays(GL_LINES, 0, gridVertexCount_);
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

    // environment = true: dark room + floor grid (opaque). false: transparent overlay with rays.
    void renderEye(Swapchain& sc, XrCompositionLayerProjectionView& pv, const XrView& view, bool environment) {
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
        if (environment) glClearColor(0.008f, 0.009f, 0.015f, 1);
        else glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);  // premultiplied

        Mat4 viewProj = mul(projection(view.fov, 0.05f, 100.f), rigidInverse(poseMatrix(view.pose)));
        if (environment) {
            if (layoutValid_) drawGrid(viewProj);
        } else {
            drawPointers(viewProj);
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
        xrReleaseSwapchainImage(sc.handle, &ri);

        pv.pose = view.pose;
        pv.fov = view.fov;
        pv.subImage.swapchain = sc.handle;
        pv.subImage.imageRect = {{0, 0}, {sc.width, sc.height}};
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
