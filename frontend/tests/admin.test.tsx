// @vitest-environment jsdom
import { act } from "react";
import { createRoot, Root } from "react-dom/client";
import { beforeEach, afterEach, it, expect, vi } from "vitest";
import { WorkspaceAdmin, AuditLog } from "../src/admin";
let root: Root,
  host: HTMLDivElement,
  calls: { path: string; body: any }[] = [];
const response = (x: unknown) =>
  new Response(JSON.stringify(x), {
    headers: { "Content-Type": "application/json" },
  });
const tick = () =>
  act(async () => {
    await new Promise((r) => setTimeout(r, 0));
  });
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  calls = [];
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
  vi.stubGlobal(
    "fetch",
    vi.fn(async (path: string, init?: RequestInit) => {
      calls.push({
        path,
        body: init?.body ? JSON.parse(init.body as string) : null,
      });
      if (path.endsWith("/csrf")) return response({ token: "test" });
      if (path.endsWith("/org/permissions"))
        return response([{ code: "REQUEST_APPROVE" }, { code: "ROLE_MANAGE" }]);
      if (path.includes("/roles?"))
        return response({
          items: [
            {
              id: "r1",
              name: "Custom",
              code: "CUSTOM",
              system: false,
              version: 4,
              permissions: ["REQUEST_APPROVE"],
            },
          ],
          hasMore: false,
        });
      if (path.endsWith("/roles/r1/permissions"))
        return response({
          id: "r1",
          name: "Custom",
          version: 5,
          permissions: ["REQUEST_APPROVE"],
        });
      if (path.includes("/workflows?"))
        return response({
          items: [{ id: "policy", name: "Policy" }],
          hasMore: false,
        });
      if (path.endsWith("/versions/v1"))
        return response({
          id: "v1",
          status: "PUBLISHED",
          version: 2,
          versionNumber: 1,
          steps: [
            {
              name: "Review",
              approverRoleId: "r1",
              condition: { type: "ALWAYS" },
            },
          ],
        });
      if (path.endsWith("/audit-logs/a1"))
        return response({
          entry: { id: "a1" },
          oldValue: null,
          newValue: { version: 1 },
        });
      if (path.includes("/audit-logs?"))
        return response({
          items: [
            {
              id: "a1",
              action: "WORKFLOW_APPROVED",
              resourceType: "REQUEST",
              occurredAt: "2026-01-01T00:00:00Z",
            },
          ],
          hasMore: false,
        });
      return response({ items: [], hasMore: false });
    }),
  );
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  vi.unstubAllGlobals();
});
async function click(text: string) {
  const b = [...host.querySelectorAll("button")].find((x) =>
    x.textContent?.includes(text),
  );
  expect(b).toBeTruthy();
  await act(async () => b!.click());
  await tick();
}
function input(element: HTMLInputElement, value: string) {
  Object.getOwnPropertyDescriptor(
    HTMLInputElement.prototype,
    "value",
  )!.set!.call(element, value);
  element.dispatchEvent(new Event("input", { bubbles: true }));
}
it("updates custom grants using the displayed concurrency version", async () => {
  await act(async () =>
    root.render(
      <WorkspaceAdmin
        org="org"
        permissions={["ROLE_MANAGE", "REQUEST_APPROVE"]}
      />,
    ),
  );
  await tick();
  await click("Custom");
  await act(async () =>
    host
      .querySelector("form")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })),
  );
  await tick();
  const c = calls.find((c) => c.path.endsWith("/roles/r1/permissions"));
  expect(c?.body.expectedVersion).toBe(4);
  expect(host.textContent).toContain("version 5");
});
it("does not fetch protected directories for unauthorized users", async () => {
  await act(async () =>
    root.render(<WorkspaceAdmin org="org" permissions={[]} />),
  );
  await tick();
  expect(calls).toHaveLength(0);
  expect(host.textContent).toBe("");
});
it("keeps published workflow steps immutable", async () => {
  await act(async () =>
    root.render(
      <WorkspaceAdmin
        org="org"
        permissions={["WORKFLOW_VIEW", "WORKFLOW_UPDATE"]}
      />,
    ),
  );
  await tick();
  const select = host.querySelector<HTMLSelectElement>("select")!;
  await act(async () => {
    select.value = "policy";
    select.dispatchEvent(new Event("change", { bubbles: true }));
  });
  await act(async () =>
    input(host.querySelector<HTMLInputElement>("input")!, "v1"),
  );
  await click("Load policy version");
  expect(host.querySelector("fieldset")?.disabled).toBe(true);
  expect(host.textContent).toContain("PUBLISHED");
});
it("browses audit metadata and allowlisted detail without mutation routes", async () => {
  await act(async () => root.render(<AuditLog org="org" />));
  await tick();
  await click("WORKFLOW_APPROVED");
  expect(host.querySelector("pre")?.textContent).toContain('"newValue"');
  expect(calls.every((c) => c.body === null)).toBe(true);
});
