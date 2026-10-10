package panamaview.macos;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Builds an Objective-C <em>block</em> in Java, by hand.
 *
 * <p>Plenty of Cocoa and WebKit APIs are block-based rather than delegate-based
 * ({@code takeSnapshotWithConfiguration:completionHandler:} is the one this PoC needs),
 * and a block is not a class you can create with {@code objc_allocateClassPair}. It is
 * just a struct in memory:
 *
 * <pre>
 *   struct Block_layout {
 *       void       *isa;        // _NSConcreteGlobalBlock
 *       int         flags;      // BLOCK_IS_GLOBAL
 *       int         reserved;
 *       void       *invoke;     // a function pointer — here, an FFM upcall stub
 *       descriptor *descriptor; // { reserved, size, copy, dispose }
 *   };
 * </pre>
 *
 * <p>So the same FFM upcall stub that gives an Objective-C method a Java body also works
 * here; only the envelope differs. Declaring {@code BLOCK_IS_GLOBAL} is what makes this
 * safe: {@code Block_copy} and {@code Block_release} both short-circuit for global
 * blocks, so the framework will neither try to move it to the heap nor free it. The
 * storage therefore lives in {@link Arena#global()} and outlives every autorelease pool.
 */
final class NativeBlock {

    /** {@code BLOCK_IS_GLOBAL} — "this block is immortal, do not copy or dispose of it". */
    private static final int BLOCK_IS_GLOBAL = 1 << 28;

    /** isa + flags + reserved + invoke + descriptor. */
    private static final long BLOCK_SIZE = 5 * 8;

    private static final MemorySegment GLOBAL_BLOCK_ISA;

    static {
        SymbolLookup blocks = SymbolLookup.libraryLookup(
                "/usr/lib/system/libsystem_blocks.dylib", Arena.global());
        GLOBAL_BLOCK_ISA = blocks.find("_NSConcreteGlobalBlock")
                .orElseThrow(() -> new UnsatisfiedLinkError("_NSConcreteGlobalBlock not found"));
    }

    private NativeBlock() {
    }

    /**
     * @param owner  the class holding the Java method that becomes the block body
     * @param method a {@code static} method whose signature matches {@code fd}
     * @param fd     the block's signature <em>including</em> the leading block pointer
     */
    public static MemorySegment of(Class<?> owner, String method, FunctionDescriptor fd) {
        Arena arena = Arena.global();
        MemorySegment invoke = ObjC.upcall(owner, method, fd);

        MemorySegment descriptor = arena.allocate(4 * 8);
        descriptor.set(JAVA_LONG, 0, 0L);                 // reserved
        descriptor.set(JAVA_LONG, 8, BLOCK_SIZE);         // size
        descriptor.set(ADDRESS, 16, MemorySegment.NULL);  // copy   — unused for global blocks
        descriptor.set(ADDRESS, 24, MemorySegment.NULL);  // dispose

        MemorySegment block = arena.allocate(BLOCK_SIZE);
        block.set(ADDRESS, 0, GLOBAL_BLOCK_ISA);
        block.set(JAVA_INT, 8, BLOCK_IS_GLOBAL);
        block.set(JAVA_INT, 12, 0);
        block.set(ADDRESS, 16, invoke);
        block.set(ADDRESS, 24, descriptor);
        return block;
    }
}
