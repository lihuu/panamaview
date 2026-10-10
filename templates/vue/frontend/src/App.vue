<script setup>
/*
 * The bridge has no Vue-specific binding, on purpose: window.java.invoke is a promise, so it fits
 * Vue the way any async call does. The one place Vue needs help is the Java → page direction,
 * because a callback can fire before anything has mounted — bridge.js is that help.
 */
import { ref } from "vue";
import { useJavaMessage } from "./bridge.js";

const fromJava = useJavaMessage();
const answer = ref(null);

async function ask() {
  answer.value = "…";
  try {
    answer.value = await window.java.invoke("greet", { name: "World" });
  } catch (error) {
    // A timeout, a cancellation and a thrown exception all arrive here as a message.
    answer.value = `Error: ${error.message}`;
  }
}
</script>

<template>
  <main>
    <h1>My App</h1>

    <nav>
      <RouterLink to="/">home</RouterLink>
      <RouterLink to="/about">about</RouterLink>
    </nav>

    <RouterView />

    <section>
      <h2>Java → JavaScript</h2>
      <pre>{{ fromJava ? JSON.stringify(fromJava, null, 2) : "waiting for Java…" }}</pre>
    </section>

    <section>
      <h2>JavaScript → Java</h2>
      <button @click="ask">Call Java</button>
      <pre>{{ answer ?? "—" }}</pre>
    </section>
  </main>
</template>
