import { useEffect, useRef, useState } from "react";
import { requests, type ApiError } from "./api";
import { AsyncScope } from "./async-scope";
export function Activity({
  org,
  requestId,
}: {
  org: string;
  requestId: string;
}) {
  const [data, setData] = useState<any>(null),
    [page, setPage] = useState(0),
    [error, setError] = useState<ApiError | null>(null),
    [busy, setBusy] = useState(false);
  const scope = useRef(new AsyncScope());
  async function load(offset: number) {
    const valid = scope.current.start("activity");
    setBusy(true);
    setError(null);
    try {
      const d = await requests.activity(org, requestId, offset);
      if (valid()) {
        setData(d.activity);
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
  }, [org, requestId]);
  return (
    <section>
      <h3>Activity timeline</h3>
      <p>Background events may lag the current request state.</p>
      {error && <p role="alert">{error.detail}</p>}
      {busy ? (
        <p role="status">Loading activity…</p>
      ) : (
        <ul>
          {data?.items?.map((x: any) => (
            <li key={x.eventId}>
              {x.type} · {x.state} · {new Date(x.occurredAt).toLocaleString()}
            </li>
          ))}
        </ul>
      )}
      <div className="pagination">
        <button
          disabled={busy || page === 0}
          onClick={() => void load(page - 20)}
        >
          Previous activity
        </button>
        <button
          disabled={busy || !data?.hasMore || page >= 10000}
          onClick={() => void load(page + 20)}
        >
          Next activity
        </button>
        <button disabled={busy} onClick={() => void load(page)}>
          Refresh activity
        </button>
      </div>
    </section>
  );
}
