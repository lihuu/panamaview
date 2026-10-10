import { Component, inject, signal } from "@angular/core";
import { JsonPipe } from "@angular/common";
import { RouterLink, RouterLinkActive, RouterOutlet } from "@angular/router";
import { Bridge } from "./bridge";

@Component({
  selector: "app-root",
  imports: [RouterLink, RouterLinkActive, RouterOutlet, JsonPipe],
  templateUrl: "./app.html",
})
export class App {
  // Injected so the service is created during bootstrap rather than on first use. It is what
  // makes `fromJava` already hold Java's first message by the time this renders.
  protected readonly bridge = inject(Bridge);

  protected readonly answer = signal<string | null>(null);

  protected async ask(): Promise<void> {
    this.answer.set("…");
    try {
      this.answer.set(String(await this.bridge.invoke("greet", { name: "World" })));
    } catch (error) {
      // A timeout, a cancellation and a thrown exception all arrive here as a message.
      this.answer.set(`Error: ${(error as Error).message}`);
    }
  }
}
