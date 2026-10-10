import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { viteSingleFile } from "vite-plugin-singlefile";

/*
 * Three settings here are load-bearing, and each one was arrived at by watching the app fail
 * without it. They are not taste.
 *
 * 1. base: "./"
 *    The built page is loaded from disk over file://, where an absolute "/assets/x.js" resolves
 *    to the filesystem root rather than to the page. Relative references are what a file:// load
 *    can follow.
 *
 * 2. viteSingleFile()
 *    This is the one that actually bites. Vite emits ES modules, and a module script is fetched
 *    with CORS rules — under file:// the page's origin is opaque, so the fetch is refused and
 *    NOTHING runs. Relative paths alone do not fix it; the symptom is a blank window with a
 *    clean console and a page that reports it loaded successfully. Inlining the bundle into the
 *    HTML removes the fetch entirely, and an inline module executes without one.
 *
 *    It also collapses the output to a single index.html, which means the asset manifest the
 *    Java side needs is one line rather than a list of hashed filenames that change every build.
 *
 *    The trade: no code splitting, and the whole bundle rides in the HTML. For a desktop app
 *    whose assets are read from local disk that costs nothing, since there is no network and
 *    nothing to cache between loads.
 *
 * 3. The bridge script rewrite, below.
 *    In development the page is served by this dev server, so the bridge is fetched from the
 *    public directory at "/panamaview.js". In the built app the page is loaded from disk
 *    alongside a copy of the same file, so the reference has to be relative. One index.html
 *    cannot say both things, so it says the dev-server form and this rewrites it for build
 *    output only.
 */
export default defineConfig({
  plugins: [
    react(),
    viteSingleFile(),
    {
      name: "panamaview-relative-bridge",
      apply: "build",
      transformIndexHtml: (html) => html.replace('src="/panamaview.js"', 'src="panamaview.js"'),
    },
  ],
  base: "./",
});
