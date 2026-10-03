import { useEffect, useState } from "react";
import {
  notifications,
  type NotificationPreferences,
  type ApiError,
} from "./api";
export function Preferences({ org }: { org: string }) {
  const [value, setValue] = useState<NotificationPreferences | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    let active = true;
    notifications
      .preferences(org)
      .then((v) => active && setValue(v))
      .catch((e) => active && setError(e));
    return () => {
      active = false;
    };
  }, [org]);
  async function save() {
    if (!value || busy) return;
    setBusy(true);
    setError(null);
    try {
      setValue(
        await notifications.savePreferences(org, {
          inAppEnabled: value.inAppEnabled,
          emailEnabled: value.emailEnabled,
          expectedVersion: value.version,
        }),
      );
    } catch (e) {
      setError(e as ApiError);
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="filters" aria-label="Notification preferences">
      <h3>Preferences</h3>
      {error && (
        <p role="alert">
          {error.detail} {error.requestId && `Request ${error.requestId}`}
        </p>
      )}
      {value && (
        <>
          <label>
            <input
              type="checkbox"
              checked={value.inAppEnabled}
              disabled={busy}
              onChange={(e) =>
                setValue({ ...value, inAppEnabled: e.target.checked })
              }
            />
            In-app updates
          </label>
          <label>
            <input
              type="checkbox"
              checked={value.emailEnabled}
              disabled={busy}
              onChange={(e) =>
                setValue({ ...value, emailEnabled: e.target.checked })
              }
            />
            Email reminders (requires configured, verified provider)
          </label>
          <button onClick={() => void save()} disabled={busy}>
            Save preferences
          </button>
        </>
      )}
    </section>
  );
}
