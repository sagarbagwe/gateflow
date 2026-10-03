import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// Fixture contains opaque session values: chmod 600, local-only, never upload it.
const fixture = JSON.parse(open(__ENV.GATEFLOW_LOAD_FIXTURE || '/work/.load-fixture.json'));
if (__ENV.GATEFLOW_DISPOSABLE_STACK !== '1' || !/^http:\/\/(127\.0\.0\.1|localhost)(:\d+)?$/.test(fixture.base)) {
  throw new Error('Business workload requires an explicitly disposable loopback stack');
}
const failed = new Rate('business_failed');
const duration = new Trend('business_duration', true);
export const options = {
  vus: Number(__ENV.VUS || 5),
  duration: __ENV.DURATION || '60s',
  maxRedirects: 0,
  thresholds: {
    business_failed: ['rate<0.01'],
    business_duration: ['p(95)<750'],
    checks: ['rate>0.99'],
  },
};
function identity(role) {
  const jar = http.cookieJar();
  jar.clear(fixture.base);
  Object.entries(fixture[role + 'Cookies']).forEach(([name, value]) => jar.set(fixture.base, name, value));
}
function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.floor(Math.random() * 16);
    return (c === 'x' ? r : (r & 3) | 8).toString(16);
  });
}
function call(operation, path, method = 'GET', body, role = 'admin') {
  identity(role);
  const headers = {};
  if (method !== 'GET') {
    const csrf = http.get(fixture.base + '/api/v1/auth/csrf', { tags: { name: 'csrf', operation: 'csrf' } });
    if (!check(csrf, { 'csrf available': r => r.status === 200 })) { failed.add(true); return null; }
    const token = csrf.json();
    headers[token.headerName] = token.token;
    headers['Content-Type'] = 'application/json';
    headers['Idempotency-Key'] = uuid();
  }
  const r = http.request(method, fixture.base + fixture.prefix + path,
    body === undefined ? null : JSON.stringify(body), { headers, tags: { name: operation, operation } });
  const ok = check(r, { [operation + ' succeeds']: x => [200, 201].includes(x.status) });
  failed.add(!ok, { operation });
  duration.add(r.timings.duration, { operation });
  return ok ? r.json() : null;
}
export default function () {
  // Nine read-only iterations, then one isolated submit+approve lifecycle.
  // This is an iteration mix, not a claimed HTTP request-percentage mix.
  const slot = (__ITER + __VU) % 10;
  if (slot < 6) call('request-search', '/requests?limit=20&pagination=OFFSET&offset=0&status=APPROVED&q=laptop');
  else if (slot < 8) call('review-inbox', '/requests/inbox?limit=20&pagination=OFFSET&offset=0', 'GET', undefined, 'reviewer');
  else if (slot === 8) call('request-detail', '/requests/' + fixture.requestId);
  else {
    const r = call('submit', '/requests', 'POST', { ...fixture.body, title: 'Load laptop ' + uuid() });
    if (r) {
      const step = r.steps.find(s => s.state === 'ACTIVE');
      if (!step) { failed.add(true); return; }
      const result = call('approve', '/requests/' + r.id + '/steps/' + step.id + '/decisions',
        'POST', { expectedVersion: r.version, decision: 'APPROVE', comment: 'Disposable load verification' }, 'reviewer');
      if (result) {
        const approved = check(result, { 'request reaches APPROVED': x => x.state === 'APPROVED' });
        if (!approved) failed.add(true);
      }
    }
  }
  sleep(0.2);
}
