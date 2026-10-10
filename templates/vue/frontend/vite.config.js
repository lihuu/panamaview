import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

/*
 * A plain Vite config. Nothing here works around the host, and that is deliberate.
 *
 * Two things this file would have needed if the page were loaded over file://, and does not:
 *
 *   base: "./"              an absolute /assets/x.js resolves to the filesystem root from a
 *                           file:// page rather than to the page
 *   plugin-singlefile       a module script is fetched under CORS rules, and a file:// origin is
 *                           opaque, so the fetch is refused and nothing runs at all. Inlining
 *                           removed the fetch, and also removed code splitting.
 *
 * The host serves these assets over its own URL scheme instead, so the page has a real origin and
 * neither workaround is needed. Absolute paths resolve normally and the module graph loads.
 *
 * If you ever see a blank window with a clean console here, check that the page is served over
 * that scheme rather than file://. That pairing is the whole failure mode, and it is silent.
 *
 * Nothing else is required for the router: history mode works because the host answers a path
 * that is not a file with the entry document. See WebApp.spaFallback.
 */
export default defineConfig({
  plugins: [vue()],
});
