# GateFlow frontend

Accessible React/TypeScript SPA for authentication, request search, reviewer inbox, and notifications.

```sh
npm install
npm run build
npm test
npm run dev
```

Vite proxies `/api` to `http://localhost:8080`. The production Nginx image proxies to the Compose `backend` service, preserving same-origin cookie and CSRF behavior.
