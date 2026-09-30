#import <Cocoa/Cocoa.h>
#import <QuartzCore/QuartzCore.h>
#import <objc/message.h>
#include <jni.h>

// AppKit owns layout, SF Symbols, focus, vibrancy and accessibility. JNI transports
// only section indices and public UI labels; tunnel operations stay in the JVM.
static JavaVM *vm;
static jclass callbackClass;
static jmethodID navigateMethod;
static jmethodID displayMethod;
static NSMutableDictionary<NSNumber *, id> *sidebars;
static uint64_t nextHandle = 1;

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

@interface VLKSidebar : NSObject
@property(nonatomic, strong) NSView *view;
@property(nonatomic, strong) NSTextField *status;
@property(nonatomic, strong) NSArray<NSButton *> *buttons;
@property(nonatomic, strong) id accessibilityObserver;
@property(nonatomic, strong) id colorObserver;
@property(nonatomic) NSInteger selectedIndex;
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
    if (_accessibilityObserver) [NSWorkspace.sharedWorkspace.notificationCenter removeObserver:_accessibilityObserver];
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
    JNIEnv *env = NULL;
    BOOL attached = NO;
    jint result = (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8);
    if (result == JNI_EDETACHED) {
        if ((*vm)->AttachCurrentThread(vm, (void **)&env, NULL) != JNI_OK) return;
        attached = YES;
    } else if (result != JNI_OK) return;
    (*env)->CallStaticVoidMethod(env, callbackClass, navigateMethod, (jint)sender.tag);
    // Do not let a Java exception escape into AppKit's event loop.
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (attached) (*vm)->DetachCurrentThread(vm);
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

static NSTextField *label(NSString *text, CGFloat size, NSFontWeight weight) {
    NSTextField *field = [NSTextField labelWithString:text];
    field.font = [NSFont systemFontOfSize:size weight:weight];
    field.textColor = NSColor.labelColor;
    field.lineBreakMode = NSLineBreakByTruncatingTail;
    field.maximumNumberOfLines = 2;
    return field;
}

JNIEXPORT jlong JNICALL Java_app_veilark_macos_MacNativeChrome_install
  (JNIEnv *env, jclass cls, jstring title, jobjectArray labels, jbyteArray logo) {
    @autoreleasepool {
    if ((*env)->GetArrayLength(env, labels) != 8) return 0;
    NSString *windowTitle = readString(env, title);
    jsize logoLength = logo ? (*env)->GetArrayLength(env, logo) : 0;
    if (logoLength <= 0 || logoLength > 262144) return 0;
    NSMutableData *logoData = [NSMutableData dataWithLength:(NSUInteger)logoLength];
    (*env)->GetByteArrayRegion(env, logo, 0, logoLength, (jbyte *)logoData.mutableBytes);
    if ((*env)->ExceptionCheck(env)) return 0;
    NSMutableArray<NSString *> *strings = [NSMutableArray arrayWithCapacity:8];
    for (int i = 0; i < 8; i++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, labels, i);
        [strings addObject:readString(env, value)];
        (*env)->DeleteLocalRef(env, value);
    }
    if (!vm) {
        if ((*env)->GetJavaVM(env, &vm) != JNI_OK) return 0;
        callbackClass = (*env)->NewGlobalRef(env, cls);
        navigateMethod = (*env)->GetStaticMethodID(env, cls, "onNavigate", "(I)V");
        displayMethod = (*env)->GetStaticMethodID(env, cls, "onDisplayPreferencesChanged", "()V");
        if (!callbackClass || !navigateMethod || !displayMethod) return 0;
    }
    __block jlong handle = 0;
    void (^install)(void) = ^{
        NSWindow *window = nil;
        for (NSWindow *candidate in NSApp.windows) {
            if ([candidate.title isEqualToString:windowTitle] && candidate.isVisible) {
                window = candidate;
                break;
            }
        }
        if (!window || !window.contentView) return;
        NSView *parent = window.contentView;
        VLKSidebar *sidebar = [VLKSidebar new];
        NSView *material;
        BOOL reduce = NSWorkspace.sharedWorkspace.accessibilityDisplayShouldReduceTransparency;
        // Runtime lookup keeps the same binary usable on pre-26 macOS/older build SDKs.
        Class glassClass = NSClassFromString(@"NSGlassEffectView");
        if (!reduce && glassClass && [glassClass instancesRespondToSelector:@selector(setContentView:)]) {
            material = [[glassClass alloc] initWithFrame:NSZeroRect];
            if ([material respondsToSelector:@selector(setCornerRadius:)]) {
                ((void (*)(id, SEL, CGFloat))objc_msgSend)(material, @selector(setCornerRadius:), 16);
            }
        } else if (!reduce) {
            NSVisualEffectView *effect = [NSVisualEffectView new];
            effect.material = NSVisualEffectMaterialSidebar;
            effect.blendingMode = NSVisualEffectBlendingModeBehindWindow;
            effect.state = NSVisualEffectStateFollowsWindowActiveState;
            material = effect;
        } else {
            material = [NSView new];
            material.wantsLayer = YES;
            material.layer.backgroundColor = NSColor.windowBackgroundColor.CGColor;
        }
        material.translatesAutoresizingMaskIntoConstraints = NO;
        [parent addSubview:material positioned:NSWindowAbove relativeTo:nil];
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
        [NSLayoutConstraint activateConstraints:@[
            [stack.leadingAnchor constraintEqualToAnchor:content.leadingAnchor constant:12],
            [stack.trailingAnchor constraintEqualToAnchor:content.trailingAnchor constant:-12],
            [stack.topAnchor constraintEqualToAnchor:content.topAnchor constant:20],
            [stack.bottomAnchor constraintEqualToAnchor:content.bottomAnchor constant:-16]
        ]];
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
        sidebar.accessibilityObserver = [NSWorkspace.sharedWorkspace.notificationCenter
            addObserverForName:NSWorkspaceAccessibilityDisplayOptionsDidChangeNotification object:nil
            queue:NSOperationQueue.mainQueue usingBlock:^(NSNotification *note) {
                if (!weakSidebar) return;
                JNIEnv *callbackEnv = NULL;
                BOOL attached = NO;
                jint result = (*vm)->GetEnv(vm, (void **)&callbackEnv, JNI_VERSION_1_8);
                if (result == JNI_EDETACHED) {
                    if ((*vm)->AttachCurrentThread(vm, (void **)&callbackEnv, NULL) != JNI_OK) return;
                    attached = YES;
                } else if (result != JNI_OK) return;
                (*callbackEnv)->CallStaticVoidMethod(callbackEnv, callbackClass, displayMethod);
                if ((*callbackEnv)->ExceptionCheck(callbackEnv)) (*callbackEnv)->ExceptionClear(callbackEnv);
                if (attached) (*vm)->DetachCurrentThread(vm);
            }];
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
    if (NSThread.isMainThread) install(); else dispatch_sync(dispatch_get_main_queue(), install);
    return handle;
    }
}

JNIEXPORT void JNICALL Java_app_veilark_macos_MacNativeChrome_update
  (JNIEnv *env, jclass cls, jlong handle, jint selected, jstring status) {
    @autoreleasepool {
    NSString *text = readString(env, status);
    dispatch_async(dispatch_get_main_queue(), ^{
        VLKSidebar *sidebar = sidebars[@(handle)];
        if (!sidebar) return;
        sidebar.status.stringValue = text;
        sidebar.selectedIndex = selected;
        [sidebar refreshColors];
    });
    }
}

JNIEXPORT void JNICALL Java_app_veilark_macos_MacNativeChrome_remove
  (JNIEnv *env, jclass cls, jlong handle) {
    dispatch_async(dispatch_get_main_queue(), ^{
        VLKSidebar *sidebar = sidebars[@(handle)];
        [sidebar.view removeFromSuperview];
        [sidebars removeObjectForKey:@(handle)];
    });
}
