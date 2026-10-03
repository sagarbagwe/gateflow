// @vitest-environment jsdom
import { act } from "react";
import { createRoot, Root } from "react-dom/client";
import { beforeEach, afterEach, it, expect, vi } from "vitest";
import { App } from "../src/main";
let root: Root,
  host: HTMLDivElement,
  calls: string[],
  overrides: (
    path: string,
    init?: RequestInit,
  ) => Response | Promise<Response> | undefined;
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
const flush = () =>
  act(async () => {
    await new Promise((r) => setTimeout(r, 0));
  });
const click = async (text: string) => {
  const b = [...host.querySelectorAll("button")].find(
    (x) => x.textContent?.trim() === text,
  );
  expect(b, `Missing button ${text}`).toBeTruthy();
  await act(async () => b!.click());
  await flush();
};
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  localStorage.clear();
  calls = [];
  overrides = () => undefined;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (path: string, init?: RequestInit) => {
      calls.push(path);
      const custom = overrides(path, init);
      if (custom) return await custom;
      if (path.endsWith("/csrf")) return json({ token: "test-csrf" });
      if (path.endsWith("/auth/me"))
        return json({
          id: "user",
          email: "test@example.invalid",
          displayName: "Tester",
        });
      if (path.includes("/organizations?"))
        return json({ items: [{ id: "org" }] });
      if (path.endsWith("/org/me"))
        return json({
          membershipId: "member",
          permissions: [
            "REQUEST_SUBMIT",
            "REQUEST_WITHDRAW_OWN",
            "REQUEST_APPROVE",
          ],
        });
      if (path.endsWith("/preferences"))
        return json({ inAppEnabled: true, emailEnabled: false, version: 0 });
      if (path.includes("/notifications?"))
        return json({
          items: [
            {
              id: "notification-id",
              requestId: "request-id",
              kind: "REVIEW",
              eventType: "REQUEST_SUBMITTED",
              readAt: null,
            },
          ],
          hasMore: false,
        });
      if (path.endsWith("/requests/request-id"))
        return json({
          id: "request-id",
          title: "Laptop request",
          state: "IN_REVIEW",
          version: 0,
          steps: [],
        });
      return json({
        items: [
          { id: "request-id", title: "Laptop request", state: "IN_REVIEW" },
        ],
        hasMore: true,
      });
    }),
  );
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  vi.unstubAllGlobals();
});
async function boot() {
  await act(async () => root.render(<App />));
  await flush();
  await flush();
}
it("opens the notification request, not its notification ID", async () => {
  await boot();
  await click("Notifications");
  const b = host.querySelector<HTMLButtonElement>('[aria-label="Open item"]')!;
  await act(async () => b.click());
  await flush();
  expect(calls).toContain("/api/v1/organizations/org/requests/request-id");
  expect(calls.some((x) => x.endsWith("/requests/notification-id"))).toBe(
    false,
  );
});
it("shows logout errors and keeps the session UI until successful logout", async () => {
  await boot();
  overrides = (p) =>
    p.endsWith("/logout")
      ? json({ title: "Unavailable", detail: "Try again" }, 503)
      : undefined;
  await act(async () =>
    host.querySelector<HTMLButtonElement>('[aria-label="Sign out"]')!.click(),
  );
  await flush();
  expect(host.textContent).toContain("Try again");
  expect(host.textContent).toContain("Tester");
  expect(host.textContent).not.toContain("Sign in to GateFlow");
});
it("clears the account only after server logout succeeds", async () => {
  await boot();
  overrides = (p) =>
    p.endsWith("/logout") ? new Response(null, { status: 204 }) : undefined;
  await act(async () =>
    host.querySelector<HTMLButtonElement>('[aria-label="Sign out"]')!.click(),
  );
  await flush();
  expect(host.textContent).toContain("Sign in to GateFlow");
  expect(localStorage.getItem("gateflow.org")).toBeNull();
});
it("retrieves the next bounded page", async () => {
  await boot();
  await click("Next");
  expect(
    calls.some(
      (x) => x.includes("pagination=OFFSET") && x.includes("offset=20"),
    ),
  ).toBe(true);
  expect(host.textContent).toContain("Page 2");
});
it("does not show approval actions when an active step is missing", async () => {
  await boot();
  await act(async () =>
    host.querySelector<HTMLButtonElement>('[aria-label="Open item"]')!.click(),
  );
  await flush();
  expect(
    [...host.querySelectorAll("button")].some(
      (b) => b.textContent === "Approve",
    ),
  ).toBe(false);
});
it("ignores a response from a previous view", async () => {
  let resolve!: (v: Response) => void;
  overrides = (p) =>
    p.includes("/requests?")
      ? new Promise<Response>((r) => (resolve = r))
      : undefined;
  await boot();
  await click("Notifications");
  await act(async () =>
    resolve(
      json({
        items: [{ id: "stale", title: "Stale tenant data" }],
        hasMore: false,
      }),
    ),
  );
  await flush();
  expect(host.textContent).not.toContain("Stale tenant data");
  expect(host.textContent).toContain("REQUEST_SUBMITTED");
});
it("marks a notification read via its notification ID", async () => {
  await boot();
  await click("Notifications");
  await click("Read");
  expect(calls).toContain(
    "/api/v1/organizations/org/notifications/notification-id/read",
  );
});
it("clears data immediately and rejects stale results after changing organization", async () => {
  let resolve!: (v: Response) => void;
  overrides = (p) =>
    p.includes("/org/requests?")
      ? new Promise<Response>((r) => (resolve = r))
      : undefined;
  await boot();
  const input = host.querySelector<HTMLInputElement>(".orgbar input")!;
  await act(async () => {
    Object.getOwnPropertyDescriptor(
      HTMLInputElement.prototype,
      "value",
    )!.set!.call(input, "other-org");
    input.dispatchEvent(new Event("input", { bubbles: true }));
  });
  await flush();
  await act(async () =>
    resolve(
      json({
        items: [{ id: "private", title: "Old organization result" }],
        hasMore: false,
      }),
    ),
  );
  await flush();
  expect(host.textContent).not.toContain("Old organization result");
  expect(calls.some((p) => p.includes("/other-org/requests?"))).toBe(true);
});
it("does not clear the current list when clicking its active navigation item", async () => {
  await boot();
  await click("All requests");
  expect(host.textContent).toContain("Laptop request");
});
it("returns to authentication when a protected endpoint reports an expired session", async () => {
  await boot();
  overrides = (p) =>
    p.includes("offset=20")
      ? json(
          { status: 401, title: "Sign in required", detail: "Session expired" },
          401,
        )
      : undefined;
  await click("Next");
  expect(host.textContent).toContain("Sign in to GateFlow");
  expect(host.textContent).not.toContain("Tester");
});
