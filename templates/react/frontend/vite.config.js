import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

/*
 * A plain Vite config. Nothing here exists to work around the host, and that is the point.
 *
 * An earlier version of this file carried two workarounds, both of which were symptoms of the
 * page being loaded over file://:
 *
 *   base: "./"              because an absolute /assets/x.js resolves to the filesystem root
 *                           from a file:// page rather than to the page
 *   vite-plugin-singlefile  because a module script is fetched under CORS rules, and a file://
 *                           origin is opaque, so the fetch was refused and nothing ran. Inlining
 *                           removed the fetch. It also removed code splitting.
 *
 * The host now serves the app's assets over its own URL scheme, so the page has a real origin
 * and neither workaround is needed — nor is the bridge-script rewrite that went with them.
 * Absolute paths resolve normally, and the module graph loads, including dynamic imports.
 *
 * If you ever see a blank window with a clean console in this app, check that the page is being
 * served over that scheme and not over file://. That pairing is the whole failure mode.
 */
export default defineConfig({
  plugins: [react()],
});
