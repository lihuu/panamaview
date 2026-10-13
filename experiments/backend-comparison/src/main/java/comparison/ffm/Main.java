package comparison.ffm;

import comparison.ComparisonApp;
import java.lang.foreign.*;
import panamaview.macos.MacOsHost;

public final class Main {
    public static void main(String[] args) throws Exception {
        MacOsHost host = new MacOsHost();
        ComparisonApp.run("ffm-system-webview", host, Main::quit);
        if (ComparisonApp.exitCode != 0) System.exit(ComparisonApp.exitCode);
    }

    /** Experiment-only quit operation, using the same OS dispatch mechanism as the host. */
    private static void quit() {
        try {
            var lib = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global());
            var linker = Linker.nativeLinker();
            var name = FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            var getClass = linker.downcallHandle(lib.find("objc_getClass").orElseThrow(), name);
            var selector = linker.downcallHandle(lib.find("sel_registerName").orElseThrow(), name);
            var pointerCall = linker.downcallHandle(lib.find("objc_msgSend").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            var voidCall = linker.downcallHandle(lib.find("objc_msgSend").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment cls = (MemorySegment) getClass.invokeExact(arena.allocateFrom("NSApplication"));
                MemorySegment shared = (MemorySegment) selector.invokeExact(arena.allocateFrom("sharedApplication"));
                MemorySegment app = (MemorySegment) pointerCall.invokeExact(cls, shared);
                MemorySegment terminate = (MemorySegment) selector.invokeExact(arena.allocateFrom("terminate:"));
                voidCall.invokeExact(app, terminate, MemorySegment.NULL);
            }
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
