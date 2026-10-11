# windows-probe

The Windows port's milestone-1 consumer. It proves the FFM → `WebView2Loader.dll` → installed
runtime chain without opening a window, so it runs over SSH, in session 0, on a headless
runner — anywhere a JVM runs.

It exists for the same reason `demo-app/` does: the framework jar must carry no application,
so anything with a `main()` lives in a module that depends on the framework the way anyone else
would. Like `demo-app/`, it is a module of the repository's reactor — it resolves the framework
out of the local repository rather than out of the reactor, and inherits the version instead of
pinning it.

It builds on any platform. `Win32`, `Com` and `WebView2` bind their entry points at run time
through `Linker.nativeLinker()`, so on macOS they compile and are never called; only running the
probe on Windows reaches the loader. That is what makes it usable as a compile-time check
everywhere and a real check on Windows.

The probe stops at the runtime version on purpose: opening a window is milestone 2, which needs a
desktop session — not because the code is missing, but because Windows does not render one to a
headless session.

## Run it

```sh
mvn install -DskipTests                                  # the framework, once, from the repository root
powershell -ExecutionPolicy Bypass -File fetch-loader.ps1
mvn compile exec:exec
```

Expected output ends:

```
loader  ...\windows-probe\loader\WebView2Loader.dll
runtime WebView2 154.0.4258.62
[probe] PASS — FFM reached the loader, and the installed runtime answered
```

Exit codes: 0 pass, 1 the runtime chain failed, 2 the loader DLL is not staged.

## The smoke (milestone 2a)

`Smoke.java` is the first real window: it creates a Win32 window, a WebView2 environment
and controller over it, navigates the page, and runs `2+2` through `ExecuteScript` with a
hand-built COM callback — the whole sequence milestone 2 will grow `WindowsHost` out of,
with no framework around it yet.

It opens a window on the desktop it runs in, so it must be launched from an **interactive
session** (RDP or the console), not SSH — in session 0 WebView2 does not run. It ends by
itself (a watchdog quits it after two minutes if the chain stalls), and every stage lands
in `smoke-result.txt` beside this file, so the run can be judged by someone who was not
watching the screen:

```bat
run-smoke.cmd
```

Expected last stage: `stage script  2+2 returned 4` and a PASS verdict line.

## Requirements

- Windows on x64, with the WebView2 Evergreen runtime installed. It is preinstalled on
  Windows 11; the probe names it explicitly when it is missing. `fetch-loader.ps1` does not
  install the runtime — it fetches the loader that finds it.
- JDK 25 and Maven. `mvn.cmd` takes its Java from `JAVA_HOME`, so a machine with several JDKs
  points that at the JDK 25 install and the build follows it; nothing else needs configuring.

The loader DLL is not committed. `fetch-loader.ps1` downloads it from the
`Microsoft.Web.WebView2` NuGet package and verifies its sha256 against the pin in the script —
the same pattern as the Tailwind standalone binary in the `vanilla-tailwind` template.
