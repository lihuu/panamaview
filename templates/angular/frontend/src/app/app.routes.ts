import { Routes } from '@angular/router';
import { Home } from './home';
import { About } from './about';

/*
 * Two routes, so the URL is a real path the router owns. Reload either one: the host serves the
 * entry document, Angular reads the path, and you land where you were. That round trip is the
 * whole reason spaFallback exists.
 */
export const routes: Routes = [
  { path: '', component: Home },
  { path: 'about', component: About },
];
