import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';

/*
 * Importing bridge.ts for its side effect installs window.onJavaMessage before anything renders.
 * The framework warns at load time if nothing has defined that callback, because messages pushed
 * from Java would otherwise vanish without a trace.
 */
import './app/bridge';

bootstrapApplication(App, appConfig)
  .catch((err) => console.error(err));
