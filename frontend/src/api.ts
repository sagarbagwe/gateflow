export type ApiError = {
  status: number;
  title: string;
  detail: string;
  requestId?: string;
  code?: string;
};
export type User = { id: string; email: string; displayName?: string };
let csrf: string | undefined;
async function problem(r: Response): Promise<ApiError> {
  let b: any = {};
  try {
    b = await r.json();
  } catch {
    b = {};
  }
  return {
    status: r.status,
    title: b.title || "Request failed",
    detail: b.detail || `HTTP ${r.status}`,
    requestId: b.requestId,
    code: b.code,
  };
}
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method || "GET").toUpperCase();
  if (!csrf && method !== "GET") await getCsrf();
  const headers = new Headers(init.headers);
  if (init.body) headers.set("Content-Type", "application/json");
  if (csrf && method !== "GET") headers.set("X-XSRF-TOKEN", csrf);
  const r = await fetch(path, { ...init, headers, credentials: "include" });
  if (!r.ok) throw await problem(r);
  if (r.status === 204) return undefined as T;
  return r.json();
}
export async function getCsrf() {
  const r = await fetch("/api/v1/auth/csrf", { credentials: "include" });
  if (!r.ok) throw await problem(r);
  const b = await r.json();
  csrf = b.token || b.csrfToken;
  return b;
}
const base = (org: string) =>
  `/api/v1/organizations/${encodeURIComponent(org)}`;
const json = (
  method: string,
  body: unknown,
  headers?: HeadersInit,
): RequestInit => ({ method, body: JSON.stringify(body), headers });
export const auth = {
  me: () => api<User>("/api/v1/auth/me"),
  login: (email: string, password: string) =>
    api<User>("/api/v1/auth/login", json("POST", { email, password })),
  signup: (email: string, password: string, displayName: string) =>
    api<User>(
      "/api/v1/auth/signup",
      json("POST", { email, password, displayName }),
    ),
  logout: async () => {
    try {
      await api<void>("/api/v1/auth/logout", { method: "POST" });
    } finally {
      csrf = undefined;
    }
  },
};
export const organizations = {
  mine: () => api<any>("/api/v1/organizations?limit=100&offset=0"),
  create: (name: string, slug: string) =>
    api<any>("/api/v1/organizations", json("POST", { name, slug })),
  access: (org: string) => api<any>(`${base(org)}/me`),
  roles: (org: string) => api<any>(`${base(org)}/roles?limit=100&offset=0`),
  createReviewerRole: (org: string, code: string, name: string) =>
    api<any>(
      `${base(org)}/roles`,
      json("POST", {
        code,
        name,
        permissions: ["WORKFLOW_VIEW", "REQUEST_APPROVE", "REQUEST_VIEW_ALL"],
      }),
    ),
  members: (org: string) =>
    api<any>(`${base(org)}/memberships?limit=100&offset=0`),
  enroll: (org: string, userId: string, roleIds: string[]) =>
    api<any>(`${base(org)}/memberships`, json("POST", { userId, roleIds })),
};
export const workflows = {
  list: (org: string) => api<any>(`${base(org)}/workflows?limit=100&offset=0`),
  create: (org: string, name: string, description: string) =>
    api<any>(`${base(org)}/workflows`, json("POST", { name, description })),
  createVersion: (org: string, id: string, approverRoleId: string) =>
    api<any>(
      `${base(org)}/workflows/${id}/versions`,
      json("POST", {
        steps: [
          {
            name: "Approval review",
            approverRoleId,
            condition: { type: "ALWAYS" },
          },
        ],
      }),
    ),
  publish: (
    org: string,
    id: string,
    versionId: string,
    expectedVersion: number,
  ) =>
    api<any>(
      `${base(org)}/workflows/${id}/versions/${versionId}/publish`,
      json("POST", { expectedVersion }),
    ),
};
export const requests = {
  list: (org: string, q: string) =>
    api<any>(
      `${base(org)}/requests?limit=20&sort=CREATED_DESC${q ? `&q=${encodeURIComponent(q)}` : ""}`,
    ),
  inbox: (org: string) =>
    api<any>(`${base(org)}/requests/inbox?limit=20&sort=CREATED_ASC`),
  get: (org: string, id: string) => api<any>(`${base(org)}/requests/${id}`),
  submit: (org: string, body: any) =>
    api<any>(
      `${base(org)}/requests`,
      json("POST", body, { "Idempotency-Key": crypto.randomUUID() }),
    ),
  decide: (
    org: string,
    id: string,
    stepId: string,
    expectedVersion: number,
    decision: "APPROVE" | "REJECT",
    comment: string,
  ) =>
    api<any>(
      `${base(org)}/requests/${id}/steps/${stepId}/decisions`,
      json(
        "POST",
        { expectedVersion, decision, comment },
        { "Idempotency-Key": crypto.randomUUID() },
      ),
    ),
  withdraw: (org: string, id: string, expectedVersion: number) =>
    api<any>(
      `${base(org)}/requests/${id}/withdraw`,
      json(
        "POST",
        { expectedVersion },
        { "Idempotency-Key": crypto.randomUUID() },
      ),
    ),
};
export const notifications = {
  list: (org: string) => api<any>(`${base(org)}/notifications?limit=20`),
  count: (org: string) => api<any>(`${base(org)}/notifications/unread-count`),
};
