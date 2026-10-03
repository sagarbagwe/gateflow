import { useEffect, useRef, useState } from "react";
import {
  administration as api,
  organizations,
  workflows,
  type ApiError,
} from "./api";
import { AsyncScope } from "./async-scope";
export function WorkspaceAdmin({
  org,
  permissions,
}: {
  org: string;
  permissions: string[];
}) {
  const [tab, setTab] = useState("roles"),
    [page, setPage] = useState(0),
    [data, setData] = useState<any>(null),
    [error, setError] = useState<ApiError | null>(null),
    [busy, setBusy] = useState(false);
  const scope = useRef(new AsyncScope());
  const tabs = [
    ["roles", "Roles & permissions", "ROLE_MANAGE"],
    ["members", "Members", "MEMBERSHIP_MANAGE"],
    ["policies", "Workflow policies", "WORKFLOW_VIEW"],
  ].filter((x) => permissions.includes(x[2]));
  const actual = tabs.some((x) => x[0] === tab) ? tab : tabs[0]?.[0];
  async function load(offset = page) {
    if (!org || !actual) return;
    const valid = scope.current.start("directory");
    setBusy(true);
    setError(null);
    try {
      const result = await (actual === "roles"
        ? api.roles(org, offset)
        : actual === "members"
          ? api.members(org, offset)
          : api.policies(org, offset));
      if (valid()) {
        setData(result);
        setPage(offset);
      }
    } catch (e) {
      if (valid()) setError(e as ApiError);
    } finally {
      if (valid()) setBusy(false);
    }
  }
  useEffect(() => {
    void load(0);
    return () => scope.current.invalidate();
  }, [org, actual]);
  if (!org || !actual) return null;
  return (
    <section className="panel card">
      <h2>Workspace administration</h2>
      <nav aria-label="Administration">
        {tabs.map(([key, label]) => (
          <button
            key={key}
            aria-pressed={actual === key}
            onClick={() => {
              scope.current.invalidate();
              setData(null);
              setPage(0);
              setTab(key);
            }}
          >
            {label}
          </button>
        ))}
      </nav>
      {error && (
        <p role="alert">
          {error.detail} {error.requestId}
        </p>
      )}
      {busy && <p role="status">Loading…</p>}
      <>
        <p>
          {data?.items?.length || 0} items on this page (not an organization
          total).
        </p>
        {actual === "roles" && (
          <RoleEditor
            key={`roles:${org}`}
            org={org}
            roles={data?.items || []}
            grants={permissions}
            reload={() => load()}
          />
        )}
        {actual === "members" && (
          <MemberEditor
            key={`members:${org}`}
            org={org}
            members={data?.items || []}
            grants={permissions}
            reload={() => load()}
          />
        )}
        {actual === "policies" && (
          <PolicyEditor
            key={`policies:${org}`}
            org={org}
            definitions={data?.items || []}
            grants={permissions}
            reload={() => load()}
          />
        )}
      </>
      <div className="pagination">
        <button
          disabled={busy || page === 0}
          onClick={() => void load(Math.max(0, page - 20))}
        >
          Previous directory page
        </button>
        <span>Page {page / 20 + 1}</span>
        <button
          disabled={busy || !data?.hasMore || page >= 10000}
          onClick={() => void load(page + 20)}
        >
          Next directory page
        </button>
      </div>
    </section>
  );
}
function useCommand(reload: () => Promise<void>) {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState<ApiError | null>(null);
  const active = useRef(true);
  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);
  async function run(fn: () => Promise<unknown>) {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await fn();
      if (active.current) await reload();
    } catch (e) {
      if (active.current) setError(e as ApiError);
    } finally {
      if (active.current) setBusy(false);
    }
  }
  return {
    busy,
    run,
    feedback: error ? (
      <p role="alert">
        {error.detail} {error.requestId}
      </p>
    ) : null,
  };
}
function RoleEditor({
  org,
  roles,
  grants,
  reload,
}: {
  org: string;
  roles: any[];
  grants: string[];
  reload: () => Promise<void>;
}) {
  const [selected, setSelected] = useState<any>(null),
    [permissions, setPermissions] = useState<string[]>([]),
    [catalog, setCatalog] = useState<string[]>([]);
  const { busy, run, feedback } = useCommand(reload);
  useEffect(() => {
    let active = true;
    api
      .catalog(org)
      .then((v) => {
        if (active)
          setCatalog(
            Array.isArray(v)
              ? v.map((x) => (typeof x === "string" ? x : x.code))
              : v.permissions || [],
          );
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, [org]);
  return (
    <>
      <ul>
        {roles.map((r) => (
          <li key={r.id}>
            <button
              onClick={() => {
                setSelected(r);
                setPermissions(r.permissions);
              }}
            >
              {r.name} ({r.system ? "protected" : "custom"})
            </button>
            <code>{r.id}</code>
          </li>
        ))}
      </ul>
      <button
        onClick={() => {
          setSelected(null);
          setPermissions([]);
        }}
      >
        New custom role
      </button>
      {feedback}
      <form
        onSubmit={(e) => {
          e.preventDefault();
          const f = new FormData(e.currentTarget);
          void run(async () => {
            if (selected) {
              const r = await api.grants(org, selected.id, {
                expectedVersion: selected.version,
                permissions,
              });
              setSelected(r);
            } else {
              const r = await api.createRole(org, {
                code: String(f.get("code")),
                name: String(f.get("name")),
                permissions,
              });
              setSelected(r);
            }
          });
        }}
      >
        {selected ? (
          <h3>
            {selected.name} · version {selected.version}
          </h3>
        ) : (
          <>
            <label>
              Role code
              <input
                name="code"
                required
                maxLength={60}
                pattern="[A-Z][A-Z0-9_]*"
              />
            </label>
            <label>
              Role name
              <input name="name" required maxLength={120} />
            </label>
          </>
        )}
        <fieldset disabled={busy || !!selected?.system}>
          <legend>Permissions (server enforces delegation ceiling)</legend>
          {catalog.map((code) => (
            <label key={code}>
              <input
                type="checkbox"
                checked={permissions.includes(code)}
                disabled={!grants.includes(code)}
                onChange={(e) =>
                  setPermissions(
                    e.target.checked
                      ? [...permissions, code]
                      : permissions.filter((x) => x !== code),
                  )
                }
              />
              {code}
            </label>
          ))}
        </fieldset>
        <button disabled={busy || !!selected?.system}>
          {selected ? "Replace permissions" : "Create role"}
        </button>
      </form>
    </>
  );
}
function MemberEditor({
  org,
  members,
  grants,
  reload,
}: {
  org: string;
  members: any[];
  grants: string[];
  reload: () => Promise<void>;
}) {
  const [selected, setSelected] = useState<any>(null),
    [roleIds, setRoleIds] = useState("");
  const { busy, run, feedback } = useCommand(reload);
  const roles = () =>
    roleIds
      .split(",")
      .map((x) => x.trim())
      .filter(Boolean);
  return (
    <>
      <ul>
        {members.map((m) => (
          <li key={m.id}>
            <button
              onClick={() => {
                setSelected(m);
                setRoleIds(m.roleIds.join(", "));
              }}
            >
              {m.displayName || m.email} · {m.status}
            </button>
            <code>Membership {m.id}</code>
          </li>
        ))}
      </ul>
      {feedback}
      {grants.includes("ROLE_MANAGE") && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            const f = new FormData(e.currentTarget);
            void run(async () => {
              const m = selected
                ? await api.memberRoles(org, selected.id, {
                    expectedVersion: selected.version,
                    roleIds: roles(),
                  })
                : await organizations.enroll(
                    org,
                    String(f.get("userId")),
                    roles(),
                  );
              setSelected(m);
            });
          }}
        >
          <button
            type="button"
            onClick={() => {
              setSelected(null);
              setRoleIds("");
            }}
          >
            Enroll existing account
          </button>
          {selected ? (
            <h3>
              {selected.displayName || selected.email} · version{" "}
              {selected.version}
            </h3>
          ) : (
            <label>
              Existing account UUID
              <input name="userId" required pattern="[0-9a-fA-F-]{36}" />
            </label>
          )}
          <label>
            Role UUIDs, comma-separated
            <input
              value={roleIds}
              onChange={(e) => setRoleIds(e.target.value)}
              required={!selected}
            />
          </label>
          <p>
            Use role IDs from the role directory. No global account/email search
            or invitation flow is exposed.
          </p>
          <button disabled={busy}>
            {selected ? "Replace member roles" : "Enroll member"}
          </button>
        </form>
      )}
      {selected && (
        <button
          disabled={busy}
          onClick={() =>
            void run(async () => {
              const m = await api.memberStatus(org, selected.id, {
                expectedVersion: selected.version,
                status: selected.status === "ACTIVE" ? "SUSPENDED" : "ACTIVE",
              });
              setSelected(m);
            })
          }
        >
          {selected.status === "ACTIVE"
            ? "Suspend membership"
            : "Activate membership"}
        </button>
      )}
    </>
  );
}
type Step = {
  name: string;
  approverRoleId: string;
  condition: { type: string; amount?: number; currency?: string };
};
const emptyStep = (): Step => ({
  name: "Approval review",
  approverRoleId: "",
  condition: { type: "ALWAYS" },
});
function PolicyEditor({
  org,
  definitions,
  grants,
  reload,
}: {
  org: string;
  definitions: any[];
  grants: string[];
  reload: () => Promise<void>;
}) {
  const [definition, setDefinition] = useState(""),
    [versionId, setVersionId] = useState(""),
    [version, setVersion] = useState<any>(null),
    [steps, setSteps] = useState<Step[]>([emptyStep()]);
  const { busy, run, feedback } = useCommand(reload);
  const dirty =
    version &&
    JSON.stringify(steps) !==
      JSON.stringify(
        version.steps.map((s: any) => ({
          name: s.name,
          approverRoleId: s.approverRoleId,
          condition: s.condition,
        })),
      );
  function accept(v: any) {
    setVersion(v);
    setVersionId(v.id);
    setSteps(
      v.steps.map((s: any) => ({
        name: s.name,
        approverRoleId: s.approverRoleId,
        condition: s.condition,
      })),
    );
  }
  return (
    <>
      {feedback}
      {grants.includes("WORKFLOW_CREATE") && (
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
              setDefinition(d.id);
              setVersion(null);
              setVersionId("");
            });
          }}
        >
          <label>
            New workflow name
            <input name="name" required maxLength={160} />
          </label>
          <label>
            Description
            <input name="description" />
          </label>
          <button disabled={busy}>Create workflow definition</button>
        </form>
      )}
      <label>
        Workflow definition
        <select
          value={definition}
          onChange={(e) => {
            setDefinition(e.target.value);
            setVersion(null);
            setVersionId("");
            setSteps([emptyStep()]);
          }}
        >
          <option value="">Select workflow</option>
          {definitions.map((d) => (
            <option key={d.id} value={d.id}>
              {d.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        Version UUID to inspect
        <input
          value={versionId}
          onChange={(e) => setVersionId(e.target.value)}
        />
      </label>
      <button
        disabled={!definition || !versionId || busy}
        onClick={() =>
          void run(async () =>
            accept(await api.policyVersion(org, definition, versionId)),
          )
        }
      >
        Load policy version
      </button>
      <p>
        Existing versions are read by UUID; the backend does not yet provide a
        version-directory endpoint. Published policies are immutable.
      </p>
      {version && (
        <p>
          Policy version {version.versionNumber} · {version.status} ·
          concurrency token {version.version} · ID {version.id}
        </p>
      )}
      <fieldset
        disabled={
          busy ||
          version?.status === "PUBLISHED" ||
          !grants.includes("WORKFLOW_UPDATE")
        }
      >
        <legend>Ordered sequential steps</legend>
        {steps.map((s, i) => (
          <section key={i} className="stack">
            <label>
              Step {i + 1} name
              <input
                required
                maxLength={120}
                value={s.name}
                onChange={(e) =>
                  setSteps(
                    steps.map((x, j) =>
                      j === i ? { ...x, name: e.target.value } : x,
                    ),
                  )
                }
              />
            </label>
            <label>
              Approver role UUID
              <input
                required
                value={s.approverRoleId}
                onChange={(e) =>
                  setSteps(
                    steps.map((x, j) =>
                      j === i ? { ...x, approverRoleId: e.target.value } : x,
                    ),
                  )
                }
              />
            </label>
            <label>
              Condition
              <select
                value={s.condition.type}
                onChange={(e) =>
                  setSteps(
                    steps.map((x, j) =>
                      j === i
                        ? {
                            ...x,
                            condition:
                              e.target.value === "ALWAYS"
                                ? { type: "ALWAYS" }
                                : {
                                    type: e.target.value,
                                    amount: 1000,
                                    currency: "USD",
                                  },
                          }
                        : x,
                    ),
                  )
                }
              >
                <option>ALWAYS</option>
                <option>PURCHASE_AMOUNT_AT_LEAST</option>
              </select>
            </label>
            {s.condition.type !== "ALWAYS" && (
              <>
                <label>
                  Amount
                  <input
                    type="number"
                    min="0.01"
                    step="0.01"
                    value={s.condition.amount}
                    onChange={(e) =>
                      setSteps(
                        steps.map((x, j) =>
                          j === i
                            ? {
                                ...x,
                                condition: {
                                  ...x.condition,
                                  amount: Number(e.target.value),
                                },
                              }
                            : x,
                        ),
                      )
                    }
                  />
                </label>
                <label>
                  Currency
                  <input
                    maxLength={3}
                    value={s.condition.currency}
                    onChange={(e) =>
                      setSteps(
                        steps.map((x, j) =>
                          j === i
                            ? {
                                ...x,
                                condition: {
                                  ...x.condition,
                                  currency: e.target.value.toUpperCase(),
                                },
                              }
                            : x,
                        ),
                      )
                    }
                  />
                </label>
              </>
            )}
            <div className="actions">
              <button
                disabled={i === 0}
                onClick={() => {
                  const copy = [...steps];
                  [copy[i - 1], copy[i]] = [copy[i], copy[i - 1]];
                  setSteps(copy);
                }}
              >
                Move up
              </button>
              <button
                disabled={steps.length === 1}
                onClick={() => setSteps(steps.filter((_, j) => i !== j))}
              >
                Remove step
              </button>
            </div>
          </section>
        ))}
        <button
          disabled={steps.length >= 50}
          onClick={() => setSteps([...steps, emptyStep()])}
        >
          Add step
        </button>
      </fieldset>
      {grants.includes("WORKFLOW_UPDATE") && (
        <div className="actions">
          <button
            disabled={!definition || busy}
            onClick={() => {
              setVersion(null);
              setVersionId("");
              setSteps([emptyStep()]);
            }}
          >
            Start new draft
          </button>
          <button
            disabled={
              !definition ||
              busy ||
              version?.status === "PUBLISHED" ||
              steps.some((s) => !s.name.trim() || !s.approverRoleId.trim())
            }
            onClick={() =>
              void run(async () =>
                accept(
                  version
                    ? await api.updateDraft(org, definition, version.id, {
                        expectedVersion: version.version,
                        steps,
                      })
                    : await api.draft(org, definition, { steps }),
                ),
              )
            }
          >
            Save draft steps
          </button>
        </div>
      )}
      {grants.includes("WORKFLOW_PUBLISH") &&
        version &&
        version.status !== "PUBLISHED" && (
          <button
            disabled={busy || dirty}
            onClick={() =>
              void run(async () => {
                const v = await workflows.publish(
                  org,
                  definition,
                  version.id,
                  version.version,
                );
                accept(v);
                localStorage.setItem(`gateflow.policy.${org}`, v.id);
              })
            }
          >
            {dirty
              ? "Save draft before publishing"
              : "Publish immutable version"}
          </button>
        )}
    </>
  );
}
export function AuditLog({ org }: { org: string }) {
  const [data, setData] = useState<any>(null),
    [entry, setEntry] = useState<any>(null),
    [page, setPage] = useState(0),
    [filters, setFilters] = useState<Record<string, string>>({}),
    [error, setError] = useState<ApiError | null>(null),
    [busy, setBusy] = useState(false);
  const scope = useRef(new AsyncScope());
  async function load(offset = 0) {
    scope.current.start("detail");
    const valid = scope.current.start("list");
    setBusy(true);
    setError(null);
    setData(null);
    setEntry(null);
    try {
      const d = await api.audit(org, offset, filters);
      if (valid()) {
        setData(d);
        setPage(offset);
      }
    } catch (e) {
      if (valid()) setError(e as ApiError);
    } finally {
      if (valid()) setBusy(false);
    }
  }
  useEffect(() => {
    void load();
    return () => scope.current.invalidate();
  }, [org]);
  async function detail(id: string) {
    const valid = scope.current.start("detail");
    try {
      const d = await api.auditEntry(org, id);
      if (valid()) setEntry(d);
    } catch (e) {
      if (valid()) setError(e as ApiError);
    }
  }
  return (
    <section className="panel card">
      <h2>Audit evidence</h2>
      <p>
        Read-only, tenant-scoped, allowlisted evidence. No edit/delete operation
        is exposed.
      </p>
      {error && (
        <p role="alert">
          {error.detail} {error.requestId}
        </p>
      )}
      <form
        className="filters"
        onSubmit={(e) => {
          e.preventDefault();
          void load();
        }}
      >
        {[
          "action",
          "resourceType",
          "resourceId",
          "actorMembershipId",
          "requestId",
        ].map((key) => (
          <label key={key}>
            {key}
            <input
              value={filters[key] || ""}
              onChange={(e) =>
                setFilters({ ...filters, [key]: e.target.value })
              }
            />
          </label>
        ))}
        <button disabled={busy}>Apply audit filters</button>
      </form>
      {busy ? (
        <p role="status">Loading…</p>
      ) : (
        <ul>
          {data?.items?.map((e: any) => (
            <li key={e.id}>
              <button onClick={() => void detail(e.id)}>
                {e.action} · {e.resourceType} ·{" "}
                {new Date(e.occurredAt).toLocaleString()}
              </button>
            </li>
          ))}
        </ul>
      )}
      {entry && (
        <section>
          <h3>Selected evidence</h3>
          <pre>{JSON.stringify(entry, null, 2)}</pre>
        </section>
      )}
      <div className="pagination">
        <button
          disabled={busy || page === 0}
          onClick={() => void load(page - 20)}
        >
          Previous audit page
        </button>
        <span>Page {page / 20 + 1}</span>
        <button
          disabled={busy || !data?.hasMore || page >= 10000}
          onClick={() => void load(page + 20)}
        >
          Next audit page
        </button>
      </div>
    </section>
  );
}
