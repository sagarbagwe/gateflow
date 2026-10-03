import { FormEvent, useEffect, useState, useRef } from "react";
import {
  ApiError,
  auth,
  getCsrf,
  notifications,
  organizations,
  requests,
  User,
} from "./api";
import "./styles.css";
import { CommandIntent } from "./command-intent";
import { AsyncScope } from "./async-scope";
import { Preferences } from "./preferences";
import { WorkspaceAdmin, AuditLog } from "./admin";
import { Activity } from "./activity";
import { normalizeItems as rows } from "./view-model";
type View = "requests" | "inbox" | "notifications" | "setup" | "audit";
function ErrorBox({ error }: { error: ApiError | null }) {
  return error ? (
    <div className="alert" role="alert">
      <b>{error.title}</b>
      <span>{error.detail}</span>
      {error.requestId && <small>Request {error.requestId}</small>}
    </div>
  ) : null;
}
function Modal({
  title,
  onClose,
  children,
}: {
  title: string;
  onClose: () => void;
  children: React.ReactNode;
}) {
  const dialog = useRef<HTMLDivElement>(null);
  const close = useRef(onClose);
  close.current = onClose;
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const alreadyLocked = document.body.classList.contains("modal-open");
    document.body.classList.add("modal-open");
    const selector =
      'button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled),[tabindex="0"]';
    dialog.current?.querySelector<HTMLElement>(selector)?.focus();
    function keyboard(e: KeyboardEvent) {
      if (e.key === "Escape") {
        e.preventDefault();
        close.current();
      }
      if (e.key !== "Tab") return;
      const nodes = [
        ...(dialog.current?.querySelectorAll<HTMLElement>(selector) || []),
      ];
      const first = nodes[0],
        last = nodes[nodes.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last?.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first?.focus();
      }
    }
    document.addEventListener("keydown", keyboard);
    return () => {
      document.removeEventListener("keydown", keyboard);
      if (!alreadyLocked) document.body.classList.remove("modal-open");
      previous?.focus();
    };
  }, []);
  return (
    <div
      ref={dialog}
      className="modal"
      role="dialog"
      aria-modal="true"
      aria-label={title}
    >
      <section>
        <header>
          <h2>{title}</h2>
          <button type="button" aria-label="Close" onClick={onClose}>
            ×
          </button>
        </header>
        {children}
      </section>
    </div>
  );
}
export function App() {
  const scope = useRef(new AsyncScope());
  const currentOrg = useRef("");
  const workspaceEpoch = useRef(0);
  const renderedEpoch = workspaceEpoch.current;
  const inWorkspace = () => workspaceEpoch.current === renderedEpoch;
  const [offset, setOffset] = useState(0);
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [applied, setApplied] = useState<{
    q: string;
    filters: Record<string, string>;
  }>({ q: "", filters: {} });
  const [signingOut, setSigningOut] = useState(false);
  function resetWorkspace(next: string) {
    scope.current.invalidate();
    workspaceEpoch.current++;
    currentOrg.current = next;
    setOrg(next);
    setData(null);
    setAccess(null);
    setSelected(null);
    setRequestOpen(false);
    setError(null);
    setOffset(0);
    setFilters({});
    setQ("");
    setApplied({ q: "", filters: {} });
    setLoading(false);
    if (next) localStorage.setItem("gateflow.org", next);
    else localStorage.removeItem("gateflow.org");
  }
  function chooseView(next: View) {
    if (next === view) return;
    scope.current.invalidate();
    setData(null);
    setSelected(null);
    setError(null);
    setOffset(0);
    setView(next);
  }
  const [user, setUser] = useState<User | null>(null),
    [boot, setBoot] = useState(true),
    [error, setError] = useState<ApiError | null>(null),
    [org, setOrg] = useState(localStorage.getItem("gateflow.org") || ""),
    [view, setView] = useState<View>("requests"),
    [data, setData] = useState<any>(null),
    [loading, setLoading] = useState(false),
    [q, setQ] = useState(""),
    [requestOpen, setRequestOpen] = useState(false),
    [selected, setSelected] = useState<any>(null),
    [access, setAccess] = useState<any>(null);
  async function activateUser(u: User) {
    resetWorkspace("");
    setUser(u);
    const isCurrent = scope.current.start("account");
    try {
      const mine = await organizations.mine();
      if (!isCurrent()) return;
      const first = rows(mine)[0] as any;
      if (first) resetWorkspace(first.id);
      else setView("setup");
    } catch (e) {
      if (isCurrent()) setError(e as ApiError);
    }
  }
  useEffect(() => {
    let mounted = true;
    getCsrf()
      .then(() => auth.me())
      .then((u) => {
        if (mounted) return activateUser(u);
      })
      .catch((e) => {
        if (mounted && e.status !== 401) setError(e);
      })
      .finally(() => mounted && setBoot(false));
    return () => {
      mounted = false;
      scope.current.invalidate();
    };
  }, []);
  useEffect(() => {
    if (!user) return;
    function expired() {
      resetWorkspace("");
      setUser(null);
      setView("requests");
    }
    window.addEventListener("gateflow:unauthorized", expired);
    return () => window.removeEventListener("gateflow:unauthorized", expired);
  }, [user]);
  async function load(next = view, page = offset) {
    if (!org || next === "setup" || next === "audit") return;
    const isCurrent = scope.current.start("list");
    setLoading(true);
    setError(null);
    setData(null);
    try {
      const search = page === 0 ? { q, filters } : applied;
      const result = await (next === "requests"
        ? requests.list(org, search.q, page, search.filters)
        : next === "inbox"
          ? requests.inbox(org, page)
          : notifications.list(org, page));
      if (isCurrent()) {
        setData(result);
        setOffset(page);
        if (next === "requests") setApplied(search);
      }
    } catch (e) {
      if (isCurrent()) {
        setError(e as ApiError);
        setData(null);
      }
    } finally {
      if (isCurrent()) setLoading(false);
    }
  }
  async function loadSetup() {
    if (!org) return;
    const isCurrent = scope.current.start("setup");
    setLoading(true);
    setError(null);
    try {
      const grants = await organizations.access(org);
      if (!isCurrent()) return;
      setAccess(grants);
    } catch (e) {
      if (isCurrent()) setError(e as ApiError);
    } finally {
      if (isCurrent()) setLoading(false);
    }
  }
  useEffect(() => {
    if (!user || !org) return;
    const isCurrent = scope.current.start("access");
    void organizations
      .access(org)
      .then((value) => {
        if (isCurrent()) setAccess(value);
      })
      .catch(() => {
        if (isCurrent()) setAccess(null);
      });
    if (view === "setup") void loadSetup();
    else if (view !== "audit") void load(view, 0);
    return () => {
      scope.current.invalidate();
    };
  }, [view, user, org]);
  async function openItem(id: string) {
    const isCurrent = scope.current.start("detail");
    setError(null);
    setSelected(null);
    try {
      const result = await requests.get(org, id);
      if (isCurrent()) setSelected(result);
    } catch (e) {
      if (isCurrent()) setError(e as ApiError);
    }
  }
  async function signOut() {
    if (signingOut) return;
    setSigningOut(true);
    setError(null);
    try {
      await auth.logout();
      resetWorkspace("");
      setUser(null);
      setView("requests");
    } catch (e) {
      setError(e as ApiError);
    } finally {
      setSigningOut(false);
    }
  }
  async function markRead(id: string) {
    const workspace = org;
    try {
      await notifications.read(workspace, id);
      if (currentOrg.current === workspace && inWorkspace())
        await load("notifications");
    } catch (e) {
      if (currentOrg.current === workspace && inWorkspace())
        setError(e as ApiError);
    }
  }
  if (boot)
    return (
      <main className="center">
        <div className="spinner" />
        <p>Opening your workspace…</p>
      </main>
    );
  if (!user) return <Auth onUser={activateUser} />;
  const items = rows(data),
    nav: [View, string][] = [
      ["requests", "All requests"],
      ["inbox", "Review inbox"],
      ["notifications", "Notifications"],
      ["setup", "Workspace setup"],
      ...(access?.permissions?.includes("AUDIT_VIEW")
        ? [["audit", "Audit logs"] as [View, string]]
        : []),
    ];
  return (
    <div className="shell">
      <aside>
        <a className="brand" href="#">
          <span>G</span>GateFlow
        </a>
        <nav aria-label="Primary">
          {nav.map(([x, label]) => (
            <button
              key={x}
              className={view === x ? "active" : ""}
              onClick={() => chooseView(x)}
            >
              {label}
            </button>
          ))}
        </nav>
        <div className="profile">
          <div className="avatar">
            {(user.displayName || user.email).slice(0, 1).toUpperCase()}
          </div>
          <div>
            <b>{user.displayName || "Account"}</b>
            <small>{user.email}</small>
            <small title="User ID">{user.id}</small>
          </div>
          <button
            aria-label="Sign out"
            disabled={signingOut}
            onClick={() => void signOut()}
          >
            ↗
          </button>
        </div>
      </aside>
      <main>
        <header>
          <div>
            <p className="eyebrow">Approval workspace</p>
            <h1>
              {view === "requests"
                ? "Requests"
                : view === "inbox"
                  ? "Review inbox"
                  : view === "notifications"
                    ? "Notifications"
                    : view === "audit"
                      ? "Audit logs"
                      : "Workspace setup"}
            </h1>
          </div>
          {view !== "setup" && view !== "audit" && (
            <button
              className="primary"
              disabled={
                !org || !access?.permissions?.includes("REQUEST_SUBMIT")
              }
              onClick={() => setRequestOpen(true)}
            >
              + New request
            </button>
          )}
        </header>
        <section className="orgbar">
          <label>
            Organization ID
            <input
              value={org}
              onChange={(e) => resetWorkspace(e.target.value)}
              placeholder="Paste organization UUID"
            />
          </label>
          <button
            onClick={() => (view === "setup" ? loadSetup() : load())}
            disabled={!org || loading}
          >
            Open workspace
          </button>
          <button onClick={() => chooseView("setup")}>Manage</button>
        </section>
        <ErrorBox error={error} />
        {view === "audit" ? (
          <AuditLog key={org} org={org} />
        ) : view === "setup" ? (
          <Setup
            key={org}
            org={org}
            permissions={access?.permissions || []}
            user={user}
            setOrg={resetWorkspace}
            reload={loadSetup}
            setError={(e) => {
              if (currentOrg.current === org && inWorkspace()) setError(e);
            }}
          />
        ) : (
          <section className="panel">
            <div className="toolbar">
              <div>
                <h2>
                  {view === "requests"
                    ? "Request activity"
                    : view === "inbox"
                      ? "Waiting for your decision"
                      : "Recent updates"}
                </h2>
                <p>
                  {view === "requests"
                    ? "Track submitted approvals across your organization."
                    : view === "inbox"
                      ? "Oldest items appear first so nothing gets stuck."
                      : "Private updates for requests you can currently access."}
                </p>
              </div>
              {view === "requests" && (
                <form
                  onSubmit={(e) => {
                    e.preventDefault();
                    void load("requests", 0);
                  }}
                >
                  <input
                    maxLength={200}
                    aria-label="Search requests"
                    value={q}
                    onChange={(e) => setQ(e.target.value)}
                    placeholder="Search title or description"
                  />
                </form>
              )}
            </div>
            {view === "requests" && (
              <form
                className="filters"
                onSubmit={(e) => {
                  e.preventDefault();
                  void load("requests", 0);
                }}
              >
                <label>
                  Status
                  <select
                    value={filters.status || ""}
                    onChange={(e) =>
                      setFilters({ ...filters, status: e.target.value })
                    }
                  >
                    <option value="">All statuses</option>
                    {["IN_REVIEW", "APPROVED", "REJECTED", "WITHDRAWN"].map(
                      (x) => (
                        <option key={x}>{x}</option>
                      ),
                    )}
                  </select>
                </label>
                <label>
                  Sort
                  <select
                    value={filters.sort || "CREATED_DESC"}
                    onChange={(e) =>
                      setFilters({ ...filters, sort: e.target.value })
                    }
                  >
                    <option value="CREATED_DESC">Newest first</option>
                    <option value="CREATED_ASC">Oldest first</option>
                  </select>
                </label>
                <label>
                  Workflow definition UUID
                  <input
                    value={filters.workflowId || ""}
                    onChange={(e) =>
                      setFilters({ ...filters, workflowId: e.target.value })
                    }
                  />
                </label>
                <label>
                  Type
                  <select
                    value={filters.type || ""}
                    onChange={(e) =>
                      setFilters({ ...filters, type: e.target.value })
                    }
                  >
                    <option value="">All types</option>
                    {["PURCHASE", "SOFTWARE_ACCESS", "POLICY_EXCEPTION"].map(
                      (x) => (
                        <option key={x}>{x}</option>
                      ),
                    )}
                  </select>
                </label>
                <label>
                  Created from
                  <input
                    type="datetime-local"
                    onChange={(e) =>
                      setFilters({
                        ...filters,
                        createdFrom: e.target.value
                          ? new Date(e.target.value).toISOString()
                          : "",
                      })
                    }
                  />
                </label>
                <label>
                  Created before
                  <input
                    type="datetime-local"
                    onChange={(e) =>
                      setFilters({
                        ...filters,
                        createdBefore: e.target.value
                          ? new Date(e.target.value).toISOString()
                          : "",
                      })
                    }
                  />
                </label>
                <button type="submit" disabled={loading}>
                  Apply filters
                </button>
              </form>
            )}
            {view === "notifications" && <Preferences key={org} org={org} />}

            {loading ? (
              <div className="state">
                <div className="spinner" />
                Loading…
              </div>
            ) : !org ? (
              <div className="state">
                <b>Choose an organization</b>
                <span>Create or enter a workspace ID.</span>
              </div>
            ) : items.length === 0 ? (
              <div className="state">
                <div className="emptyIcon">✓</div>
                <b>
                  {view === "inbox"
                    ? "You’re all caught up"
                    : "Nothing here yet"}
                </b>
                <span>
                  {view === "requests"
                    ? "Create your first approval request."
                    : "There are no items requiring attention."}
                </span>
              </div>
            ) : (
              <div className="list">
                {items.map((item: any, i: number) => (
                  <article key={item.id || i}>
                    <span
                      className={`status ${(item.state || item.eventState || item.kind || "new").toLowerCase()}`}
                    >
                      {item.state || item.eventState || item.kind || "Update"}
                    </span>
                    <div>
                      <h3>{item.title || item.kind || "Approval update"}</h3>
                      <p>
                        {item.description ||
                          item.workflowName ||
                          item.eventType ||
                          "Open for details"}
                      </p>
                    </div>
                    <time>
                      {item.createdAt || item.submittedAt
                        ? new Date(
                            item.createdAt || item.submittedAt,
                          ).toLocaleDateString()
                        : ""}
                    </time>
                    <button
                      aria-label="Open item"
                      onClick={() => {
                        const id =
                          view === "notifications" ? item.requestId : item.id;
                        if (id) void openItem(id);
                      }}
                    >
                      →
                    </button>
                    {view === "notifications" && !item.readAt && (
                      <button
                        onClick={() => void markRead(item.id)}
                        aria-label="Mark notification read"
                      >
                        Read
                      </button>
                    )}
                  </article>
                ))}
              </div>
            )}
            <div className="pagination" aria-label="Pagination">
              <button
                disabled={loading || offset === 0}
                onClick={() => void load(view, Math.max(0, offset - 20))}
              >
                Previous
              </button>
              <span>Page {Math.floor(offset / 20) + 1}</span>
              <button
                disabled={loading || !data?.hasMore || offset >= 10000}
                onClick={() => void load(view, offset + 20)}
              >
                Next
              </button>
            </div>
          </section>
        )}
        {requestOpen && (
          <NewRequest
            key={org}
            org={org}
            onClose={() => setRequestOpen(false)}
            onDone={() => {
              if (currentOrg.current !== org || !inWorkspace()) return;
              setRequestOpen(false);
              setView("requests");
              void load("requests");
            }}
            setError={(e) => {
              if (currentOrg.current === org && inWorkspace()) setError(e);
            }}
          />
        )}{" "}
        {selected && (
          <RequestDetail
            key={`${org}:${selected.id}`}
            org={org}
            permissions={access?.permissions || []}
            request={selected}
            membershipId={access?.membershipId}
            setRequest={(r) => {
              if (currentOrg.current === org && inWorkspace()) setSelected(r);
            }}
            onClose={() => setSelected(null)}
            onChanged={() => {
              if (currentOrg.current === org && inWorkspace()) void load(view);
            }}
            setError={(e) => {
              if (currentOrg.current === org && inWorkspace()) setError(e);
            }}
          />
        )}
      </main>
    </div>
  );
}
function Setup({
  permissions,
  org,
  user,
  setOrg,
  reload,
  setError,
}: {
  org: string;
  permissions: string[];
  user: User;
  setOrg: (s: string) => void;
  reload: () => Promise<void>;
  setError: (e: ApiError | null) => void;
}) {
  const [busy, setBusy] = useState(false);
  async function run(fn: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try {
      await fn();
      await reload();
    } catch (e) {
      setError(e as ApiError);
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="setupGrid">
      <section className="panel card">
        <h2>Create workspace</h2>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            const f = new FormData(e.currentTarget);
            void run(async () => {
              const o = await organizations.create(
                String(f.get("name")),
                String(f.get("slug")),
              );
              setOrg(o.id);
              localStorage.setItem("gateflow.org", o.id);
            });
          }}
        >
          <label>
            Name
            <input name="name" required />
          </label>
          <label>
            Slug
            <input name="slug" required pattern="[a-z0-9]+(?:-[a-z0-9]+)*" />
          </label>
          <button className="primary" disabled={busy}>
            Create workspace
          </button>
        </form>
        <p>
          <b>Your user ID</b>
          <code>{user.id}</code>
        </p>
      </section>
      <WorkspaceAdmin key={org} org={org} permissions={permissions} />
    </div>
  );
}
function NewRequest({
  org,
  onClose,
  onDone,
  setError,
}: {
  org: string;
  onClose: () => void;
  onDone: () => void;
  setError: (e: ApiError | null) => void;
}) {
  const intent = useRef(new CommandIntent());
  const [localError, setLocalError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false),
    [type, setType] = useState("PURCHASE");
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setLocalError(null);
    const f = new FormData(e.currentTarget),
      details =
        type === "SOFTWARE_ACCESS"
          ? { softwareName: String(f.get("detail")) }
          : type === "POLICY_EXCEPTION"
            ? { policyCode: String(f.get("detail")) }
            : { vendor: String(f.get("detail")) };
    try {
      await requests.submit(
        org,
        {
          workflowVersionId: String(f.get("workflowVersionId")),
          title: String(f.get("title")),
          description: String(f.get("description")),
          requestType: type,
          purchaseAmount: type === "PURCHASE" ? Number(f.get("amount")) : null,
          currency:
            type === "PURCHASE"
              ? String(f.get("currency")).toUpperCase()
              : null,
          details,
        },
        intent.current,
      );
      onDone();
    } catch (e) {
      setLocalError(e as ApiError);
      setError(e as ApiError);
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal title="New approval request" onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <ErrorBox error={localError} />
        <label>
          Published policy version ID
          <input
            name="workflowVersionId"
            defaultValue={localStorage.getItem(`gateflow.policy.${org}`) || ""}
            required
          />
        </label>
        <label>
          Title
          <input name="title" required maxLength={200} />
        </label>
        <label>
          Description
          <textarea name="description" required maxLength={20000} />
        </label>
        <label>
          Request type
          <select value={type} onChange={(e) => setType(e.target.value)}>
            <option value="PURCHASE">Purchase</option>
            <option value="SOFTWARE_ACCESS">Software access</option>
            <option value="POLICY_EXCEPTION">Policy exception</option>
          </select>
        </label>
        {type === "PURCHASE" && (
          <div className="row">
            <label>
              Amount
              <input
                name="amount"
                type="number"
                min="0.01"
                step="0.01"
                required
              />
            </label>
            <label>
              Currency
              <input
                name="currency"
                defaultValue="USD"
                pattern="[A-Za-z]{3}"
                required
              />
            </label>
          </div>
        )}
        <label>
          {type === "PURCHASE"
            ? "Vendor"
            : type === "SOFTWARE_ACCESS"
              ? "Software name"
              : "Policy code"}
          <input name="detail" required />
        </label>
        <div className="actions">
          <button type="button" onClick={onClose}>
            Cancel
          </button>
          <button className="primary" disabled={busy}>
            {busy ? "Submitting…" : "Submit request"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
function RequestDetail({
  permissions,
  org,
  request,
  membershipId,
  setRequest,
  onClose,
  onChanged,
  setError,
}: {
  org: string;
  request: any;
  membershipId?: string;
  permissions: string[];
  setRequest: (r: any) => void;
  onClose: () => void;
  onChanged: () => void;
  setError: (e: ApiError | null) => void;
}) {
  const [comment, setComment] = useState("");
  const [localError, setLocalError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const decisionIntent = useRef(new CommandIntent());
  const withdrawIntent = useRef(new CommandIntent());
  const reassignIntent = useRef(new CommandIntent());
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  async function act(fn: () => Promise<any>) {
    if (busy) return;
    setBusy(true);
    setError(null);
    setLocalError(null);
    try {
      const r = await fn();
      if (mounted.current) {
        setRequest(r);
        onChanged();
      }
    } catch (e) {
      if (mounted.current) {
        setLocalError(e as ApiError);
        setError(e as ApiError);
      }
    } finally {
      if (mounted.current) setBusy(false);
    }
  }
  const active = request.steps?.find((s: any) => s.state === "ACTIVE");
  return (
    <Modal title={request.title || "Request details"} onClose={onClose}>
      <div className="detail">
        <ErrorBox error={localError} />
        <span className={`status ${(request.state || "new").toLowerCase()}`}>
          {request.state}
        </span>
        <p>{request.description}</p>
        <dl>
          <dt>Type</dt>
          <dd>{request.requestType}</dd>
          {request.purchaseAmount && (
            <>
              <dt>Amount</dt>
              <dd>
                {request.purchaseAmount} {request.currency}
              </dd>
            </>
          )}
          <dt>Version</dt>
          <dd>{request.version}</dd>
        </dl>
        <h3>Approval steps</h3>
        <Activity
          key={`${request.id}:${request.version}`}
          org={org}
          requestId={request.id}
        />
        {permissions.includes("REQUEST_REASSIGN") &&
          permissions.includes("REQUEST_VIEW_ALL") &&
          request.state === "IN_REVIEW" && (
            <form
              onSubmit={(e) => {
                e.preventDefault();
                const f = new FormData(e.currentTarget);
                void act(() =>
                  requests.reassign(
                    org,
                    request.id,
                    String(f.get("stepId")),
                    request.version,
                    String(f.get("membershipId")),
                    reassignIntent.current,
                  ),
                );
              }}
            >
              <label>
                Step to reassign
                <select name="stepId" required>
                  {request.steps
                    ?.filter(
                      (s: any) => s.state === "ACTIVE" || s.state === "WAITING",
                    )
                    .map((s: any) => (
                      <option key={s.id} value={s.id}>
                        {s.position}. {s.name}
                      </option>
                    ))}
                </select>
              </label>
              <label>
                Eligible reviewer membership UUID
                <input
                  name="membershipId"
                  required
                  pattern="[0-9a-fA-F-]{36}"
                />
              </label>
              <button disabled={busy}>Reassign reviewer</button>
            </form>
          )}
        {request.steps?.map((s: any) => (
          <div className="step" key={s.id}>
            <b>
              {s.position}. {s.name}
            </b>
            <span>{s.state}</span>
          </div>
        ))}
        {active &&
          active.assignedMembershipId === membershipId &&
          permissions.includes("REQUEST_APPROVE") && (
            <>
              <label>
                Decision comment
                <textarea
                  maxLength={2000}
                  disabled={busy}
                  value={comment}
                  onChange={(e) => setComment(e.target.value)}
                />
              </label>
              <div className="actions">
                <button
                  className="danger"
                  disabled={busy}
                  onClick={() =>
                    void act(() =>
                      requests.decide(
                        org,
                        request.id,
                        active.id,
                        request.version,
                        "REJECT",
                        comment,
                        decisionIntent.current,
                      ),
                    )
                  }
                >
                  Reject
                </button>
                <button
                  className="primary"
                  disabled={busy}
                  onClick={() =>
                    void act(() =>
                      requests.decide(
                        org,
                        request.id,
                        active.id,
                        request.version,
                        "APPROVE",
                        comment,
                        decisionIntent.current,
                      ),
                    )
                  }
                >
                  Approve
                </button>
              </div>
            </>
          )}
        {request.state === "IN_REVIEW" &&
          request.requesterMembershipId === membershipId &&
          permissions.includes("REQUEST_WITHDRAW_OWN") && (
            <button
              disabled={busy}
              onClick={() =>
                void act(() =>
                  requests.withdraw(
                    org,
                    request.id,
                    request.version,
                    withdrawIntent.current,
                  ),
                )
              }
            >
              Withdraw request
            </button>
          )}
      </div>
    </Modal>
  );
}
function Auth({ onUser }: { onUser: (u: User) => void | Promise<void> }) {
  const [signup, setSignup] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<ApiError | null>(null);
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const f = new FormData(e.currentTarget);
    try {
      await onUser(
        signup
          ? await auth.signup(
              String(f.get("email")),
              String(f.get("password")),
              String(f.get("displayName")),
            )
          : await auth.login(String(f.get("email")), String(f.get("password"))),
      );
    } catch (x) {
      setError(x as ApiError);
    } finally {
      setBusy(false);
    }
  }
  return (
    <main className="auth">
      <section>
        <a className="brand" href="#">
          <span>G</span>GateFlow
        </a>
        <div className="authcopy">
          <p className="eyebrow">Approvals without the chase</p>
          <h1>Move decisions forward with confidence.</h1>
          <p>
            One clear workspace for requests, reviewers, evidence, and every
            step between.
          </p>
          <ul>
            <li>Versioned approval policies</li>
            <li>Current reviewer inbox</li>
            <li>Traceable decision history</li>
          </ul>
        </div>
        <small>Secure by default · Organization scoped</small>
      </section>
      <form onSubmit={submit}>
        <div>
          <p className="eyebrow">Welcome</p>
          <h2>{signup ? "Create your account" : "Sign in to GateFlow"}</h2>
          <p>
            {signup
              ? "Start a secure approval workspace."
              : "Use your work account to continue."}
          </p>
        </div>
        <ErrorBox error={error} />
        {signup && (
          <label>
            Display name
            <input
              name="displayName"
              required
              maxLength={120}
              autoComplete="name"
            />
          </label>
        )}
        <label>
          Email
          <input
            name="email"
            type="email"
            required
            maxLength={254}
            autoComplete="email"
          />
        </label>
        <label>
          Password
          <input
            name="password"
            type="password"
            required
            minLength={signup ? 12 : undefined}
            maxLength={64}
            autoComplete={signup ? "new-password" : "current-password"}
          />
        </label>
        <button className="primary" disabled={busy}>
          {busy ? "Please wait…" : signup ? "Create account" : "Sign in"}
        </button>
        <button
          type="button"
          className="link"
          onClick={() => {
            setSignup(!signup);
            setError(null);
          }}
        >
          {signup
            ? "Already have an account? Sign in"
            : "New to GateFlow? Create an account"}
        </button>
      </form>
    </main>
  );
}
