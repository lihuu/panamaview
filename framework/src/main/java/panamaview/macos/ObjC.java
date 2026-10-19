package panamaview.macos;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Direct binding to the Objective-C runtime, built only on {@code java.lang.foreign}.
 *
 * <p>There is no JNI, no JNA, no shim library: {@code objc_msgSend} is resolved out of
 * {@code libobjc.A.dylib} and re-typed per call site with a {@link FunctionDescriptor}.
 * That trick is what makes the whole approach viable — Objective-C dispatch is a plain
 * C function pointer, so FFM can call it directly.
 *
 * <h2>Threading</h2>
 * AppKit requires its event loop to own the process' <em>main</em> thread. The JVM does
 * not give you that by default (the {@code java} launcher hands {@code main()} to a
 * freshly created thread, which is why {@code -XstartOnFirstThread} exists). Every
 * method here assumes it is running on that thread; {@link #onMainThread()} reports
 * whether the assumption holds.
 *
 * <h2>Memory</h2>
 * Objective-C objects returned from a method that is not {@code alloc}/{@code new}/
 * {@code copy} are autoreleased. Java has no way to participate in that, so transient
 * values must be produced inside an autorelease pool — see {@link #scope()}. Objects
 * that must outlive the pool are given an explicit {@code retain}.
 */
final class ObjC {

    private ObjC() {
    }

    public static final Linker LINKER = Linker.nativeLinker();
    private static final Arena GLOBAL = Arena.global();
    private static final SymbolLookup SYSTEM = LINKER.defaultLookup();

    /** {@code NSRect}/{@code CGRect} — {@code {NSPoint origin; NSSize size;}} = four doubles. */
    public static final MemoryLayout NSRECT = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("x"),
            JAVA_DOUBLE.withName("y"),
            JAVA_DOUBLE.withName("width"),
            JAVA_DOUBLE.withName("height"));

    /** {@code NSSize}/{@code CGSize} — {@code {CGFloat width; CGFloat height;}} = two doubles. */
    public static final MemoryLayout NSSIZE = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("width"),
            JAVA_DOUBLE.withName("height"));

    /** {@code NSPoint}/{@code CGPoint} — {@code {CGFloat x; CGFloat y;}} = two doubles. */
    public static final MemoryLayout NSPOINT = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("x"),
            JAVA_DOUBLE.withName("y"));

    private static final int RTLD_NOW = 0x2;
    private static final int RTLD_GLOBAL = 0x8;

    private static final MethodHandle DLOPEN;
    private static final MethodHandle DLERROR;
    private static final SymbolLookup OBJC;
    private static final MethodHandle GET_CLASS;
    private static final MethodHandle SEL_REGISTER_NAME;
    private static final MethodHandle ALLOCATE_CLASS_PAIR;
    private static final MethodHandle REGISTER_CLASS_PAIR;
    private static final MethodHandle CLASS_ADD_METHOD;
    private static final MethodHandle AUTORELEASE_POOL_PUSH;
    private static final MethodHandle AUTORELEASE_POOL_POP;
    private static final MethodHandle PTHREAD_MAIN_NP;
    private static final MemorySegment MSG_SEND;

    static {
        try {
            DLOPEN = bind(SYSTEM, "dlopen", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
            DLERROR = bind(SYSTEM, "dlerror", FunctionDescriptor.of(ADDRESS));

            OBJC = loadLibrary("/usr/lib/libobjc.A.dylib");
            GET_CLASS = bind(OBJC, "objc_getClass", FunctionDescriptor.of(ADDRESS, ADDRESS));
            SEL_REGISTER_NAME = bind(OBJC, "sel_registerName", FunctionDescriptor.of(ADDRESS, ADDRESS));
            ALLOCATE_CLASS_PAIR = bind(OBJC, "objc_allocateClassPair",
                    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
            REGISTER_CLASS_PAIR = bind(OBJC, "objc_registerClassPair", FunctionDescriptor.ofVoid(ADDRESS));
            CLASS_ADD_METHOD = bind(OBJC, "class_addMethod",
                    FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            AUTORELEASE_POOL_PUSH = bind(OBJC, "objc_autoreleasePoolPush", FunctionDescriptor.of(ADDRESS));
            AUTORELEASE_POOL_POP = bind(OBJC, "objc_autoreleasePoolPop", FunctionDescriptor.ofVoid(ADDRESS));
            PTHREAD_MAIN_NP = bind(SYSTEM, "pthread_main_np", FunctionDescriptor.of(JAVA_INT));

            MSG_SEND = OBJC.find("objc_msgSend")
                    .orElseThrow(() -> new UnsatisfiedLinkError("objc_msgSend"));

            // Loading a framework is what registers its classes with the runtime, so
            // objc_getClass() can only see NSApplication/WKWebView after this point.
            loadLibrary("/System/Library/Frameworks/Foundation.framework/Foundation");
            loadLibrary("/System/Library/Frameworks/AppKit.framework/AppKit");
            loadLibrary("/System/Library/Frameworks/WebKit.framework/WebKit");
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    private static MethodHandle bind(SymbolLookup lookup, String name, FunctionDescriptor fd) {
        MemorySegment address = lookup.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError(name + " not found"));
        return LINKER.downcallHandle(address, fd);
    }

    /** Loads a framework so its classes exist for {@code objc_getClass}. Package-private for the lazily loaded ones. */
    static SymbolLookup loadLibrary(String path) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment handle = (MemorySegment) DLOPEN.invoke(arena.allocateFrom(path), RTLD_NOW | RTLD_GLOBAL);
            if (isNull(handle)) {
                throw new UnsatisfiedLinkError("dlopen failed: " + path + ": " + cString((MemorySegment) DLERROR.invoke()));
            }
        } catch (Throwable t) {
            throw new IllegalStateException("cannot load " + path, t);
        }
        return SymbolLookup.libraryLookup(path, GLOBAL);
    }

    // ---------------------------------------------------------------- basics

    public static boolean isNull(MemorySegment p) {
        return p == null || p.address() == 0;
    }

    /** True when the calling thread is the process' first thread — the one AppKit insists on. */
    public static boolean onMainThread() {
        try {
            return ((Integer) PTHREAD_MAIN_NP.invoke()) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String cString(MemorySegment p) {
        return isNull(p) ? "" : p.reinterpret(Long.MAX_VALUE).getString(0);
    }

    private static final Map<String, MemorySegment> CLASSES = new ConcurrentHashMap<>();
    private static final Map<String, MemorySegment> SELECTORS = new ConcurrentHashMap<>();

    /** {@code objc_getClass} — nil when the name is unknown (usually: framework not loaded). */
    public static MemorySegment classNamed(String name) {
        return CLASSES.computeIfAbsent(name, n -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment cls = (MemorySegment) GET_CLASS.invoke(arena.allocateFrom(n));
                if (isNull(cls)) {
                    throw new IllegalStateException("no such Objective-C class: " + n);
                }
                return cls;
            } catch (Throwable t) {
                throw new IllegalStateException("objc_getClass(" + n + ")", t);
            }
        });
    }

    /**
     * {@code sel_registerName}.
     *
     * <p>Selectors are interned for the lifetime of the process and never freed, so
     * caching the address is not an optimisation with a lifetime caveat — it is exactly
     * the same pointer {@code sel_registerName} would hand back anyway.
     */
    public static MemorySegment selector(String name) {
        return SELECTORS.computeIfAbsent(name, n -> {
            try (Arena arena = Arena.ofConfined()) {
                return (MemorySegment) SEL_REGISTER_NAME.invoke(arena.allocateFrom(n));
            } catch (Throwable t) {
                throw new IllegalStateException("sel_registerName(" + n + ")", t);
            }
        });
    }

    // ------------------------------------------------------- autorelease pools

    /**
     * An autorelease pool plus a scratch arena, for one Java→Objective-C operation.
     *
     * <p>Everything Objective-C allocates transiently during the block is released when
     * the scope closes, and every C string Java handed over stops being valid. Objects
     * that must survive are retained explicitly.
     */
    public static final class Scope implements AutoCloseable {
        private final MemorySegment pool;
        private final Arena arena;
        private boolean closed;

        private Scope(MemorySegment pool, Arena arena) {
            this.pool = pool;
            this.arena = arena;
        }

        public Arena arena() {
            return arena;
        }

        public MemorySegment utf8(String s) {
            return arena.allocateFrom(s);
        }

        public MemorySegment size(double width, double height) {
            MemorySegment s = arena.allocate(NSSIZE);
            s.set(JAVA_DOUBLE, 0, width);
            s.set(JAVA_DOUBLE, 8, height);
            return s;
        }

        public MemorySegment point(double x, double y) {
            MemorySegment p = arena.allocate(NSPOINT);
            p.set(JAVA_DOUBLE, 0, x);
            p.set(JAVA_DOUBLE, 8, y);
            return p;
        }

        public MemorySegment rect(double x, double y, double w, double h) {
            MemorySegment r = arena.allocate(NSRECT);
            r.set(JAVA_DOUBLE, 0, x);
            r.set(JAVA_DOUBLE, 8, y);
            r.set(JAVA_DOUBLE, 16, w);
            r.set(JAVA_DOUBLE, 24, h);
            return r;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                AUTORELEASE_POOL_POP.invoke(pool);
            } catch (Throwable ignored) {
                // draining must never mask the real failure
            }
            arena.close();
        }
    }

    public static Scope scope() {
        try {
            return new Scope((MemorySegment) AUTORELEASE_POOL_PUSH.invoke(), Arena.ofConfined());
        } catch (Throwable t) {
            throw new IllegalStateException("cannot push autorelease pool", t);
        }
    }

    // ----------------------------------------------------------- message send

    private static final Map<FunctionDescriptor, MethodHandle> DISPATCH = new ConcurrentHashMap<>();

    private static final FunctionDescriptor ID0 = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor ID1 = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor ID2 = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor ID3 = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor VOID0 = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);
    private static final FunctionDescriptor VOID1 = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor VOID2 = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor VOID3 = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor BOOL1 = FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, JAVA_BYTE);
    /** {@code BOOL} out, one object in — {@code setFrameUsingName:}, {@code setFrameAutosaveName:}. */
    private static final FunctionDescriptor BOOL_OBJ = FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, ADDRESS);
    /** receiver + selector + object + NSInteger + object + object — {@code NSHTTPURLResponse}'s designated init. */
    private static final FunctionDescriptor ID_OBJ_LONG_OBJ_OBJ =
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS);
    private static final FunctionDescriptor LONG0 = FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS);
    private static final FunctionDescriptor ID_OBJ_LONG = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG);
    private static final FunctionDescriptor VOID_BOOL = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_BYTE);
    private static final FunctionDescriptor VOID_LONG = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG);
    private static final FunctionDescriptor VOID_RECT = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, NSRECT);
    private static final FunctionDescriptor VOID_SIZE = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, NSSIZE);
    private static final FunctionDescriptor VOID_POINT = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, NSPOINT);
    private static final FunctionDescriptor RECT0 = FunctionDescriptor.of(NSRECT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor ID_RECT_LONG_LONG_BOOL =
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, NSRECT, JAVA_LONG, JAVA_LONG, JAVA_BYTE);
    private static final FunctionDescriptor ID_RECT_ID =
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, NSRECT, ADDRESS);

    private static MethodHandle dispatch(FunctionDescriptor fd) {
        return DISPATCH.computeIfAbsent(fd, f -> LINKER.downcallHandle(MSG_SEND, f));
    }

    private static Object call(FunctionDescriptor fd, Object... args) {
        try {
            return dispatch(fd).invokeWithArguments(args);
        } catch (Throwable t) {
            // The signature is the useful part of the message: when a foreign call fails,
            // "which descriptor" is almost always the answer.
            throw new IllegalStateException("objc_msgSend failed for " + fd, t);
        }
    }

    public static MemorySegment send(MemorySegment receiver, MemorySegment sel) {
        return (MemorySegment) call(ID0, receiver, sel);
    }

    public static MemorySegment send(MemorySegment receiver, MemorySegment sel, MemorySegment a) {
        return (MemorySegment) call(ID1, receiver, sel, a);
    }

    public static MemorySegment send(MemorySegment receiver, MemorySegment sel, MemorySegment a, MemorySegment b) {
        return (MemorySegment) call(ID2, receiver, sel, a, b);
    }

    public static MemorySegment send(MemorySegment receiver, MemorySegment sel,
                                     MemorySegment a, MemorySegment b, MemorySegment c) {
        return (MemorySegment) call(ID3, receiver, sel, a, b, c);
    }

    public static void sendVoid(MemorySegment receiver, MemorySegment sel) {
        call(VOID0, receiver, sel);
    }

    public static void sendVoid(MemorySegment receiver, MemorySegment sel, MemorySegment a) {
        call(VOID1, receiver, sel, a);
    }

    public static void sendVoid(MemorySegment receiver, MemorySegment sel, MemorySegment a, MemorySegment b) {
        call(VOID2, receiver, sel, a, b);
    }

    public static void sendVoid(MemorySegment receiver, MemorySegment sel,
                                MemorySegment a, MemorySegment b, MemorySegment c) {
        call(VOID3, receiver, sel, a, b, c);
    }

    public static long sendLong(MemorySegment receiver, MemorySegment sel) {
        return (Long) call(LONG0, receiver, sel);
    }

    public static boolean sendBool(MemorySegment receiver, MemorySegment sel, boolean arg) {
        return ((Byte) call(BOOL1, receiver, sel, (byte) (arg ? 1 : 0))) != 0;
    }

    /**
     * A {@code BOOL}-returning message with one object argument.
     *
     * <p>The return value is the whole point of both callers: {@code setFrameUsingName:} answers
     * <em>whether a saved frame was found</em>, which is how the window knows whether centring it
     * would be a first launch or would undo the user's choice.
     */
    public static boolean sendBool(MemorySegment receiver, MemorySegment sel, MemorySegment object) {
        return ((Byte) call(BOOL_OBJ, receiver, sel, object)) != 0;
    }

    public static void sendVoidBool(MemorySegment receiver, MemorySegment sel, boolean arg) {
        call(VOID_BOOL, receiver, sel, (byte) (arg ? 1 : 0));
    }

    public static void sendVoidLong(MemorySegment receiver, MemorySegment sel, long arg) {
        call(VOID_LONG, receiver, sel, arg);
    }

    public static void sendVoidRect(MemorySegment receiver, MemorySegment sel, MemorySegment rect) {
        call(VOID_RECT, receiver, sel, rect);
    }

    /**
     * A setter taking a struct <em>by value</em> as an argument ({@code setContentSize:}).
     *
     * <p>Unlike a struct <em>return</em>, this needs no allocator: the caller owns the
     * storage and FFM reads it as it marshals the call. Two doubles is also a different
     * ABI case from a four-double NSRect — small enough to travel in registers — so both
     * shapes are worth having in the test set.
     */
    public static void sendVoidSize(MemorySegment receiver, MemorySegment sel, MemorySegment size) {
        call(VOID_SIZE, receiver, sel, size);
    }

    /** The same by-value-two-doubles shape for a position: {@code setFrameOrigin:}, {@code setFrameTopLeftPoint:}. */
    public static void sendVoidPoint(MemorySegment receiver, MemorySegment sel, MemorySegment point) {
        call(VOID_POINT, receiver, sel, point);
    }

    /**
     * A getter that returns a struct <em>by value</em> ({@code frame}, {@code bounds}, …).
     *
     * <p>FFM's rule for a by-value struct return is that the downcall handle grows an
     * extra <em>leading</em> {@link java.lang.foreign.SegmentAllocator} parameter: the
     * linker must be told where to place the returned aggregate rather than being allowed
     * to guess. Omitting it fails at call time with a {@code WrongMethodTypeException}
     * naming a signature you never wrote —
     * {@code (SegmentAllocator,MemorySegment,MemorySegment)MemorySegment}.
     *
     * <p>The scope's arena is the right allocator to pass, which ties the returned
     * struct's lifetime to the scope that read it. Note this rule applies to struct
     * returns only: a struct passed <em>as an argument</em> is just a {@code MemorySegment}
     * with no allocator involved (see {@code sendVoidRect}).
     */
    public static MemorySegment sendRect(ObjC.Scope scope, MemorySegment receiver, MemorySegment sel) {
        return (MemorySegment) call(RECT0, scope.arena(), receiver, sel);
    }

    /** {@code initWithContentRect:styleMask:backing:defer:} — a by-value NSRect plus two NSUIntegers and a BOOL. */
    public static MemorySegment sendRectStyleMaskBackingDefer(MemorySegment receiver, MemorySegment sel,
                                                              MemorySegment rect, long styleMask,
                                                              long backing, boolean defer) {
        return (MemorySegment) call(ID_RECT_LONG_LONG_BOOL,
                receiver, sel, rect, styleMask, backing, (byte) (defer ? 1 : 0));
    }

    /** {@code initWithFrame:configuration:} and friends — a by-value NSRect plus one object. */
    public static MemorySegment sendRectObject(MemorySegment receiver, MemorySegment sel,
                                               MemorySegment rect, MemorySegment object) {
        return (MemorySegment) call(ID_RECT_ID, receiver, sel, rect, object);
    }

    /**
     * A four-object message, or three objects around an {@code NSInteger}.
     *
     * <p>Four arguments is where hand-written bindings usually stop being written, and
     * {@code initWithURL:statusCode:HTTPVersion:headerFields:} is exactly that call — you cannot
     * synthesise an {@code NSHTTPURLResponse} without it, and without a real HTTP response a custom
     * URL scheme cannot serve a page.
     */
    public static MemorySegment send(MemorySegment receiver, MemorySegment sel,
                                     MemorySegment a, long b, MemorySegment c, MemorySegment d) {
        return (MemorySegment) call(ID_OBJ_LONG_OBJ_OBJ, receiver, sel, a, b, c, d);
    }

    /**
     * An object followed by an {@code NSInteger} — {@code dataWithBytes:length:} and the many
     * other Cocoa calls shaped that way.
     *
     * <p>Distinct from {@link #sendLongObject}, which is the same two arguments the other way
     * round. Objective-C selectors read as sentences, so the order is part of the signature and
     * getting it backwards is not a type error the compiler can catch.
     */
    public static MemorySegment sendObjectLong(MemorySegment receiver, MemorySegment sel,
                                               MemorySegment object, long value) {
        return (MemorySegment) call(ID_OBJ_LONG, receiver, sel, object, value);
    }

    /**
     * A void message with an {@code NSInteger} and an object —
     * {@code requestAuthorizationWithOptions:completionHandler:} is exactly that shape.
     */
    public static void sendVoidLongObject(MemorySegment receiver, MemorySegment sel,
                                          long value, MemorySegment object) {
        call(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS), receiver, sel, value, object);
    }

    /** {@code representationUsingType:properties:} — an NSUInteger plus an object. */
    public static MemorySegment sendLongObject(MemorySegment receiver, MemorySegment sel,
                                               long arg, MemorySegment object) {
        return (MemorySegment) call(FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS),
                receiver, sel, arg, object);
    }

    /** {@code writeToFile:atomically:} — an object plus a BOOL, answering with a BOOL. */
    public static boolean sendBool(MemorySegment receiver, MemorySegment sel,
                                   MemorySegment object, boolean flag) {
        return ((Byte) call(FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, ADDRESS, JAVA_BYTE),
                receiver, sel, object, (byte) (flag ? 1 : 0))) != 0;
    }

    // ---------------------------------------------------------------- objects

    /** {@code [NSString stringWithUTF8String:]} — autoreleased: only valid inside the current scope. */
    public static MemorySegment nsString(Scope scope, String value) {
        return send(classNamed("NSString"), selector("stringWithUTF8String:"), scope.utf8(value));
    }

    /** {@code [nsString UTF8String]} copied into a Java String. */
    public static String toJavaString(MemorySegment nsString) {
        if (isNull(nsString)) {
            return null;
        }
        return cString(send(nsString, selector("UTF8String")));
    }

    /** {@code [[clazz alloc] init]} — owned by the caller, outlives the autorelease pool. */
    public static MemorySegment newObject(String className) {
        MemorySegment cls = classNamed(className);
        return send(send(cls, selector("alloc")), selector("init"));
    }

    /** Bumps the retain count so the receiver survives the enclosing autorelease pool. */
    public static MemorySegment retain(MemorySegment object) {
        return send(object, selector("retain"));
    }

    // ------------------------------------------- runtime class creation (IMP → Java)

    /**
     * Defines a brand new Objective-C class whose methods are Java {@code MethodHandle}s.
     *
     * <p>This is the reverse direction of the bridge: Objective-C calls into Java without
     * any generated glue. Each method's implementation pointer is an FFM upcall stub —
     * still just a native function pointer, which is all the runtime ever wanted.
     *
     * @param name       the class name (must be globally unique)
     * @param superclass the superclass name, e.g. {@code NSObject}
     * @param methods    selector → (type encoding, upcall stub)
     */
    public static MemorySegment defineClass(String name, String superclass,
                                            Map<String, Method> methods) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cls = (MemorySegment) ALLOCATE_CLASS_PAIR.invoke(
                    classNamed(superclass), arena.allocateFrom(name), 0L);
            if (isNull(cls)) {
                throw new IllegalStateException("objc_allocateClassPair returned nil for " + name);
            }
            for (Map.Entry<String, Method> e : methods.entrySet()) {
                MemorySegment sel = selector(e.getKey());
                byte ok = (Byte) CLASS_ADD_METHOD.invoke(cls, sel, e.getValue().implementation(),
                        arena.allocateFrom(e.getValue().encoding()));
                if (ok == 0) {
                    throw new IllegalStateException("class_addMethod failed: [" + name + " " + e.getKey() + "]");
                }
            }
            REGISTER_CLASS_PAIR.invoke(cls);
            return cls;
        } catch (Throwable t) {
            throw new IllegalStateException("cannot define class " + name, t);
        }
    }

    /** A method implementation: a native type encoding plus the upcall stub to run. */
    public record Method(String encoding, MemorySegment implementation) {
    }

    /** Builds an upcall stub — a real C function pointer whose body is a Java method. */
    public static MemorySegment upcall(Class<?> owner, String method, FunctionDescriptor fd) {
        try {
            Class<?> returnType = fd.returnLayout().isPresent()
                    ? layoutClass(fd.returnLayout().get())
                    : void.class;
            MethodType type = MethodType.methodType(returnType,
                    fd.argumentLayouts().stream().map(ObjC::layoutClass).toList());
            MethodHandle target = MethodHandles.lookup().findStatic(owner, method, type);
            return LINKER.upcallStub(target, fd, GLOBAL);
        } catch (Throwable t) {
            throw new IllegalStateException("cannot create upcall stub " + owner + "." + method, t);
        }
    }

    /**
     * The Java carrier type of a native layout.
     *
     * <p>{@code MemorySegment} is the carrier for {@code ADDRESS}; the rest are fixed by
     * the FFM spec. Kept explicit because a wrong carrier silently produces a stub with
     * the wrong signature.
     */
    private static Class<?> layoutClass(MemoryLayout layout) {
        return switch (layout) {
            case java.lang.foreign.AddressLayout ignored -> MemorySegment.class;
            case java.lang.foreign.ValueLayout.OfByte ignored -> byte.class;
            case java.lang.foreign.ValueLayout.OfShort ignored -> short.class;
            case java.lang.foreign.ValueLayout.OfInt ignored -> int.class;
            case java.lang.foreign.ValueLayout.OfLong ignored -> long.class;
            case java.lang.foreign.ValueLayout.OfFloat ignored -> float.class;
            case java.lang.foreign.ValueLayout.OfDouble ignored -> double.class;
            default -> MemorySegment.class;
        };
    }
}
