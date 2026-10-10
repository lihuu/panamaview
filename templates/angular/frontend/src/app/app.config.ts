import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withHashLocation } from '@angular/router';
import { routes } from './app.routes';

/*
 * History mode by default, so URLs are real paths (/about) rather than (/#/about).
 *
 * That works because the host answers a path that is not a file with the entry document instead
 * of a 404 — otherwise a reload or a deep link would fail, since nothing was ever built at
 * /about. This is WebApp.spaFallback, and it is on by default.
 *
 * If you would rather not depend on it, swap provideRouter(routes) for
 * provideRouter(routes, withHashLocation()) (imported above). The cost is a '#' in the URL,
 * which matters if you ever open one in a browser.
 */
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
  ]
};
