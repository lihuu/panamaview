# Where your page runs, and why that mattered

Every problem this project hit with a bundled frontend came from **how the page was served**, not
from the web view being limited. That distinction is the whole document, and it is worth being
precise about because the intuitive reading — "so a system web view is a cut-down browser" — is
wrong.

## It is a real browser engine

`WKWebView` is **WebKit: the same engine that renders Safari**. Not a subset, not an embedded
mini-renderer. If Safari can do it, the page in this window can do it.

What it does not have is *browser chrome*: no tabs, no address bar, no built-in devtools UI (there
is a Web Inspector, attached from Safari's Develop menu). The engine is the same; the furniture is
missing, and you are expected to supply your own interface — which is the entire point of the
architecture.

It also runs **out of process**, which we measured rather than assumed:

```
PPID 1  com.apple.WebKit.WebContent
PPID 1  com.apple.WebKit.Networking
```

The JavaScript runs in its own operating-system process, managed by the system over XPC. That is
why every bridge call is genuinely asynchronous and why replies are correlated by id — we are not
being fussy, we are crossing a process boundary.

## So why did things break?

Four separate problems, from three different causes, and **none of them is the engine**:

| what broke | actual cause | is it the web view's fault? |
| --- | --- | --- |
| ES modules refused to load | `file://` has an **opaque origin**, and a module script is always fetched under CORS rules | no — it is how we served the file |
| `fetch()`, `XHR`, `Worker` refused | same opaque origin | no — same |
| a history-mode route 404'd | a URL like `/about` is not a file, and we were mapping URLs to files | no — that is what every web server does, which is why every dev server has an SPA fallback |
| Vite's dev server unreachable at `127.0.0.1` | Vite binds `::1` (IPv6) only | no — nothing to do with the browser at all |

The first two are one cause. An opaque origin is not a real origin, so anything that goes through
the fetch machinery has nothing to check itself against and is refused. Classic `<script src>`
survives because it is fetched in *no-cors* mode; module scripts are *always* fetched in *cors*
mode. That asymmetry is why a hand-written page works and a bundled one does not — and why this
project shipped a working app for weeks before anything went wrong.

**Give the page a real origin and all of it goes away.** That is what a custom URL scheme is:

```java
panamaview://app/index.html      // an origin the page owns
```

Measured, same page and same handler, only the source changing:

| capability | `file://` | `panamaview://app` |
| --- | --- | --- |
| classic `<script src>` | works | works |
| ES module | **refused** | works |
| static `import` | refused | works |
| dynamic `import()` — code splitting | refused | works |
| `fetch()` | refused | works |
| `XMLHttpRequest` | refused | works |
| `new Worker()` | refused | works |

## This is not a workaround we invented

Every framework in this space serves application content over a scheme it controls, for exactly
these reasons:

| | engine | content served from | engine ships with the app? |
| --- | --- | --- | --- |
| **panamaview** | system WebKit (macOS) | `panamaview://app/…` | no — the OS provides it |
| **Tauri** | system WebKit / WebView2 / WebKitGTK | a custom asset protocol | no — the OS provides it |
| **Electron** | bundled Chromium | a custom protocol (`protocol.handle`) | yes — ~150 MB, pinned by the app |

Tauri is the closest relative: the same architecture, the same problem, the same answer. Its
documentation describes serving from disk through "the asset custom protocol", and `file://` shows
up in its issue tracker marked *not recommended*. If your instinct on reading our findings was
"this must be a known thing", it is — that is why the fix looked familiar.

Worth noting that Electron can load `file://` too, and people do, and they hit exactly what we hit.
The difference is that Electron's ecosystem has settled on custom protocols as the answer, so the
tutorials hand it to you and nobody discovers the constraint the hard way.

## The one thing that genuinely differs

Engine **uniformity**, and it is a real trade-off rather than a detail.

A bundled engine is one known Chromium for every user, forever. A system web view is whatever
version that person's operating system shipped. For this project that means the page runs on
WebKit-as-of-the-user's-macOS: on an older machine, older WebKit, with older feature support.

What you get in exchange:

- **No engine to ship.** This app's native binary is ~20 MB against Electron's ~150 MB, and none
  of that is a browser.
- **No engine to patch.** Security fixes arrive with OS updates instead of with your release
  schedule — good for the user, and it means you never own a browser.
- **Native look and behaviour** in fields, menus, selection, and text rendering, because it is the
  system's own engine doing it.

The cost is that "works on my machine" needs qualifying. If you depend on a recent web platform
feature, you now have a compatibility matrix you did not have before — and it is a matrix you
cannot fix by shipping an update, only by choosing a fallback or a higher OS requirement. For an
internal tool or an app targeting recent machines, that trade is easy. For something that must
behave identically on a five-year-old Mac, it is the reason to bundle an engine instead.

To be explicit about what the *engine choice* did and did not cost us here: nothing in this
document is about WebKit versus Blink. We never hit a case where Safari could do something our
page could not. Every problem above would have appeared identically on Chromium.

## Checklist for a new app

1. **Serve from a scheme you control**, never `file://`. The framework does this; if you ever
   bypass it, expect a blank window and a clean console.
2. **Reference the bridge absolutely** — `/panamaview.js`, not `panamaview.js`. A relative path
   resolves against the page's directory and breaks on the first route with a segment.
3. **Leave `spaFallback` on** if you use history-mode routing.
4. **Keep the frontend build boring.** If a config carries a workaround for the host, that is a
   signal the host is serving the page wrong — which is how the single-file plugin in an earlier
   version of the React template turned out to be a symptom rather than a fix.

If the window is ever blank while the console is clean, the question to ask first is:
**what origin is this page on?** That pairing — loaded fine, ran nothing — is the signature of an
opaque origin.
