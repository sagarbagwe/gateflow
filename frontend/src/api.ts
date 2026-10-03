import { CommandIntent } from "./command-intent";
export type ApiError = {
  status: number;
  title: string;
  detail: string;
  requestId?: string;
  code?: string;
};
export type User = { id: string; email: string; displayName?: string };
let csrf: string | undefined;
let csrfHeader = "X-XSRF-TOKEN";
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
  if (csrf && method !== "GET") headers.set(csrfHeader, csrf);
  let r: Response;
  try {
    r = await fetch(path, {
      ...init,
      signal: init.signal ?? AbortSignal.timeout(20000),
      headers,
      credentials: "include",
    });
  } catch {
    throw {
      status: 0,
      title: "Connection interrupted",
      detail:
        "The result is unknown. Retry the unchanged command to safely recover its result.",
    } satisfies ApiError;
  }
  if (r.status === 401 && typeof window !== "undefined")
    window.dispatchEvent(new Event("gateflow:unauthorized"));
  if (!r.ok) throw await problem(r);
  if (r.status === 204) return undefined as T;
  return r.json();
}
export async function getCsrf() {
  let r: Response;
  try {
    r = await fetch("/api/v1/auth/csrf", {
      credentials: "include",
      signal: AbortSignal.timeout(20000),
    });
  } catch {
    throw {
      status: 0,
      title: "Connection interrupted",
      detail:
        "Could not initialize a secure request. Check connectivity and retry.",
    } satisfies ApiError;
  }
  if (!r.ok) throw await problem(r);
  const b = await r.json();
  csrf = b.token || b.csrfToken;
  if (
    typeof csrf !== "string" ||
    !csrf ||
    (b.headerName && !["X-XSRF-TOKEN", "X-CSRF-TOKEN"].includes(b.headerName))
  ) {
    csrf = undefined;
    throw {
      status: 502,
      title: "Invalid security response",
      detail: "CSRF initialization returned an invalid response.",
    } satisfies ApiError;
  }
  csrfHeader = b.headerName || "X-XSRF-TOKEN";
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
  login: async (email: string, password: string) => {
    const user = await api<User>(
      "/api/v1/auth/login",
      json("POST", { email, password }),
    );
    csrf = undefined;
    return user;
  },
  signup: async (email: string, password: string, displayName: string) => {
    const user = await api<User>(
      "/api/v1/auth/signup",
      json("POST", { email, password, displayName }),
    );
    csrf = undefined;
    return user;
  },
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
  activity: (org: string, id: string, offset = 0) =>
    api<any>(`${base(org)}/requests/${id}/activity?limit=20&offset=${offset}`),
  reassign: (
    org: string,
    id: string,
    stepId: string,
    expectedVersion: number,
    membershipId: string,
    intent: CommandIntent,
  ) => {
    const path = `${base(org)}/requests/${id}/steps/${stepId}/reassign`,
      body = { expectedVersion, membershipId };
    return intent.run(path, body, (key) =>
      api<any>(path, json("POST", body, { "Idempotency-Key": key })),
    );
  },
  list: (
    org: string,
    q: string,
    offset = 0,
    filters: Record<string, string> = {},
  ) =>
    api<any>(
      `${base(org)}/requests?${new URLSearchParams({ limit: "20", pagination: "OFFSET", offset: String(offset), sort: "CREATED_DESC", ...Object.fromEntries(Object.entries(filters).filter(([, value]) => value)), ...(q ? { q } : {}) })}`,
    ),
  inbox: (org: string, offset = 0) =>
    api<any>(
      `${base(org)}/requests/inbox?limit=20&pagination=OFFSET&offset=${offset}&sort=CREATED_ASC`,
    ),
  get: (org: string, id: string) => api<any>(`${base(org)}/requests/${id}`),
  submit: (org: string, body: any, intent: CommandIntent) => {
    const path = `${base(org)}/requests`;
    return intent.run(path, body, (key) =>
      api<any>(path, json("POST", body, { "Idempotency-Key": key })),
    );
  },
  decide: (
    org: string,
    id: string,
    stepId: string,
    expectedVersion: number,
    decision: "APPROVE" | "REJECT",
    comment: string,
    intent: CommandIntent,
  ) =>
    intent.run(
      `${base(org)}/requests/${id}/steps/${stepId}/decisions`,
      { expectedVersion, decision, comment },
      (key) =>
        api<any>(
          `${base(org)}/requests/${id}/steps/${stepId}/decisions`,
          json(
            "POST",
            { expectedVersion, decision, comment },
            { "Idempotency-Key": key },
          ),
        ),
    ),
  withdraw: (
    org: string,
    id: string,
    expectedVersion: number,
    intent: CommandIntent,
  ) =>
    intent.run(
      `${base(org)}/requests/${id}/withdraw`,
      { expectedVersion },
      (key) =>
        api<any>(
          `${base(org)}/requests/${id}/withdraw`,
          json("POST", { expectedVersion }, { "Idempotency-Key": key }),
        ),
    ),
};
export const notifications = {
  list: (org: string, offset = 0) =>
    api<any>(`${base(org)}/notifications?limit=20&offset=${offset}`),
  read: (org: string, id: string) =>
    api<any>(`${base(org)}/notifications/${encodeURIComponent(id)}/read`, {
      method: "PATCH",
    }),
  preferences: (org: string) =>
    api<NotificationPreferences>(`${base(org)}/notifications/preferences`),
  savePreferences: (
    org: string,
    body: {
      inAppEnabled: boolean;
      emailEnabled: boolean;
      expectedVersion: number;
    },
  ) =>
    api<NotificationPreferences>(
      `${base(org)}/notifications/preferences`,
      json("PUT", body),
    ),
  count: (org: string) => api<any>(`${base(org)}/notifications/unread-count`),
};

export type NotificationPreferences = {
  inAppEnabled: boolean;
  emailEnabled: boolean;
  version: number;
};
export const administration = {
  roles: (org: string, offset = 0) =>
    api<any>(`${base(org)}/roles?limit=20&offset=${offset}`),
  members: (org: string, offset = 0) =>
    api<any>(`${base(org)}/memberships?limit=20&offset=${offset}`),
  catalog: (org: string) => api<any>(`${base(org)}/permissions`),
  createRole: (org: string, body: unknown) =>
    api<any>(`${base(org)}/roles`, json("POST", body)),
  grants: (org: string, id: string, body: unknown) =>
    api<any>(`${base(org)}/roles/${id}/permissions`, json("PUT", body)),
  memberRoles: (org: string, id: string, body: unknown) =>
    api<any>(`${base(org)}/memberships/${id}/roles`, json("PUT", body)),
  memberStatus: (org: string, id: string, body: unknown) =>
    api<any>(`${base(org)}/memberships/${id}/status`, json("PATCH", body)),
  policies: (org: string, offset = 0) =>
    api<any>(`${base(org)}/workflows?limit=20&offset=${offset}`),
  draft: (org: string, id: string, body: unknown) =>
    api<any>(`${base(org)}/workflows/${id}/versions`, json("POST", body)),
  policyVersion: (org: string, id: string, versionId: string) =>
    api<any>(`${base(org)}/workflows/${id}/versions/${versionId}`),
  updateDraft: (org: string, id: string, versionId: string, body: unknown) =>
    api<any>(
      `${base(org)}/workflows/${id}/versions/${versionId}`,
      json("PUT", body),
    ),
  audit: (org: string, offset = 0, filters: Record<string, string> = {}) =>
    api<any>(
      `${base(org)}/audit-logs?${new URLSearchParams({ limit: "20", offset: String(offset), ...Object.fromEntries(Object.entries(filters).filter(([, v]) => v)) })}`,
    ),
  auditEntry: (org: string, id: string) =>
    api<any>(`${base(org)}/audit-logs/${id}`),
};
