import { useState } from "react";
import { useJavaMessage } from "./bridge.js";

/*
 * The bridge has no framework-specific binding, on purpose: `window.java.invoke` is a promise, so
 * it fits React the way any async call does. The one place React needs help is the Java → page
 * direction, because a callback can fire before anything has mounted — bridge.js is that help.
 */
export default function App() {
  const fromJava = useJavaMessage();
  const [answer, setAnswer] = useState(null);

  async function ask() {
    setAnswer("…");
    try {
      setAnswer(await window.java.invoke("greet", { name: "World" }));
    } catch (error) {
      // A timeout, a cancellation and a thrown exception all arrive here as a message.
      setAnswer(`Error: ${error.message}`);
    }
  }

  return (
    <main>
      <h1>My App</h1>

      <section>
        <h2>Java → JavaScript</h2>
        <pre>{fromJava ? JSON.stringify(fromJava, null, 2) : "waiting for Java…"}</pre>
      </section>

      <section>
        <h2>JavaScript → Java</h2>
        <button onClick={ask}>Call Java</button>
        <pre>{answer ?? "—"}</pre>
      </section>
    </main>
  );
}
