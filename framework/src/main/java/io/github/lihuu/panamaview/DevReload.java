package io.github.lihuu.panamaview;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Reloads the page when the files behind it change, so that editing plain HTML gives the same
 * experience a frontend dev server does.
 *
 * <p>Only used for file-backed pages. A page served by a real dev server reloads itself — that
 * is what the dev server is for — so this exists for the case where there is no frontend
 * toolchain in the picture at all.
 *
 * <p>Two details that matter in practice:
 *
 * <ul>
 *   <li><b>Directories are watched recursively.</b> A flat watch misses {@code assets/foo.js},
 *       which is exactly where generated output tends to live.</li>
 *   <li><b>Events are debounced.</b> One save in an editor is routinely three events (write,
 *       rename, attribute change) plus the compiler's own output, and three reloads in a row
 *       is both wasteful and visibly janky.</li>
 * </ul>
 */
final class DevReload {

    /** Long enough to swallow one save's burst of events, short enough to feel immediate. */
    private static final long DEBOUNCE_MILLIS = 180;

    private DevReload() {
    }

    /**
     * Watches {@code root} and calls {@code onChange} after the files settle down.
     *
     * <p>Runs on a daemon thread, so it cannot keep the process alive on its own.
     */
    static void watch(Path root, Runnable onChange) throws IOException {
        WatchService service = FileSystems.getDefault().newWatchService();
        int registered = registerTree(root, service);
        if (registered == 0) {
            service.close();
            throw new IOException("nothing to watch under " + root);
        }

        Thread thread = new Thread(() -> {
            try {
                while (true) {
                    WatchKey key = service.take();
                    boolean relevant = false;
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                            relevant = true;
                            continue;
                        }
                        String name = String.valueOf(event.context());
                        // Editors and compilers write these constantly; reloading for them is
                        // noise, not signal.
                        if (!name.endsWith("~") && !name.endsWith(".swp") && !name.startsWith(".#")) {
                            relevant = true;
                        }
                    }

                    // A newly created directory needs watching too, for the same reason the
                    // walk is recursive: output directories appear mid-run.
                    Path dir = (Path) key.watchable();
                    if (!key.reset()) {
                        continue;
                    }
                    try (var children = Files.list(dir)) {
                        for (Path child : children.filter(Files::isDirectory).toList()) {
                            registerTree(child, service);
                        }
                    } catch (IOException ignored) {
                        // best effort
                    }

                    if (relevant) {
                        Thread.sleep(DEBOUNCE_MILLIS);
                        // Drain whatever else arrived during the debounce, so the reload that
                        // follows is for the settled state rather than an intermediate one.
                        WatchKey extra;
                        while ((extra = service.poll()) != null) {
                            extra.pollEvents();
                            extra.reset();
                        }
                        onChange.run();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                System.err.println("[panamaview] live reload stopped: " + t);
            }
        }, "panamaview-dev-reload");
        thread.setDaemon(true);
        thread.start();
    }

    private static int registerTree(Path root, WatchService service) throws IOException {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        var counter = new int[1];
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                dir.register(service,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                counter[0]++;
                return FileVisitResult.CONTINUE;
            }
        });
        return counter[0];
    }
}
