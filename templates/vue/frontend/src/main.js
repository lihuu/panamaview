import { createApp } from "vue";
import { createRouter, createWebHistory } from "vue-router";
import App from "./App.vue";
import Home from "./Home.vue";
import About from "./About.vue";
import "./style.css";

// Installs window.onJavaMessage before anything renders. The framework warns at load time if
// nothing has defined that callback, because messages pushed from Java would otherwise vanish
// without a trace.
import "./bridge.js";

/*
 * History mode: URLs are real paths (/about), not hashes (/#/about).
 *
 * That is the mode worth having, and it is only viable because the host answers a path that is
 * not a file with the entry document — otherwise a reload or a deep link would 404. This is what
 * WebApp.spaFallback does, and it is on by default.
 *
 * If you would rather not depend on that, createWebHashHistory() needs nothing from the host.
 * The cost is a URL with a # in it, which matters if you ever open one in a browser.
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", component: Home },
    { path: "/about", component: About },
  ],
});

createApp(App).use(router).mount("#app");
