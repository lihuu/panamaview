import React from "react";
import { createRoot } from "react-dom/client";
import App from "./App.jsx";
import "./style.css";

// bridge.js owns window.onJavaMessage and exposes it as a hook; importing it here installs the
// callback before anything renders. The framework warns at load time if nothing has defined that
// callback, because messages pushed from Java would otherwise disappear without a trace.
import "./bridge.js";

createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
