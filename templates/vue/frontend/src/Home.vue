<script setup>
/*
 * A route. The point of having two is that a URL like /about only exists client-side: nothing
 * was ever built at that path, so reloading it asks the host for a file that does not exist.
 * WebApp.spaFallback answers with this app's entry document and the router takes it from there.
 *
 * Try it: run ./run.sh, click "about", then reload the window. Without the fallback that reload
 * is a blank page.
 */
</script>

<template>
  <section>
    <h2>Route</h2>
    <p id="route">home</p>
  </section>
</template>
