#import <Cocoa/Cocoa.h>
#import <QuartzCore/QuartzCore.h>
#import <UserNotifications/UserNotifications.h>
#import <objc/message.h>
#include <jni.h>

// AppKit owns layout, SF Symbols, focus, vibrancy, toolbar, sheets and accessibility.
// JNI transports only section indices, public UI labels and window geometry; tunnel
// operations stay in the JVM. Every entry point hops to the main queue and fails soft:
// a zero handle, NULL state or JNI_FALSE tells Kotlin to keep its Compose fallback.
static JavaVM *vm;
static jclass callbackClass;
static jmethodID navigateMethod;
static jmethodID displayMethod;
static jmethodID toolbarMethod;
static jmethodID confirmMethod;
static NSMutableDictionary<NSNumber *, id> *sidebars;
static uint64_t nextHandle = 1;
static id displayObserver;

static NSToolbarItemIdentifier const VLKStatusItem = @"app.veilark.macos.toolbar.status";
static NSToolbarItemIdentifier const VLKActionItem = @"app.veilark.macos.toolbar.connection";

static BOOL bindCallbacks(JNIEnv *env, jclass cls) {
    @synchronized(NSObject.class) {
        if (vm && callbackClass && navigateMethod && displayMethod && toolbarMethod && confirmMethod) return YES;
        if (!vm && (*env)->GetJavaVM(env, &vm) != JNI_OK) return NO;
        if (!callbackClass) callbackClass = (*env)->NewGlobalRef(env, cls);
        if (!callbackClass) return NO;
        navigateMethod = (*env)->GetStaticMethodID(env, cls, "onNavigate", "(I)V");
        if (!navigateMethod) { (*env)->ExceptionClear(env); return NO; }
        displayMethod = (*env)->GetStaticMethodID(env, cls, "onDisplayPreferencesChanged", "(I)V");
        if (!displayMethod) { (*env)->ExceptionClear(env); return NO; }
        toolbarMethod = (*env)->GetStaticMethodID(env, cls, "onToolbarAction", "(I)V");
        if (!toolbarMethod) { (*env)->ExceptionClear(env); return NO; }
        confirmMethod = (*env)->GetStaticMethodID(env, cls, "onConfirmResult", "(II)V");
        if (!confirmMethod) { (*env)->ExceptionClear(env); return NO; }
        return YES;
    }
}

// Calls MacNativeChrome.<method>(a[, b]) from any AppKit thread. A Java exception must
// never escape into AppKit's event loop.
static void callJava(jmethodID method, jint a, jint b, BOOL twoArguments) {
    if (!vm || !callbackClass || !method) return;
    JNIEnv *env = NULL;
    BOOL attached = NO;
    jint result = (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8);
    if (result == JNI_EDETACHED) {
        if ((*vm)->AttachCurrentThread(vm, (void **)&env, NULL) != JNI_OK) return;
        attached = YES;
    } else if (result != JNI_OK) return;
    if (twoArguments) (*env)->CallStaticVoidMethod(env, callbackClass, method, a, b);
    else (*env)->CallStaticVoidMethod(env, callbackClass, method, a);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (attached) (*vm)->DetachCurrentThread(vm);
}

// Runs work on the main thread and waits at most `seconds`. A block that has not started
// by the deadline is cancelled (it will never run), so a busy or blocked AppKit thread
// cannot deadlock the JVM caller and cannot create an orphaned native view later.
@interface VLKMainCall : NSObject {
@public
    int state; // 0 pending, 1 running/finished, 2 abandoned
}
@end
@implementation VLKMainCall
@end

static BOOL onMainWithin(double seconds, void (^work)(void)) {
    if (NSThread.isMainThread) {
        work();
        return YES;
    }
    VLKMainCall *call = [VLKMainCall new];
    dispatch_semaphore_t done = dispatch_semaphore_create(0);
    dispatch_async(dispatch_get_main_queue(), ^{
        BOOL run;
        @synchronized(call) {
            run = call->state == 0;
            if (run) call->state = 1;
        }
        if (run) work();
        dispatch_semaphore_signal(done);
    });
    if (dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, (int64_t)(seconds * NSEC_PER_SEC))) == 0) {
        return YES;
    }
    BOOL abandoned;
    @synchronized(call) {
        abandoned = call->state == 0;
        if (abandoned) call->state = 2;
    }
    if (abandoned) return NO;
    dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
    return YES;
}

static jint currentDisplayMask(void) {
    NSWorkspace *workspace = NSWorkspace.sharedWorkspace;
    return (workspace.accessibilityDisplayShouldReduceMotion ? 1 : 0) |
        (workspace.accessibilityDisplayShouldReduceTransparency ? 2 : 0) |
        (workspace.accessibilityDisplayShouldIncreaseContrast ? 4 : 0);
}

// macOS 26 SDK: NSBezelStyleGlass. Guarded both at compile time (older SDKs do not
// declare it) and at run time (older systems do not implement it).
static BOOL applyGlassBezel(NSButton *button) {
#if defined(__MAC_26_0) && __MAC_OS_X_VERSION_MAX_ALLOWED >= __MAC_26_0
    if (@available(macOS 26.0, *)) {
        button.bezelStyle = NSBezelStyleGlass;
        return YES;
    }
#endif
    button.bezelStyle = (NSBezelStyle)11; // textured rounded / toolbar on every supported SDK
    return NO;
}

@class VLKSidebar;
@interface VLKContentView : NSView
@property(nonatomic, weak) VLKSidebar *owner;
@end

@interface VLKSidebarButtonCell : NSButtonCell
@end
@implementation VLKSidebarButtonCell
- (void)drawWithFrame:(NSRect)cellFrame inView:(NSView *)controlView {
    [super drawWithFrame:NSInsetRect(cellFrame, 10, 0) inView:controlView];
}
@end

@interface VLKSidebar : NSObject <NSToolbarDelegate>
@property(nonatomic, strong) NSView *view;
@property(nonatomic, weak) NSWindow *window;
@property(nonatomic, strong) NSTextField *status;
@property(nonatomic, strong) NSArray<NSButton *> *buttons;
@property(nonatomic, strong) id colorObserver;
@property(nonatomic) NSInteger selectedIndex;
@property(nonatomic) jint materialMode;
// Toolbar: 0 absent (another toolbar owned the window), 1 unified, 2 unified + glass bezels.
@property(nonatomic, strong) NSToolbar *toolbar;
@property(nonatomic, strong) NSButton *statusButton;
@property(nonatomic, strong) NSButton *actionButton;
@property(nonatomic) jint toolbarMode;
// Target selection: 1 native handle, 2 title + exact frame, 3 the only visible window with the title.
@property(nonatomic) jint matchedBy;
@property(nonatomic) BOOL changedTitlebar;
@property(nonatomic) NSWindowTitleVisibility previousTitleVisibility;
- (void)refreshColors;
@end

@implementation VLKContentView
- (void)viewDidChangeEffectiveAppearance {
    [super viewDidChangeEffectiveAppearance];
    [self.owner refreshColors];
}
@end

@implementation VLKSidebar
- (void)dealloc {
    if (_colorObserver) [NSNotificationCenter.defaultCenter removeObserver:_colorObserver];
}
- (void)refreshColors {
    [self.view.effectiveAppearance performAsCurrentDrawingAppearance:^{
        if (![self.view isKindOfClass:NSVisualEffectView.class] &&
            ![self.view isKindOfClass:NSClassFromString(@"NSGlassEffectView")]) {
            self.view.layer.backgroundColor = NSColor.windowBackgroundColor.CGColor;
        }
        for (NSButton *button in self.buttons) {
            BOOL active = button.tag == self.selectedIndex;
            button.state = active ? NSControlStateValueOn : NSControlStateValueOff;
            button.contentTintColor = NSColor.labelColor;
            button.layer.backgroundColor = active ? [NSColor.controlAccentColor colorWithAlphaComponent:0.12].CGColor : NSColor.clearColor.CGColor;
            button.font = [NSFont systemFontOfSize:13 weight:active ? NSFontWeightSemibold : NSFontWeightRegular];
        }
    }];
}
- (void)navigate:(NSButton *)sender {
    // Native toggle state must reflect the confirmed section, including a repeat click.
    // Compose may not emit a new effect when the selected index has not changed.
    [self refreshColors];
    callJava(navigateMethod, (jint)sender.tag, 0, NO);
}
- (void)showOverview:(id)sender {
    callJava(navigateMethod, 0, 0, NO);
}
- (void)toggleConnection:(id)sender {
    callJava(toolbarMethod, 0, 0, NO);
}
- (NSArray<NSToolbarItemIdentifier> *)toolbarDefaultItemIdentifiers:(NSToolbar *)toolbar {
    return @[NSToolbarFlexibleSpaceItemIdentifier, VLKStatusItem, VLKActionItem];
}
- (NSArray<NSToolbarItemIdentifier> *)toolbarAllowedItemIdentifiers:(NSToolbar *)toolbar {
    return [self toolbarDefaultItemIdentifiers:toolbar];
}
- (NSToolbarItem *)toolbar:(NSToolbar *)toolbar itemForItemIdentifier:(NSToolbarItemIdentifier)identifier
  willBeInsertedIntoToolbar:(BOOL)flag {
    NSButton *button = nil;
    if ([identifier isEqualToString:VLKStatusItem]) button = self.statusButton;
    else if ([identifier isEqualToString:VLKActionItem]) button = self.actionButton;
    if (!button) return nil;
    NSToolbarItem *item = [[NSToolbarItem alloc] initWithItemIdentifier:identifier];
    item.view = button;
    item.label = button.title;
    item.paletteLabel = button.title;
    item.toolTip = button.title;
    return item;
}
@end

static NSString *readString(JNIEnv *env, jstring value) {
    if (!value) return @"";
    const jchar *characters = (*env)->GetStringChars(env, value, NULL);
    if (!characters) return @"";
    NSString *text = [[NSString alloc] initWithCharacters:(const unichar *)characters
                                                  length:(NSUInteger)(*env)->GetStringLength(env, value)];
    (*env)->ReleaseStringChars(env, value, characters);
    return text;
}

// Separate JNI callbacks: notification clicks also work when the main window is closed.
static JavaVM *noticeVM;
static jclass noticeClass;
static jmethodID noticeOpened, noticePosted;
static void notifyJava(jmethodID method, jint build, BOOL posted) {
    JNIEnv *env = NULL; BOOL attached = NO;
    if (!noticeVM || !noticeClass || !method) return;
    jint result = (*noticeVM)->GetEnv(noticeVM, (void **)&env, JNI_VERSION_1_6);
    if (result == JNI_EDETACHED) {
        if ((*noticeVM)->AttachCurrentThread(noticeVM, (void **)&env, NULL) != JNI_OK) return;
        attached = YES;
    } else if (result != JNI_OK) return;
    if (posted) (*env)->CallStaticVoidMethod(env, noticeClass, method, build);
    else (*env)->CallStaticVoidMethod(env, noticeClass, method);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (attached) (*noticeVM)->DetachCurrentThread(noticeVM);
}
@interface VLKUpdateNoticeDelegate : NSObject <UNUserNotificationCenterDelegate>
@end
@implementation VLKUpdateNoticeDelegate
- (void)userNotificationCenter:(UNUserNotificationCenter *)center
  didReceiveNotificationResponse:(UNNotificationResponse *)response
  withCompletionHandler:(void (^)(void))completion {
    if ([response.notification.request.identifier hasPrefix:@"veilark-update-"] &&
        [response.actionIdentifier isEqualToString:UNNotificationDefaultActionIdentifier]) {
        notifyJava(noticeOpened, 0, NO);
    }
    completion();
}
- (void)userNotificationCenter:(UNUserNotificationCenter *)center
  willPresentNotification:(UNNotification *)notification
  withCompletionHandler:(void (^)(UNNotificationPresentationOptions))completion {
    completion(UNNotificationPresentationOptionBanner | UNNotificationPresentationOptionList);
}
@end
static VLKUpdateNoticeDelegate *noticeDelegate;
JNIEXPORT jboolean JNICALL Java_app_veilark_macos_MacNativeChrome_postUpdateNotice
  (JNIEnv *env, jclass cls, jstring title, jstring body, jint build) {
    @autoreleasepool {
        if (build <= 0 || !title || !body || (*env)->GetStringLength(env, title) > 200 ||
            (*env)->GetStringLength(env, body) > 1000 ||
            ![NSBundle.mainBundle.bundleIdentifier isEqualToString:@"app.veilark.macos"]) return JNI_FALSE;
        NSString *caption = readString(env, title), *message = readString(env, body);
        if (!noticeVM) {
            if ((*env)->GetJavaVM(env, &noticeVM) != JNI_OK) return JNI_FALSE;
            noticeClass = (*env)->NewGlobalRef(env, cls);
            noticeOpened = (*env)->GetStaticMethodID(env, cls, "onUpdateNoticeOpened", "()V");
            noticePosted = (*env)->GetStaticMethodID(env, cls, "onUpdateNoticePosted", "(I)V");
            if (!noticeClass || !noticeOpened || !noticePosted) return JNI_FALSE;
        }
        dispatch_async(dispatch_get_main_queue(), ^{
            UNUserNotificationCenter *center = UNUserNotificationCenter.currentNotificationCenter;
            if (!noticeDelegate) noticeDelegate = [VLKUpdateNoticeDelegate new];
            if (center.delegate && center.delegate != noticeDelegate) return;
            center.delegate = noticeDelegate;
            [center requestAuthorizationWithOptions:UNAuthorizationOptionAlert completionHandler:^(BOOL granted, NSError *error) {
                if (!granted || error) return;
                UNMutableNotificationContent *content = [UNMutableNotificationContent new];
                content.title = caption; content.body = message;
                NSString *identifier = [NSString stringWithFormat:@"veilark-update-%d", build];
                UNNotificationRequest *request = [UNNotificationRequest requestWithIdentifier:identifier content:content trigger:nil];
                [center addNotificationRequest:request withCompletionHandler:^(NSError *failure) {
                    if (!failure) notifyJava(noticePosted, build, YES);
                }];
            }];
        });
        return JNI_TRUE;
    }
}

static NSTextField *label(NSString *text, CGFloat size, NSFontWeight weight) {
    NSTextField *field = [NSTextField labelWithString:text];
    field.font = [NSFont systemFontOfSize:size weight:weight];
    field.textColor = NSColor.labelColor;
    field.lineBreakMode = NSLineBreakByTruncatingTail;
    field.maximumNumberOfLines = 2;
    return field;
}

// Never trust a title alone: the 420x220 startup window also says "Veilark". Prefer the
// NSWindow pointer that Compose reports; otherwise require title plus the AWT frame.
static NSWindow *findTargetWindow(jlong windowHandle, NSString *title, NSRect awtBounds, jint *matchedBy) {
    NSArray<NSWindow *> *windows = NSApp.windows;
    if (windowHandle != 0) {
        for (NSWindow *candidate in windows) {
            if ((jlong)(intptr_t)(__bridge void *)candidate == windowHandle ||
                (jlong)(intptr_t)(__bridge void *)candidate.contentView == windowHandle) {
                *matchedBy = 1;
                return candidate;
            }
        }
    }
    NSScreen *primary = NSScreen.screens.firstObject;
    if (primary && awtBounds.size.width > 0 && awtBounds.size.height > 0) {
        // AWT: top-left origin on the primary screen. AppKit: bottom-left origin.
        NSRect expected = NSMakeRect(awtBounds.origin.x,
                                     NSMaxY(primary.frame) - awtBounds.origin.y - awtBounds.size.height,
                                     awtBounds.size.width, awtBounds.size.height);
        for (NSWindow *candidate in windows) {
            NSRect frame = candidate.frame;
            if (candidate.isVisible && [candidate.title isEqualToString:title] &&
                fabs(frame.origin.x - expected.origin.x) <= 2 && fabs(frame.origin.y - expected.origin.y) <= 2 &&
                fabs(frame.size.width - expected.size.width) <= 2 && fabs(frame.size.height - expected.size.height) <= 2) {
                *matchedBy = 2;
                return candidate;
            }
        }
    }
    NSWindow *only = nil;
    NSUInteger count = 0;
    for (NSWindow *candidate in windows) {
        if (candidate.isVisible && [candidate.title isEqualToString:title]) {
            only = candidate;
            count++;
        }
    }
    if (count == 1) {
        *matchedBy = 3;
        return only;
    }
    return nil;
}

static NSButton *toolbarButton(NSString *title, NSString *symbol, VLKSidebar *target, SEL action, BOOL *glass) {
    NSButton *button = [NSButton buttonWithTitle:title target:target action:action];
    if (symbol) {
        button.image = [NSImage imageWithSystemSymbolName:symbol accessibilityDescription:nil];
        button.imagePosition = NSImageLeading;
    }
    button.controlSize = NSControlSizeLarge;
    button.font = [NSFont systemFontOfSize:NSFont.systemFontSize weight:NSFontWeightMedium];
    button.accessibilityLabel = title;
    *glass = applyGlassBezel(button) && *glass;
    return button;
}

static void installToolbar(VLKSidebar *sidebar, NSWindow *window, NSString *status, NSString *action, BOOL unified) {
    if (window.toolbar) {
        sidebar.toolbarMode = 0; // Someone else owns this window's toolbar; do not replace it.
        return;
    }
    BOOL glass = YES;
    sidebar.statusButton = toolbarButton(status, nil, sidebar, @selector(showOverview:), &glass);
    sidebar.actionButton = toolbarButton(action, @"power", sidebar, @selector(toggleConnection:), &glass);
    NSToolbar *toolbar = [[NSToolbar alloc] initWithIdentifier:@"app.veilark.macos.main"];
    toolbar.delegate = sidebar;
    toolbar.displayMode = NSToolbarDisplayModeIconOnly;
    toolbar.allowsUserCustomization = NO;
    toolbar.autosavesConfiguration = NO;
    sidebar.toolbar = toolbar;
    window.toolbar = toolbar;
    window.toolbarStyle = NSWindowToolbarStyleUnified;
    if (unified) {
        // Kotlin sets apple.awt.fullWindowContent/transparentTitleBar on the JRootPane
        // first, so AWT's own style bits agree with this and will not undo it later.
        sidebar.previousTitleVisibility = window.titleVisibility;
        sidebar.changedTitlebar = YES;
        window.styleMask |= NSWindowStyleMaskFullSizeContentView;
        window.titlebarAppearsTransparent = YES;
        window.titleVisibility = NSWindowTitleHidden;
    }
    sidebar.toolbarMode = glass ? 2 : 1;
}

JNIEXPORT jlong JNICALL Java_app_veilark_macos_MacNativeChrome_install
  (JNIEnv *env, jclass cls, jlong windowHandle, jstring title, jintArray bounds, jboolean unifiedTitlebar,
   jobjectArray labels, jbyteArray logo) {
    @autoreleasepool {
    if (!labels || (*env)->GetArrayLength(env, labels) != 9) return 0;
    if (!bounds || (*env)->GetArrayLength(env, bounds) != 4) return 0;
    jint rawBounds[4] = {0, 0, 0, 0};
    (*env)->GetIntArrayRegion(env, bounds, 0, 4, rawBounds);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return 0; }
    NSRect awtBounds = NSMakeRect(rawBounds[0], rawBounds[1], rawBounds[2], rawBounds[3]);
    NSString *windowTitle = readString(env, title);
    jsize logoLength = logo ? (*env)->GetArrayLength(env, logo) : 0;
    if (logoLength <= 0 || logoLength > 262144) return 0;
    NSMutableData *logoData = [NSMutableData dataWithLength:(NSUInteger)logoLength];
    (*env)->GetByteArrayRegion(env, logo, 0, logoLength, (jbyte *)logoData.mutableBytes);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return 0; }
    NSMutableArray<NSString *> *strings = [NSMutableArray arrayWithCapacity:9];
    for (int i = 0; i < 9; i++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, labels, i);
        [strings addObject:readString(env, value)];
        (*env)->DeleteLocalRef(env, value);
    }
    if (!bindCallbacks(env, cls)) return 0;
    BOOL unified = unifiedTitlebar == JNI_TRUE;
    __block jlong handle = 0;
    void (^install)(void) = ^{
        jint matchedBy = 0;
        NSWindow *window = findTargetWindow(windowHandle, windowTitle, awtBounds, &matchedBy);
        if (!window || !window.contentView) return;
        NSView *parent = window.contentView;
        VLKSidebar *sidebar = [VLKSidebar new];
        sidebar.window = window;
        sidebar.matchedBy = matchedBy;
        installToolbar(sidebar, window, strings[7], strings[8], unified);
        NSView *material;
        BOOL reduce = NSWorkspace.sharedWorkspace.accessibilityDisplayShouldReduceTransparency;
        // Runtime lookup keeps the same binary usable on pre-26 macOS/older build SDKs.
        Class glassClass = NSClassFromString(@"NSGlassEffectView");
        if (!reduce && glassClass && [glassClass instancesRespondToSelector:@selector(setContentView:)]) {
            material = [[glassClass alloc] initWithFrame:NSZeroRect];
            sidebar.materialMode = 2;
            if ([material respondsToSelector:@selector(setCornerRadius:)]) {
                // Concentric with the macOS 26 toolbar window corner (about 26 pt) at an 8 pt inset.
                ((void (*)(id, SEL, CGFloat))objc_msgSend)(material, @selector(setCornerRadius:), 18);
            }
        } else if (!reduce) {
            NSVisualEffectView *effect = [NSVisualEffectView new];
            effect.material = NSVisualEffectMaterialSidebar;
            effect.blendingMode = NSVisualEffectBlendingModeBehindWindow;
            effect.state = NSVisualEffectStateFollowsWindowActiveState;
            material = effect;
            sidebar.materialMode = 1;
        } else {
            material = [NSView new];
            sidebar.materialMode = 0;
            material.wantsLayer = YES;
            material.layer.backgroundColor = NSColor.windowBackgroundColor.CGColor;
        }
        material.translatesAutoresizingMaskIntoConstraints = NO;
        [parent addSubview:material positioned:NSWindowAbove relativeTo:nil];
        // With a full-size content view the sidebar runs under the transparent titlebar,
        // and the traffic lights sit inside the glass, as in macOS 26 Finder.
        [NSLayoutConstraint activateConstraints:@[
            [material.leadingAnchor constraintEqualToAnchor:parent.leadingAnchor constant:8],
            [material.topAnchor constraintEqualToAnchor:parent.topAnchor constant:8],
            [material.bottomAnchor constraintEqualToAnchor:parent.bottomAnchor constant:-8],
            [material.widthAnchor constraintEqualToConstant:224]
        ]];
        sidebar.view = material;

        VLKContentView *content = [VLKContentView new];
        content.owner = sidebar;
        content.translatesAutoresizingMaskIntoConstraints = NO;
        if (!reduce && glassClass && [material isKindOfClass:glassClass]) {
            ((void (*)(id, SEL, id))objc_msgSend)(material, @selector(setContentView:), content);
        } else {
            [material addSubview:content];
        }
        [NSLayoutConstraint activateConstraints:@[
            [content.leadingAnchor constraintEqualToAnchor:material.leadingAnchor],
            [content.trailingAnchor constraintEqualToAnchor:material.trailingAnchor],
            [content.topAnchor constraintEqualToAnchor:material.topAnchor],
            [content.bottomAnchor constraintEqualToAnchor:material.bottomAnchor]
        ]];
        NSStackView *stack = [NSStackView new];
        stack.orientation = NSUserInterfaceLayoutOrientationVertical;
        stack.alignment = NSLayoutAttributeLeading;
        stack.spacing = 6;
        stack.translatesAutoresizingMaskIntoConstraints = NO;
        [content addSubview:stack];
        id layoutGuide = window.contentLayoutGuide;
        NSLayoutYAxisAnchor *safeTop = [layoutGuide isKindOfClass:NSLayoutGuide.class]
            ? ((NSLayoutGuide *)layoutGuide).topAnchor : content.topAnchor;
        [NSLayoutConstraint activateConstraints:@[
            [stack.leadingAnchor constraintEqualToAnchor:content.leadingAnchor constant:12],
            [stack.trailingAnchor constraintEqualToAnchor:content.trailingAnchor constant:-12],
            [stack.topAnchor constraintGreaterThanOrEqualToAnchor:content.topAnchor constant:20],
            [stack.topAnchor constraintGreaterThanOrEqualToAnchor:safeTop constant:10],
            [stack.bottomAnchor constraintEqualToAnchor:content.bottomAnchor constant:-16]
        ]];
        NSLayoutConstraint *pinTop = [stack.topAnchor constraintEqualToAnchor:content.topAnchor constant:20];
        pinTop.priority = NSLayoutPriorityDefaultLow;
        pinTop.active = YES;
        NSImage *brand = [[NSImage alloc] initWithData:logoData];
        brand.template = YES;
        NSImageView *brandView = [NSImageView imageViewWithImage:brand];
        [brandView.widthAnchor constraintEqualToConstant:26].active = YES;
        [brandView.heightAnchor constraintEqualToConstant:26].active = YES;
        NSStackView *brandRow = [NSStackView stackViewWithViews:@[brandView, label(@"Veilark", 20, NSFontWeightSemibold)]];
        brandRow.spacing = 10;
        [stack addArrangedSubview:brandRow];
        NSTextField *subtitle = label(strings[5], 11, NSFontWeightRegular);
        subtitle.textColor = NSColor.secondaryLabelColor;
        [stack addArrangedSubview:subtitle];
        sidebar.status = label(strings[7], 12, NSFontWeightMedium);
        [stack setCustomSpacing:22 afterView:subtitle];
        [stack addArrangedSubview:sidebar.status];
        [stack setCustomSpacing:18 afterView:sidebar.status];
        NSArray *symbols = @[@"house", @"network", @"point.topleft.down.to.point.bottomright.curvepath",
                             @"waveform.path.ecg", @"gearshape"];
        NSMutableArray *buttons = [NSMutableArray new];
        for (NSInteger i = 0; i < 5; i++) {
            NSButton *button = [NSButton new];
            button.cell = [VLKSidebarButtonCell new];
            button.title = [@"  " stringByAppendingString:strings[i]];
            button.target = sidebar;
            button.action = @selector(navigate:);
            button.tag = i;
            button.bordered = NO;
            [button setButtonType:NSButtonTypePushOnPushOff];
            button.alignment = NSTextAlignmentLeft;
            button.font = [NSFont systemFontOfSize:13 weight:NSFontWeightRegular];
            button.image = [NSImage imageWithSystemSymbolName:symbols[i] accessibilityDescription:nil];
            button.image = [button.image imageWithSymbolConfiguration:
                [NSImageSymbolConfiguration configurationWithPointSize:15 weight:NSFontWeightMedium]];
            button.imagePosition = NSImageLeft;
            button.imageScaling = NSImageScaleProportionallyDown;
            button.keyEquivalent = [NSString stringWithFormat:@"%ld", (long)i + 1];
            button.keyEquivalentModifierMask = NSEventModifierFlagCommand;
            button.wantsLayer = YES;
            button.layer.cornerRadius = 8;
            button.accessibilityLabel = strings[i];
            [stack addArrangedSubview:button];
            [button.widthAnchor constraintEqualToAnchor:stack.widthAnchor].active = YES;
            [button.heightAnchor constraintEqualToConstant:36].active = YES;
            [buttons addObject:button];
        }
        sidebar.buttons = buttons;
        __weak VLKSidebar *weakSidebar = sidebar;
        sidebar.colorObserver = [NSNotificationCenter.defaultCenter
            addObserverForName:NSSystemColorsDidChangeNotification object:nil queue:NSOperationQueue.mainQueue
            usingBlock:^(NSNotification *note) { [weakSidebar refreshColors]; }];
        [sidebar refreshColors];
        NSView *spacer = [NSView new];
        [spacer setContentHuggingPriority:1 forOrientation:NSLayoutConstraintOrientationVertical];
        [stack addArrangedSubview:spacer];
        NSTextField *hint = label(strings[6], 10, NSFontWeightRegular);
        hint.textColor = NSColor.secondaryLabelColor;
        [stack addArrangedSubview:hint];
        for (NSTextField *field in @[subtitle, sidebar.status, hint]) {
            [field.widthAnchor constraintLessThanOrEqualToAnchor:stack.widthAnchor].active = YES;
        }
        if (!sidebars) sidebars = [NSMutableDictionary new];
        handle = (jlong)nextHandle++;
        sidebars[@(handle)] = sidebar;
    };
    if (!onMainWithin(2.0, install)) return 0;
    return handle;
    }
}

static void removeSidebar(VLKSidebar *sidebar) {
    [sidebar.view removeFromSuperview];
    NSWindow *window = sidebar.window;
    if (window && sidebar.toolbar && window.toolbar == sidebar.toolbar) {
        window.toolbar = nil;
    }
    if (window && sidebar.changedTitlebar) {
        window.titleVisibility = sidebar.previousTitleVisibility;
        window.titlebarAppearsTransparent = NO;
        window.styleMask &= ~NSWindowStyleMaskFullSizeContentView;
    }
    sidebar.toolbar.delegate = nil;
}

JNIEXPORT void JNICALL Java_app_veilark_macos_MacNativeChrome_update
  (JNIEnv *env, jclass cls, jlong handle, jint selected, jstring status, jstring action, jboolean actionEnabled) {
    @autoreleasepool {
    NSString *text = readString(env, status);
    NSString *actionText = readString(env, action);
    dispatch_async(dispatch_get_main_queue(), ^{
        VLKSidebar *sidebar = sidebars[@(handle)];
        if (!sidebar) return;
        BOOL changed = sidebar.selectedIndex != selected;
        BOOL reduce = NSWorkspace.sharedWorkspace.accessibilityDisplayShouldReduceMotion;
        // One bounded transition per confirmed section change, never an idle loop.
        if (changed && !reduce) {
            for (NSButton *button in sidebar.buttons) {
                CABasicAnimation *transition = [CABasicAnimation animationWithKeyPath:@"backgroundColor"];
                transition.fromValue = (__bridge id)(button.layer.presentationLayer ?: button.layer).backgroundColor;
                BOOL active = button.tag == selected;
                transition.toValue = (__bridge id)(active ? [NSColor.controlAccentColor colorWithAlphaComponent:0.12].CGColor : NSColor.clearColor.CGColor);
                transition.duration = 0.14;
                transition.timingFunction = [CAMediaTimingFunction functionWithName:kCAMediaTimingFunctionEaseOut];
                [button.layer addAnimation:transition forKey:@"selection"];
            }
        } else if (reduce) {
            for (NSButton *button in sidebar.buttons) [button.layer removeAnimationForKey:@"selection"];
        }
        sidebar.status.stringValue = text;
        sidebar.selectedIndex = selected;
        if (sidebar.statusButton) {
            sidebar.statusButton.title = text;
            sidebar.statusButton.accessibilityLabel = text;
        }
        if (sidebar.actionButton && actionText.length > 0) {
            sidebar.actionButton.title = actionText;
            sidebar.actionButton.accessibilityLabel = actionText;
            sidebar.actionButton.enabled = actionEnabled == JNI_TRUE;
        }
        for (NSToolbarItem *item in sidebar.toolbar.items) {
            if ([item.itemIdentifier isEqualToString:VLKStatusItem]) item.label = item.toolTip = text;
            if ([item.itemIdentifier isEqualToString:VLKActionItem] && actionText.length > 0) item.label = item.toolTip = actionText;
        }
        [sidebar refreshColors];
    });
    }
}

JNIEXPORT void JNICALL Java_app_veilark_macos_MacNativeChrome_remove
  (JNIEnv *env, jclass cls, jlong handle) {
    dispatch_async(dispatch_get_main_queue(), ^{
        VLKSidebar *sidebar = sidebars[@(handle)];
        if (sidebar) removeSidebar(sidebar);
        [sidebars removeObjectForKey:@(handle)];
    });
}

JNIEXPORT jint JNICALL Java_app_veilark_macos_MacNativeChrome_materialMode
  (JNIEnv *env, jclass cls, jlong handle) {
    __block jint result = -1;
    if (!onMainWithin(1.0, ^{
        VLKSidebar *sidebar = sidebars[@(handle)];
        if (sidebar) result = sidebar.materialMode;
    })) return -1;
    return result;
}

// {material, toolbar, matchedBy, top inset of the content layout area in points,
//  window width, window height}; NULL when detached or the main thread is unavailable.
JNIEXPORT jintArray JNICALL Java_app_veilark_macos_MacNativeChrome_nativeState
  (JNIEnv *env, jclass cls, jlong handle) {
    __block struct { jint values[6]; } state = {{-1, 0, 0, 0, 0, 0}};
    __block BOOL found = NO;
    if (!onMainWithin(1.0, ^{
        VLKSidebar *sidebar = sidebars[@(handle)];
        NSWindow *window = sidebar.window;
        if (!sidebar || !window) return;
        found = YES;
        NSView *content = window.contentView;
        CGFloat inset = content ? NSMaxY(content.frame) - NSMaxY(window.contentLayoutRect) : 0;
        state.values[0] = sidebar.materialMode;
        state.values[1] = sidebar.toolbarMode;
        state.values[2] = sidebar.matchedBy;
        state.values[3] = (jint)MAX(0, ceil(inset));
        state.values[4] = (jint)lround(window.frame.size.width);
        state.values[5] = (jint)lround(window.frame.size.height);
    }) || !found) return NULL;
    jintArray result = (*env)->NewIntArray(env, 6);
    if (!result) { (*env)->ExceptionClear(env); return NULL; }
    jint copy[6];
    for (int i = 0; i < 6; i++) copy[i] = state.values[i];
    (*env)->SetIntArrayRegion(env, result, 0, 6, copy);
    return result;
}

// Bit mask 1 = Reduce Motion, 2 = Reduce Transparency, 4 = Increase Contrast; -1 = unknown.
// The first call also subscribes to live NSWorkspace accessibility display changes.
JNIEXPORT jint JNICALL Java_app_veilark_macos_MacNativeChrome_observeDisplayPreferences
  (JNIEnv *env, jclass cls) {
    if (!bindCallbacks(env, cls)) return -1;
    __block jint mask = -1;
    if (!onMainWithin(0.5, ^{
        if (!displayObserver) {
            displayObserver = [NSWorkspace.sharedWorkspace.notificationCenter
                addObserverForName:NSWorkspaceAccessibilityDisplayOptionsDidChangeNotification object:nil
                queue:NSOperationQueue.mainQueue usingBlock:^(NSNotification *note) {
                    callJava(displayMethod, currentDisplayMask(), 0, NO);
                }];
        }
        mask = currentDisplayMask();
    })) return -1;
    return mask;
}

// Native confirmation sheet on the window that owns `handle`. The answer arrives through
// onConfirmResult(requestId, 1 confirmed | 0 cancelled | -1 not shown, use Compose).
JNIEXPORT jboolean JNICALL Java_app_veilark_macos_MacNativeChrome_confirm
  (JNIEnv *env, jclass cls, jlong handle, jint requestId, jstring title, jstring message,
   jstring confirmTitle, jstring cancelTitle, jboolean destructive) {
    @autoreleasepool {
        if (handle == 0 || requestId <= 0 || !title || !message || !confirmTitle || !cancelTitle) return JNI_FALSE;
        if ((*env)->GetStringLength(env, title) > 200 || (*env)->GetStringLength(env, message) > 1000 ||
            (*env)->GetStringLength(env, confirmTitle) > 80 || (*env)->GetStringLength(env, cancelTitle) > 80) {
            return JNI_FALSE;
        }
        if (!bindCallbacks(env, cls)) return JNI_FALSE;
        NSString *caption = readString(env, title), *body = readString(env, message);
        NSString *accept = readString(env, confirmTitle), *reject = readString(env, cancelTitle);
        BOOL isDestructive = destructive == JNI_TRUE;
        dispatch_async(dispatch_get_main_queue(), ^{
            VLKSidebar *sidebar = sidebars[@(handle)];
            NSWindow *window = sidebar.window;
            if (!sidebar || !window || !window.isVisible || window.attachedSheet) {
                callJava(confirmMethod, requestId, -1, YES);
                return;
            }
            NSAlert *alert = [NSAlert new];
            alert.alertStyle = NSAlertStyleWarning;
            alert.messageText = caption;
            alert.informativeText = body;
            NSButton *acceptButton = [alert addButtonWithTitle:accept];
            NSButton *rejectButton = [alert addButtonWithTitle:reject];
            if (isDestructive) {
                // Destructive action is never the Return-key default.
                acceptButton.hasDestructiveAction = YES;
                acceptButton.keyEquivalent = @"";
                rejectButton.keyEquivalent = @"\r";
            }
            [alert beginSheetModalForWindow:window completionHandler:^(NSModalResponse response) {
                callJava(confirmMethod, requestId, response == NSAlertFirstButtonReturn ? 1 : 0, YES);
            }];
        });
        return JNI_TRUE;
    }
}
