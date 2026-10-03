import { beforeEach, afterEach, it, expect, vi } from "vitest";
import { CommandIntent } from "../src/command-intent";
let calls: { path: string; init?: RequestInit }[] = [];
beforeEach(() => {
  vi.resetModules();
  calls = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (path: string, init?: RequestInit) => {
      calls.push({ path, init });
      return new Response(
        JSON.stringify(
          path.includes("/csrf")
            ? { token: "test-csrf" }
            : { items: [], hasMore: false },
        ),
        { status: 200, headers: { "Content-Type": "application/json" } },
      );
    }),
  );
});
afterEach(() => vi.unstubAllGlobals());
it("explicitly uses OFFSET mode and encodes filters", async () => {
  const { requests } = await import("../src/api");
  await requests.list("org", "a & b", 20, {
    status: "APPROVED",
    type: "PURCHASE",
    createdFrom: "2026-01-01T00:00:00Z",
  });
  const u = new URL(calls[0].path, "https://test.invalid");
  expect(u.searchParams.get("pagination")).toBe("OFFSET");
  expect(u.searchParams.get("offset")).toBe("20");
  expect(u.searchParams.get("q")).toBe("a & b");
  expect(u.searchParams.get("status")).toBe("APPROVED");
});
it("includes inbox and notification offsets", async () => {
  const { requests, notifications } = await import("../src/api");
  await requests.inbox("org", 40);
  await notifications.list("org", 60);
  expect(calls[0].path).toContain("pagination=OFFSET&offset=40");
  expect(calls[1].path).toContain("offset=60");
});
it("preserves command keys through actual fetch failures", async () => {
  const { requests } = await import("../src/api");
  const intent = new CommandIntent();
  let attempts = 0;
  const keys: string[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (path: string, init?: RequestInit) => {
      if (path.includes("/csrf"))
        return new Response(JSON.stringify({ token: "test-csrf" }));
      keys.push(new Headers(init?.headers).get("Idempotency-Key")!);
      if (++attempts === 1) throw new TypeError("network failed");
      return new Response(JSON.stringify({ id: "one-request" }));
    }),
  );
  await expect(
    requests.submit("org", { title: "one" }, intent),
  ).rejects.toMatchObject({ status: 0 });
  await requests.submit("org", { title: "one" }, intent);
  expect(keys[0]).toBe(keys[1]);
});
it("acknowledges notifications with CSRF and versioned preferences", async () => {
  const { notifications } = await import("../src/api");
  await notifications.read("org", "notice");
  await notifications.savePreferences("org", {
    inAppEnabled: true,
    emailEnabled: false,
    expectedVersion: 3,
  });
  expect(calls[1].init?.method).toBe("PATCH");
  expect(new Headers(calls[1].init?.headers).get("X-XSRF-TOKEN")).toBe(
    "test-csrf",
  );
  expect(JSON.parse(calls[2].init?.body as string).expectedVersion).toBe(3);
});
it("uses the server-selected CSRF header and rejects invalid bootstrap data", async () => {
  const { api, getCsrf } = await import("../src/api");
  vi.stubGlobal(
    "fetch",
    vi.fn(async (path: string, init?: RequestInit) => {
      calls.push({ path, init });
      return new Response(
        JSON.stringify(
          path.includes("/csrf")
            ? { token: "test", headerName: "X-CSRF-TOKEN" }
            : {},
        ),
      );
    }),
  );
  await api("/test", { method: "POST" });
  expect(new Headers(calls[1].init?.headers).get("X-CSRF-TOKEN")).toBe("test");
  vi.stubGlobal(
    "fetch",
    vi.fn(
      async () =>
        new Response(
          JSON.stringify({ token: "test", headerName: "Authorization" }),
        ),
    ),
  );
  await expect(getCsrf()).rejects.toMatchObject({ status: 502 });
});
