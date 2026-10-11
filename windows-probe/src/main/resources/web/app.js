"use strict";

const $ = (id) => document.getElementById(id);

function log(node, text) {
  $(node).textContent = typeof text === "string" ? text : JSON.stringify(text, null, 2);
}

// Java → page. The demo's slow task pushes progress through here; onReady pushes a hello.
window.onJavaMessage = (payload) => {
  log("inbox", "收到 " + JSON.stringify(payload));
};

// 点击 + 输入 → 调用 Java：一行 await，回复按 id 配对，顺序无关。
$("greet").onclick = async () => {
  try {
    const reply = await window.java.invoke("hello", { name: $("name").value });
    log("greetOut", "Java 说: " + reply);
  } catch (e) {
    log("greetOut", "失败了: " + e.message);
  }
};

// 状态在 Java 侧：每次点击都是一次真实的跨进程调用。
$("count").onclick = async () => {
  $("countOut").textContent = await window.java.invoke("bump");
};

// 慢任务：处理器跑在虚拟线程上，页面可以取消它，Java 侧被真正 interrupt。
let run = null;
$("start").onclick = () => {
  log("taskOut", "已提交，等 Java 干活……");
  run = window.java.invoke("slowTask", { steps: 8 });
  run.then((r) => log("taskOut", "任务完成: " + r))
     .catch((e) => log("taskOut", "任务结束: " + e.message));
};
$("cancel").onclick = () => {
  if (run) {
    run.cancel();
  }
};

// bindUi：跑在 UI 线程上的处理器，读窗口状态用它。
$("info").onclick = async () => {
  log("infoOut", await window.java.invoke("windowInfo"));
};
