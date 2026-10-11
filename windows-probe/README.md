# windows-probe

The Windows port's milestone-1 consumer. It proves the FFM → `WebView2Loader.dll` → installed
runtime chain without opening a window, so it runs over SSH, in session 0, on a headless
runner — anywhere a JVM runs.

It exists for the same reason `demo-app/` does: the framework jar must carry no application,
so anything with a `main()` lives in a consumer module that depends on the framework the way
anyone else would. And it stops at the runtime version on purpose: opening a window is
milestone 2, which needs a desktop session — not because the code is missing, but because
Windows does not render one to a headless session.

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

## Requirements

- Windows on x64, with the WebView2 Evergreen runtime installed. It is preinstalled on
  Windows 11; the probe names it explicitly when it is missing. `fetch-loader.ps1` does not
  install the runtime — it fetches the loader that finds it.
- JDK 25 and Maven. `mvn.cmd` takes its Java from `JAVA_HOME`, so a machine with several JDKs
  points that at the JDK 25 install and the build follows it; nothing else needs configuring.

The loader DLL is not committed. `fetch-loader.ps1` downloads it from the
`Microsoft.Web.WebView2` NuGet package and verifies its sha256 against the pin in the script —
the same pattern as the Tailwind standalone binary in the `vanilla-tailwind` template.
