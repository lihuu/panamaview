package poc;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;

/**
 * Native Image enters Java on the OS first thread. Standard Glass dispatches to that
 * thread, so Application.launch must run elsewhere while this thread pumps CFRunLoop.
 * This is an experimental macOS launcher adapter, not a JavaFX window/WebView binding.
 */
final class MacNativeBootstrap {
    static void run(Runnable application) throws Throwable {
        var symbols = SymbolLookup.libraryLookup(
                "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", Arena.global());
        var linker = Linker.nativeLinker();
        var getMain = linker.downcallHandle(symbols.find("CFRunLoopGetMain").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS));
        var runLoop = linker.downcallHandle(symbols.find("CFRunLoopRunInMode").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_BYTE));
        MemorySegment defaultMode = symbols.find("kCFRunLoopDefaultMode").orElseThrow()
                .reinterpret(ValueLayout.ADDRESS.byteSize()).get(ValueLayout.ADDRESS, 0);
        var wakeLoop = linker.downcallHandle(symbols.find("CFRunLoopWakeUp").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        MemorySegment mainLoop = (MemorySegment) getMain.invokeExact();
        Thread worker = new Thread(() -> {
            try { application.run(); }
            finally {
                try {
                    wakeLoop.invokeExact(mainLoop);
                } catch (Throwable failure) { failure.printStackTrace(); }
            }
        }, "poc-java-launcher");
        worker.start();
        System.out.println("[poc] mac-native-bootstrap CFRunLoop on initial thread");
        // A stop request affects the innermost run-loop invocation. Glass runs a nested
        // NSApplication loop, so an unbounded outer CFRunLoopRun can survive window close.
        while (worker.isAlive()) {
            int ignored = (int) runLoop.invokeExact(defaultMode, 0.1, (byte) 1);
        }
        worker.join();
    }
}
