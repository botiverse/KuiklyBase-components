#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "curl_wrapper.h"

namespace {

constexpr const char *kProbeUrl = "https://example.com/";

int SpikeLog(int level, const char *tag, const char *content) {
    std::fprintf(
        stderr,
        "SLOCK_IOS_CURL_SPIKE log level=%d tag=%s content=%s\n",
        level,
        tag == nullptr ? "" : tag,
        content == nullptr ? "" : content
    );
    return 1;
}

struct ProbeResult {
    bool passed = false;
};

void ProbeCallback(void *callbackRef, CurlResponse *response) {
    auto *result = static_cast<ProbeResult *>(callbackRef);
    const char *error = response->errorMsg == nullptr ? "" : response->errorMsg;
    std::fprintf(
        stderr,
        "SLOCK_IOS_CURL_SPIKE result curlCode=%d httpCode=%ld bytes=%d "
        "dnsMs=%.3f connectMs=%.3f tlsMs=%.3f ttfbMs=%.3f totalMs=%.3f error=%.*s\n",
        response->code,
        response->httpCode,
        response->dataLen,
        response->elapse.nameLookupTimeMs,
        response->elapse.connectTimeMs,
        response->elapse.sslCostTimeMs,
        response->elapse.startTransferTimeMs,
        response->elapse.totalTimeMs,
        response->errorMsgLen,
        error
    );
    result->passed = response->code == 0 && response->httpCode == 200 && response->dataLen > 0;
}

void RunProbe() {
    @autoreleasepool {
        NSString *caPath = [[NSBundle mainBundle] pathForResource:@"cacert" ofType:@"pem"];
        if (caPath == nil) {
            std::fprintf(stderr, "SLOCK_IOS_CURL_SPIKE missing bundled cacert.pem\n");
            std::fflush(stderr);
            std::exit(2);
        }

        setCurlLogImpl(SpikeLog);

        StringDic headers{};
        CurlRequest request{};
        request.url = kProbeUrl;
        request.method = "GET";
        request.headers = &headers;
        request.timeout = 10'000;

        bool passed = true;
        for (int attempt = 1; attempt <= 2; ++attempt) {
            ProbeResult result;
            CurlCallback callback{};
            callback.callbackRef = &result;
            callback.callback = ProbeCallback;

            CurClientHandle client = CreateCurlClient("ios-curl-spike");
            SetCurlCaInfo(client, caPath.UTF8String);
            StartRequest(client, request, &callback);
            DeleteCurlClient(client);
            passed = passed && result.passed;
            std::fprintf(
                stderr,
                "SLOCK_IOS_CURL_SPIKE attempt=%d passed=%s\n",
                attempt,
                result.passed ? "true" : "false"
            );
        }

        std::fprintf(stderr, "SLOCK_IOS_CURL_SPIKE completed passed=%s\n", passed ? "true" : "false");
        std::fflush(stderr);
        std::exit(passed ? 0 : 3);
    }
}

}  // namespace

@interface CurlSpikeAppDelegate : UIResponder <UIApplicationDelegate>
@property(nonatomic, strong) UIWindow *window;
@end

@implementation CurlSpikeAppDelegate

- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)launchOptions {
    self.window = [[UIWindow alloc] initWithFrame:UIScreen.mainScreen.bounds];
    UIViewController *controller = [[UIViewController alloc] init];
    controller.view.backgroundColor = UIColor.whiteColor;
    self.window.rootViewController = controller;
    [self.window makeKeyAndVisible];

    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        RunProbe();
    });
    return YES;
}

@end

int main(int argc, char *argv[]) {
    @autoreleasepool {
        return UIApplicationMain(argc, argv, nil, NSStringFromClass(CurlSpikeAppDelegate.class));
    }
}
