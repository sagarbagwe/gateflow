import React, { FormEvent, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  ApiError,
  auth,
  getCsrf,
  notifications,
  organizations,
  requests,
  User,
  workflows,
} from "./api";
import "./styles.css";
import { normalizeItems as rows } from "./view-model";
type View = "requests" | "inbox" | "notifications" | "setup";
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
  return (
    <div className="modal" role="dialog" aria-modal="true" aria-label={title}>
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
function App() {
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
    [roles, setRoles] = useState<any[]>([]),
    [members, setMembers] = useState<any[]>([]),
    [definitions, setDefinitions] = useState<any[]>([]),
    [access, setAccess] = useState<any>(null);
  async function activateUser(u: User) {
    setUser(u);
    const mine = await organizations.mine();
    const first = rows(mine)[0] as any;
    if (first) {
      setOrg(first.id);
      localStorage.setItem("gateflow.org", first.id);
    } else {
      setOrg("");
      setView("setup");
    }
  }
  useEffect(() => {
    getCsrf()
      .then(() => auth.me())
      .then(activateUser)
      .catch(() => {})
      .finally(() => setBoot(false));
  }, []);
  async function load(next = view) {
    if (!org || next === "setup") return;
    setLoading(true);
    setError(null);
    localStorage.setItem("gateflow.org", org);
    try {
      setData(
        await (next === "requests"
          ? requests.list(org, q)
          : next === "inbox"
            ? requests.inbox(org)
            : notifications.list(org)),
      );
    } catch (e) {
      setError(e as ApiError);
      setData(null);
    } finally {
      setLoading(false);
    }
  }
  async function loadSetup() {
    if (!org) return;
    setLoading(true);
    setError(null);
    try {
      const [r, m, w] = await Promise.all([
        organizations.roles(org),
        organizations.members(org),
        workflows.list(org),
      ]);
      setRoles(rows(r));
      setMembers(rows(m));
      setDefinitions(rows(w));
    } catch (e) {
      setError(e as ApiError);
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => {
    if (user && org) {
      void organizations
        .access(org)
        .then(setAccess)
        .catch(() => setAccess(null));
      if (view === "setup") void loadSetup();
      else void load(view);
    } else {
      setAccess(null);
    }
  }, [view, user, org]);
  async function openItem(id: string) {
    setError(null);
    try {
      setSelected(await requests.get(org, id));
    } catch (e) {
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
              onClick={() => setView(x)}
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
            onClick={() =>
              auth.logout().finally(() => {
                localStorage.removeItem("gateflow.org");
                setUser(null);
                setOrg("");
                setAccess(null);
                setData(null);
                setRoles([]);
                setMembers([]);
                setDefinitions([]);
                setSelected(null);
                setView("requests");
              })
            }
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
                    : "Workspace setup"}
            </h1>
          </div>
          {view !== "setup" && (
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
              onChange={(e) => setOrg(e.target.value)}
              placeholder="Paste organization UUID"
            />
          </label>
          <button
            onClick={() => (view === "setup" ? loadSetup() : load())}
            disabled={!org || loading}
          >
            Open workspace
          </button>
          <button onClick={() => setView("setup")}>Manage</button>
        </section>
        <ErrorBox error={error} />
        {view === "setup" ? (
          <Setup
            org={org}
            user={user}
            roles={roles}
            members={members}
            definitions={definitions}
            setOrg={setOrg}
            reload={loadSetup}
            setError={setError}
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
                    void load();
                  }}
                >
                  <input
                    aria-label="Search requests"
                    value={q}
                    onChange={(e) => setQ(e.target.value)}
                    placeholder="Search title or description"
                  />
                </form>
              )}
            </div>
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
                      className={`status ${(item.state || item.status || "new").toLowerCase()}`}
                    >
                      {item.state || item.status || item.type || "Update"}
                    </span>
                    <div>
                      <h3>
                        {item.title || item.message || "Approval request"}
                      </h3>
                      <p>
                        {item.description ||
                          item.workflowName ||
                          item.action ||
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
                      onClick={() => item.id && openItem(item.id)}
                    >
                      →
                    </button>
                  </article>
                ))}
              </div>
            )}
          </section>
        )}
        {requestOpen && (
          <NewRequest
            org={org}
            onClose={() => setRequestOpen(false)}
            onDone={() => {
              setRequestOpen(false);
              setView("requests");
              void load("requests");
            }}
            setError={setError}
          />
        )}{" "}
        {selected && (
          <RequestDetail
            org={org}
            request={selected}
            membershipId={access?.membershipId}
            setRequest={setSelected}
            onClose={() => setSelected(null)}
            onChanged={() => void load(view)}
            setError={setError}
          />
        )}
      </main>
    </div>
  );
}
function Setup({
  org,
  user,
  roles,
  members,
  definitions,
  setOrg,
  reload,
  setError,
}: {
  org: string;
  user: User;
  roles: any[];
  members: any[];
  definitions: any[];
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
      <section className="panel card">
        <h2>Reviewer access</h2>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            const f = new FormData(e.currentTarget);
            void run(async () => {
              await organizations.createReviewerRole(
                org,
                String(f.get("code")).toUpperCase(),
                String(f.get("name")),
              );
            });
          }}
        >
          <label>
            Role code
            <input name="code" defaultValue="APPROVER" required />
          </label>
          <label>
            Role name
            <input name="name" defaultValue="Approval reviewer" required />
          </label>
          <button className="primary" disabled={!org || busy}>
            Create reviewer role
          </button>
        </form>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            const f = new FormData(e.currentTarget);
            void run(async () => {
              await organizations.enroll(org, String(f.get("userId")), [
                String(f.get("roleId")),
              ]);
            });
          }}
        >
          <label>
            Reviewer user ID
            <input name="userId" required />
          </label>
          <label>
            Reviewer role
            <select name="roleId" required>
              <option value="">Choose role</option>
              {roles.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.name}
                </option>
              ))}
            </select>
          </label>
          <button className="primary" disabled={!org || busy}>
            Add reviewer
          </button>
        </form>
      </section>
      <section className="panel card">
        <h2>Approval policy</h2>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            const f = new FormData(e.currentTarget);
            void run(async () => {
              const d = await workflows.create(
                org,
                String(f.get("name")),
                String(f.get("description")),
              );
              const v = await workflows.createVersion(
                org,
                d.id,
                String(f.get("roleId")),
              );
              const p = await workflows.publish(org, d.id, v.id, v.version);
              localStorage.setItem(`gateflow.policy.${org}`, p.id);
            });
          }}
        >
          <label>
            Policy name
            <input name="name" defaultValue="Standard approval" required />
          </label>
          <label>
            Description
            <input name="description" defaultValue="One-step approval policy" />
          </label>
          <label>
            Approver role
            <select name="roleId" required>
              <option value="">Choose role</option>
              {roles
                .filter((r) => r.permissions?.includes("REQUEST_APPROVE"))
                .map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.name}
                  </option>
                ))}
            </select>
          </label>
          <button className="primary" disabled={!org || busy}>
            Create and publish policy
          </button>
        </form>
        <p>
          Saved policy version:{" "}
          <code>
            {org
              ? localStorage.getItem(`gateflow.policy.${org}`) || "None yet"
              : "None"}
          </code>
        </p>
      </section>
      <section className="panel card">
        <h2>Workspace directory</h2>
        <p>
          {roles.length} roles · {members.length} members · {definitions.length}{" "}
          workflows
        </p>
        <ul>
          {members.map((m) => (
            <li key={m.id}>
              {m.displayName || m.email} — {m.status}
            </li>
          ))}
        </ul>
      </section>
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
  const [busy, setBusy] = useState(false),
    [type, setType] = useState("PURCHASE");
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const f = new FormData(e.currentTarget),
      details =
        type === "SOFTWARE_ACCESS"
          ? { softwareName: String(f.get("detail")) }
          : type === "POLICY_EXCEPTION"
            ? { policyCode: String(f.get("detail")) }
            : { vendor: String(f.get("detail")) };
    try {
      await requests.submit(org, {
        workflowVersionId: String(f.get("workflowVersionId")),
        title: String(f.get("title")),
        description: String(f.get("description")),
        requestType: type,
        purchaseAmount: type === "PURCHASE" ? Number(f.get("amount")) : null,
        currency:
          type === "PURCHASE" ? String(f.get("currency")).toUpperCase() : null,
        details,
      });
      onDone();
    } catch (e) {
      setError(e as ApiError);
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal title="New approval request" onClose={onClose}>
      <form className="stack" onSubmit={submit}>
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
  setRequest: (r: any) => void;
  onClose: () => void;
  onChanged: () => void;
  setError: (e: ApiError | null) => void;
}) {
  const [comment, setComment] = useState("");
  async function act(fn: () => Promise<any>) {
    setError(null);
    try {
      const r = await fn();
      setRequest(r);
      onChanged();
    } catch (e) {
      setError(e as ApiError);
    }
  }
  const active = request.steps?.find((s: any) => s.state === "ACTIVE");
  return (
    <Modal title={request.title || "Request details"} onClose={onClose}>
      <div className="detail">
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
        {request.steps?.map((s: any) => (
          <div className="step" key={s.id}>
            <b>
              {s.position}. {s.name}
            </b>
            <span>{s.state}</span>
          </div>
        ))}
        {active?.assignedMembershipId === membershipId && (
          <>
            <label>
              Decision comment
              <textarea
                value={comment}
                onChange={(e) => setComment(e.target.value)}
              />
            </label>
            <div className="actions">
              <button
                className="danger"
                onClick={() =>
                  void act(() =>
                    requests.decide(
                      org,
                      request.id,
                      active.id,
                      request.version,
                      "REJECT",
                      comment,
                    ),
                  )
                }
              >
                Reject
              </button>
              <button
                className="primary"
                onClick={() =>
                  void act(() =>
                    requests.decide(
                      org,
                      request.id,
                      active.id,
                      request.version,
                      "APPROVE",
                      comment,
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
          request.requesterMembershipId === membershipId && (
            <button
              onClick={() =>
                void act(() =>
                  requests.withdraw(org, request.id, request.version),
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
            <li>Real-time reviewer inbox</li>
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
            <input name="displayName" required autoComplete="name" />
          </label>
        )}
        <label>
          Email
          <input name="email" type="email" required autoComplete="email" />
        </label>
        <label>
          Password
          <input
            name="password"
            type="password"
            required
            minLength={12}
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
createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
