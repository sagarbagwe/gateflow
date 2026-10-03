import { describe, it, expect, vi } from "vitest";
import { CommandIntent } from "../src/command-intent";
import { AsyncScope } from "../src/async-scope";
describe("command intent", () => {
  it("reuses the same key after an ambiguous failure", async () => {
    const intent = new CommandIntent(),
      keys: string[] = [];
    const send = vi.fn(async (key: string) => {
      keys.push(key);
      if (keys.length === 1) throw new Error("lost response");
      return { id: "same" };
    });
    await expect(
      intent.run("/submit", { title: "Laptop" }, send),
    ).rejects.toThrow();
    await intent.run("/submit", { title: "Laptop" }, send);
    expect(keys[0]).toBe(keys[1]);
    await intent.run("/submit", { title: "Laptop" }, send);
    expect(keys[2]).not.toBe(keys[1]);
  });
  it("rotates for a different payload or path", async () => {
    const intent = new CommandIntent(),
      keys: string[] = [];
    const fail = async (k: string) => {
      keys.push(k);
      throw new Error("offline");
    };
    for (const [p, b] of [
      ["/a", { version: 0 }],
      ["/a", { version: 1 }],
      ["/b", { version: 1 }],
    ] as const)
      await intent.run(p, b, fail).catch(() => {});
    expect(new Set(keys).size).toBe(3);
  });
  it("coalesces concurrent identical clicks", async () => {
    const intent = new CommandIntent();
    let finish!: (v: number) => void;
    const send = vi.fn(() => new Promise<number>((r) => (finish = r)));
    const a = intent.run("/a", {}, send),
      b = intent.run("/a", {}, send);
    finish(1);
    expect(await a).toBe(1);
    expect(await b).toBe(1);
    expect(send).toHaveBeenCalledTimes(1);
  });
});
describe("async scope", () => {
  it("rejects older loads while keeping independent channels", () => {
    const scope = new AsyncScope(),
      old = scope.start("list"),
      detail = scope.start("detail"),
      latest = scope.start("list");
    expect(old()).toBe(false);
    expect(detail()).toBe(true);
    expect(latest()).toBe(true);
  });
  it("rejects responses across workspace or account changes, including switch-back", () => {
    const scope = new AsyncScope(),
      old = scope.start("list");
    scope.invalidate();
    scope.invalidate();
    scope.start("list");
    expect(old()).toBe(false);
  });
});
