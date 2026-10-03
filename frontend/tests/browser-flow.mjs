import { chromium } from "playwright";
import { createServer } from "node:http";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
const frontend = fileURLToPath(new URL("..", import.meta.url));
const root = path.join(frontend, "dist"),
  out = process.env.GATEFLOW_QA_DIR || path.join(frontend, ".qa");
await mkdir(out, { recursive: true });
const headers = JSON.parse(
  await readFile(path.join(frontend, "vercel.json"), "utf8"),
).headers[0].headers;
const server = createServer(async (req, res) => {
  try {
    const file = path.join(
      root,
      req.url === "/" ? "index.html" : req.url.split("?")[0],
    );
    const bytes = await readFile(file);
    for (const { key, value } of headers) res.setHeader(key, value);
    res.setHeader(
      "Content-Type",
      file.endsWith(".js")
        ? "application/javascript"
        : file.endsWith(".css")
          ? "text/css"
          : "text/html",
    );
    res.end(bytes);
  } catch {
    res.writeHead(404);
    res.end();
  }
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const origin = `http://127.0.0.1:${server.address().port}`;
let browser;
const errors = [];
let screenshots = 0;
try {
  browser = await chromium.launch({
    executablePath: process.env.GATEFLOW_CHROMIUM || undefined,
    headless: true,
    args: ["--no-sandbox"],
  });
  const page = await browser.newPage({
    viewport: { width: 1440, height: 1000 },
  });
  page.on("pageerror", (e) => errors.push(e.message));
  let loggedIn = false,
    failLogout = true;
  const calls = [];
  await page.route("**/api/**", async (route) => {
    const req = route.request(),
      p = new URL(req.url()).pathname;
    calls.push(p);
    let body = { items: [], hasMore: false },
      status = 200;
    if (p.endsWith("/csrf"))
      body = { headerName: "X-XSRF-TOKEN", token: "test-csrf" };
    else if (p.endsWith("/auth/me")) {
      body = loggedIn
        ? { id: "u1", email: "qa@example.invalid", displayName: "QA Admin" }
        : { status: 401, detail: "Sign in required" };
      status = loggedIn ? 200 : 401;
    } else if (p.endsWith("/auth/login")) {
      loggedIn = true;
      body = { id: "u1", email: "qa@example.invalid", displayName: "QA Admin" };
    } else if (p.endsWith("/auth/logout")) {
      if (failLogout) {
        status = 503;
        body = { status: 503, title: "Unavailable", detail: "Retry logout" };
      } else {
        loggedIn = false;
        status = 204;
      }
    } else if (p === "/api/v1/organizations") body = { items: [{ id: "org" }] };
    else if (p === "/api/v1/organizations/org/me")
      body = {
        membershipId: "member",
        permissions: [
          "REQUEST_SUBMIT",
          "REQUEST_WITHDRAW_OWN",
          "REQUEST_APPROVE",
          "ROLE_MANAGE",
          "MEMBERSHIP_MANAGE",
          "WORKFLOW_VIEW",
          "WORKFLOW_UPDATE",
          "WORKFLOW_CREATE",
          "WORKFLOW_PUBLISH",
          "AUDIT_VIEW",
          "REQUEST_VIEW_ALL",
        ],
      };
    else if (p.endsWith("/requests"))
      body = {
        items: [
          {
            id: "request-id",
            title: "Equipment for the engineering team",
            state: "IN_REVIEW",
            requestType: "PURCHASE",
            createdAt: "2026-10-03T10:00:00Z",
          },
        ],
        hasMore: true,
      };
    else if (p.endsWith("/notifications"))
      body = {
        items: [
          {
            id: "notification-id",
            requestId: "request-id",
            kind: "REVIEW",
            eventType: "REQUEST_SUBMITTED",
            readAt: null,
            createdAt: "2026-10-03T10:00:00Z",
          },
        ],
        hasMore: false,
      };
    else if (p.endsWith("/preferences"))
      body = { inAppEnabled: true, emailEnabled: false, version: 0 };
    else if (p.endsWith("/requests/request-id"))
      body = {
        id: "request-id",
        title: "Equipment for the engineering team",
        description: "Replace two unreliable developer laptops.",
        state: "IN_REVIEW",
        version: 0,
        requestType: "PURCHASE",
        purchaseAmount: 2000,
        currency: "USD",
        requesterMembershipId: "member",
        steps: [
          {
            id: "s1",
            position: 1,
            name: "Manager review",
            state: "ACTIVE",
            assignedMembershipId: "reviewer",
          },
        ],
      };
    else if (p.endsWith("/activity"))
      body = {
        activity: {
          items: [
            {
              eventId: "event",
              type: "REQUEST_SUBMITTED",
              state: "IN_REVIEW",
              occurredAt: "2026-10-03T10:00:00Z",
            },
          ],
          hasMore: false,
        },
        eventuallyConsistent: true,
      };
    else if (p.endsWith("/roles"))
      body = {
        items: [
          {
            id: "role-id",
            name: "Reviewers",
            code: "REVIEWER",
            system: false,
            version: 0,
            permissions: ["REQUEST_APPROVE"],
          },
        ],
        hasMore: false,
      };
    else if (p.endsWith("/permissions"))
      body = [
        { code: "REQUEST_APPROVE" },
        { code: "WORKFLOW_VIEW" },
        { code: "ROLE_MANAGE" },
      ];
    else if (p.endsWith("/workflows"))
      body = {
        items: [{ id: "policy-id", name: "Equipment approval" }],
        hasMore: false,
      };
    else if (p.endsWith("/audit-logs"))
      body = {
        items: [
          {
            id: "a1",
            action: "REQUEST_SUBMITTED",
            resourceType: "REQUEST",
            occurredAt: "2026-10-03T10:00:00Z",
          },
        ],
        hasMore: false,
      };
    else if (p.endsWith("/audit-logs/a1"))
      body = {
        entry: { action: "REQUEST_SUBMITTED", resourceType: "REQUEST" },
        oldValue: null,
        newValue: { version: 0 },
        snapshotRedacted: false,
      };
    await route.fulfill({
      status,
      contentType: "application/json",
      body: status === 204 ? "" : JSON.stringify(body),
    });
  });
  async function capture(name) {
    await page.waitForTimeout(100);
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth > innerWidth,
    );
    if (overflow) throw new Error(`${name}: horizontal overflow`);
    await page.screenshot({ path: `${out}/${name}.png`, fullPage: true });
    let html = await page.content();
    const css = await page.locator("link[rel=stylesheet]").getAttribute("href");
    html = html
      .replace(
        /<link[^>]*rel="stylesheet"[^>]*>/,
        `<style>${await readFile(path.join(root, css), "utf8")}</style>`,
      )
      .replace(/<script[\s\S]*?<\/script>/g, "");
    await writeFile(`${out}/${name}.html`, html);
    screenshots++;
  }
  await page.goto(origin);
  await page.getByRole("heading", { name: "Sign in to GateFlow" }).waitFor();
  await capture("login-desktop");
  await page
    .getByRole("textbox", { name: "Email", exact: true })
    .fill("qa@example.invalid");
  await page
    .getByRole("textbox", { name: "Password", exact: true })
    .fill("Test password only 123");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await page.getByRole("heading", { name: "Requests", exact: true }).waitFor();
  await capture("requests-desktop");
  await page.getByRole("button", { name: "Next", exact: true }).click();
  await page.getByText("Page 2", { exact: true }).waitFor();
  await page
    .getByRole("button", { name: "Notifications", exact: true })
    .click();
  await page.getByText("REQUEST_SUBMITTED", { exact: true }).waitFor();
  await page.setViewportSize({ width: 390, height: 844 });
  await capture("notifications-mobile");
  await page.getByRole("button", { name: "Open item", exact: true }).click();
  await page.getByRole("dialog").waitFor();
  if (!calls.includes("/api/v1/organizations/org/requests/request-id"))
    throw new Error("Wrong notification route");
  await capture("request-detail-mobile");
  await page.getByRole("button", { name: "Close", exact: true }).click();
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page
    .getByRole("button", { name: "Workspace setup", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Reviewers (custom)", exact: true })
    .waitFor();
  await capture("roles-desktop");
  await page
    .getByRole("button", { name: "Workflow policies", exact: true })
    .click();
  await page.getByText("Ordered sequential steps", { exact: true }).waitFor();
  await capture("policy-desktop");
  await page.getByRole("button", { name: "Audit logs", exact: true }).click();
  await page
    .getByRole("button", { name: /REQUEST_SUBMITTED · REQUEST/ })
    .click();
  await page.getByRole("heading", { name: "Selected evidence" }).waitFor();
  await capture("audit-desktop");
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await page.getByText("Retry logout", { exact: true }).waitFor();
  await capture("logout-error");
  failLogout = false;
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await page.getByRole("heading", { name: "Sign in to GateFlow" }).waitFor();
  if (errors.length) throw new Error(errors.join("; "));
  console.log(
    `PASS: browser flow with mocked APIs; ${screenshots} states, desktop/mobile, pagination, notification navigation, admin, audit, logout; no uncaught page errors`,
  );
} finally {
  if (browser) await browser.close();
  await new Promise((r) => server.close(r));
}
