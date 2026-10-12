# panamaview demo

A small application built on `panamaview` — and the only thing in this repository that uses the
framework the way anyone else would: one dependency, its own sources, its own assets, no access
to the framework's internals.

That is the point of it. Every mechanism in the bridge has a probe here, the page runs all of them
on load, and it reports the verdict back to Java so the result lands in stdout instead of in a
screenshot:

```
[selftest] 13 passed, 0 failed
[selftest]   PASS  slow handler does not block — hello answered in 1 ms while slow(600 ms) was in flight
[selftest]   PASS  control: UI-thread handler does block — hello delayed 406 ms behind slowUi(400 ms)
[selftest]   PASS  cancel actually stops the handler — handler interrupted (count 0 → 1)
[selftest]   PASS  java-side deadline fires on its own — rejected after 303 ms with "no answer within 300 ms"
[selftest]   PASS  200 concurrent invocations — 200 round trips in 22 ms
[selftest]   PASS  webkit self-snapshot — /tmp/shot.png
```

Several of those checks exist to be able to **fail**: `slowUi` is the control experiment for
`slow`, and the cancellation check counts interruptions on the Java side rather than timing the
page's own promise. A test that can only pass is not evidence.

## Before it will build

`io.github.lihuu:panamaview` has to be resolvable — from Maven Central, where it is published from
`0.3.0` on, from a private repository the machine is configured for, or from the framework checkout
with `mvn install -DskipTests`. This project deliberately has no fallback to the framework's source
tree; if it stops building, the framework stopped being usable from outside itself.

## Run it

```sh
./run.sh              # JVM mode
./build-native.sh     # → target/panamaview-demo, a standalone executable
./target/panamaview-demo
```

Three system properties change what happens on start-up:

```sh
JAVA_OPTS="-Dpanamaview.check=true" ./run.sh               # stage the assets and stop
JAVA_OPTS="-Dpanamaview.snapshot=/tmp/shot.png" ./run.sh   # photograph the window when loaded
JAVA_OPTS="-Dpanamaview.navigateAway=true" ./run.sh        # end the run by navigating away
```

`-Dpanamaview.check=true` is the headless one: it stages the demo's assets and the framework's
client bridge, prints what it found, and exits without loading AppKit. It is what CI runs, on a
machine with no window server, and it is the only check that can run there — everything else here
needs a window.

## Notes

`-Dpanamaview.snapshot` uses `takeSnapshotWithConfiguration:` with `afterScreenUpdates: true`,
which asks WebKit to wait for the next screen update. When the window is occluded that update can
be late enough to stall the run visibly. It only affects this diagnostic flag, not the bridge.

This project is never published. It exists to be built.
