# spring-harness

An agent platform: chat sessions with real LLM orchestration, a workflow engine with typed states, subagent trees, MCP integrations, and a cross-platform desktop client.

Backend: Java 25, Spring Boot 4.1.1, Spring AI 2.0.1, PostgreSQL 18. Desktop client (`web-desktop/`): Electron, Vue 3, Vite, TypeScript.

The design docs live in [`docs/design/`](docs/design/), the decision log in [`docs/design/decisions.md`](docs/design/decisions.md), and agent-working conventions in [`AGENTS.md`](AGENTS.md).

This README gets a fresh deployment from zero: backend on a Linux VM via docker compose, single sign-on via your Keycloak, then the desktop client on your workstation.

## 1. Backend (docker compose)

One command brings up the whole stack on a clean Linux VM. Tested with Docker 29.8.0 and compose v5.5.1; any recent docker with compose v2+ should do.

What you need on the VM:

- `docker` with the `compose` plugin (`docker compose version`).
- Network access to your Keycloak instance (see section 2).
- Network access to Docker Hub, for `postgres:18-alpine` and the base images used by `docker/Dockerfile.orchestrator`.
- A directory for session workspaces. Create it before the first start, otherwise the docker daemon will create it as root and the permissions will surprise you:

  ```bash
  mkdir -p /srv/harness/workspaces
  ```

  It must be writable by the user the orchestrator container runs as: the orchestrator itself creates the per-session directory (`workspaces/<sessionId>`) before starting the helper container, so a root-owned root fails with `AccessDeniedException` on the very first tool call. The app boots fine without this, only tool calls fail, so you can fix it after the first start:

  ```bash
  OWNER="$(docker compose exec -T orchestrator sh -c 'echo "$(id -u):$(id -g)"')"
  sudo chown -R "$OWNER" /srv/harness/workspaces   # use $HARNESS_WORKSPACE_ROOT if you changed it
  ```

  The container user is deliberately left to the image (`USER harness`) rather than pinned in compose: it needs access to `/var/run/docker.sock` to create helper containers, and a pinned uid that is not in the host's `docker` group would lose exactly that.

Prepare the repo:

```bash
git clone <repo>
cd spring-harness

# Helper image for per-session workspace containers. Compose does NOT build it;
# the backend expects it to exist locally (see harness.docker.helper-image in
# application.yml).
docker build -f docker/Dockerfile -t harness-helper:local .
```

Fill in the secrets in `docker-compose.yml` before the first start. There is no `.env` file by design; everything lives in the `environment:` block, and every secret ships as a `CHANGE_ME` placeholder.

| Variable | What it is | Example |
|---|---|---|
| `POSTGRES_PASSWORD` | the postgres password; the same name on both sides (backend env and database bootstrap) | a long random string |
| `POSTGRES_USERNAME` / `POSTGRES_DATABASE` | backend credentials; defaults `harness`/`harness`, change only if you renamed them | `harness` |

One naming note: the official postgres image bootstraps its superuser from its own hardcoded `POSTGRES_USER`/`POSTGRES_DB` variables, those two lines in the postgres service cannot be renamed. Everything the backend reads is uniformly `POSTGRES_*`.
| `KEYCLOAK_ISSUER_URI` | issuer of your realm | `https://keycloak.example.com/realms/myrealm` |
| `KEYCLOAK_JWKS_URI` | JWKS endpoint of the same realm | `https://keycloak.example.com/realms/myrealm/protocol/openid-connect/certs` |
| `HARNESS_WEBHOOK_SECRET` | HMAC-SHA256 secret for webhook capability URLs (api-contracts §4.4) | a long random string |
| `HARNESS_WEBHOOK_BASE_URL` | publicly reachable base for webhook callbacks; use the VM's hostname | `http://spring-harness.internal:8080` |
| `HARNESS_LLM_KEY_V1` | LLM provider key (encrypted at rest, see D-43) | the provider secret |
| `HARNESS_WORKSPACE_ROOT` | absolute host path for workspaces | change only if you moved the directory |

Do not commit compose edits that contain real secrets. The file itself belongs in the repo, the `CHANGE_ME` placeholders belong in it too. If you prefer git to stop tracking your local edits entirely:

```bash
git update-index --skip-worktree docker-compose.yml
```

No internet access to a Maven mirror from the VM? Build the jar on your dev machine instead:

```bash
chmod +x mvnw
./mvnw -DskipTests package
```

Then replace the `build:` block in compose with a ready image. The default path stays the in-container `mvn -DskipTests package`, which needs no local Java at all.

Start it:

```bash
docker compose up -d
docker compose ps            # orchestrator waits for postgres to go healthy first
docker compose logs -f orchestrator
```

Check it:

```bash
curl -sS http://<vm>:8081/actuator/health
# {"status":"UP"}

curl -sS http://<vm>:8080/api/v1/agents | head
```

Liquibase applies the schema on first start; the log shows `Starting Liquibase` and then `Update committed successfully`. More on logs and metrics in [`docs/design/operations.md`](docs/design/operations.md).

## 2. Keycloak

Keycloak runs outside this stack (yours to provide). The backend only needs the issuer and JWKS URIs from section 1; the desktop client needs a public client in the same realm. The setup below takes a couple of minutes and reproduces cleanly on any realm.

Create a public client for the desktop app:

| Field | Value |
|---|---|
| Client ID | `spring-harness-desktop` |
| Client authentication | Off (public) |
| Standard flow | enabled |
| Direct access grants | disabled, only Authorization Code is used |
| PKCE | required, method S256 |
| Valid redirect URIs | `http://127.0.0.1/*` (the app listens on a loopback port, D-42/D-91) |
| Web origins | `http://127.0.0.1` |
| Valid post logout redirect URIs | `http://127.0.0.1` |

One mapper decides everything: the backend gates access on a `groups` claim (api-contracts §1), and without it every request gets a 403.

1. In the Admin UI open the client's dedicated scope under **Client scopes**.
2. **Add mapper**, type **Group Membership**.
3. Token claim name: `groups`.
4. Full group path: **OFF**, otherwise the claim arrives as `myrealm/harness-users` instead of `harness-users`.
5. Add to ID token: ON. Add to access token: ON.

Then create a user and make them a member of the `harness-users` group (the default of `harness.security.allowed-groups` in `application.yml`). No membership, empty `groups` claim, 403.

A note of restraint: this realm is probably shared. Keep every change scoped to this client and its scope; do not touch realm defaults or default client scopes.

## 3. Desktop client (Web Desktop)

For a dev run you need Node.js 20.18.0+ and pnpm 10+ (`corepack enable && corepack prepare pnpm@latest --activate` gets you there).

```bash
cd web-desktop
pnpm install
pnpm dev   # electron-vite dev with HMR, opens the Electron window
```

In the window, open Settings and point it at your stack:

- `server.baseUrl`: `http://<vm>:8080`
- `keycloakIssuer`: `https://keycloak.example.com/realms/myrealm` (your realm)
- `keycloakClientId`: `spring-harness-desktop`

Hit Login. A visible browser window opens with your Keycloak login page; after the redirect the tokens land in the OS keychain via `safeStorage`. The sessions list appears, `+` creates a session, the composer at the bottom sends messages.

For a packaged build, package on the target OS (electron-builder does not cross-compile installers):

```bash
cd web-desktop
pnpm install
pnpm run build
pnpm run package:win    # Windows NSIS setup in dist/
pnpm run package:linux  # Linux AppImage in dist/
```

`publish: null` in `electron-builder.yml` means no auto-update. Internal MVP: distribute builds over your file share, users install by hand. Details in [`docs/design/operations.md`](docs/design/operations.md) §6 and [`docs/design/web-desktop-client.md`](docs/design/web-desktop-client.md).

### First end-to-end check

1. Login through SSO.
2. The session list appears.
3. Create a session (pick an agent from the catalog).
4. Type "hello" in the composer.
5. The assistant answers in the feed.

One expected failure mode: with a missing or invalid `HARNESS_LLM_KEY_V1` the server starts fine, but the first turn dies trying to decrypt the provider key, and the log says so plainly. That is by design: a loud configuration error beats a silently broken LLM.

### Smoke and e2e, two different runs

They look similar and check different things.

Stub-server e2e runs with no backend at all; an in-test Node server fakes the wire protocols (SSE §3.1/§3.2, WS relay §5):

```bash
cd web-desktop
pnpm install
pnpm run build         # one-off: the playwright-electron driver launches the built binary
pnpm run e2e:electron  # full happy path (chat, bash tool call, artifact); needs a display or xvfb
pnpm run e2e:stub      # headless protocol check against the stub, fast and CI-friendly
```

The stub and Playwright setup are documented in [`web-desktop/docs/smoke.md`](web-desktop/docs/smoke.md).

Smoke against the real stack needs section 1 running, then:

```bash
pwsh scripts/smoke-docker.ps1    # or bash scripts/smoke-docker.sh
```

The script reads `HARNESS_E2E_*` from the environment, drives Electron through the full scenario (login, session, bash tool, artifact download), and needs a display server (`xvfb-run` on headless Linux). The manual fallback lives in [`web-desktop/docs/smoke.md`](web-desktop/docs/smoke.md).

## Going deeper

- Architecture, operations, security: [`docs/design/architecture.md`](docs/design/architecture.md), [`docs/design/operations.md`](docs/design/operations.md), [`docs/design/security-multitenancy.md`](docs/design/security-multitenancy.md)
- Public contracts: [`docs/design/api-contracts.md`](docs/design/api-contracts.md), tools catalog in [`docs/design/agent-tools.md`](docs/design/agent-tools.md)
- Desktop client: [`docs/design/web-desktop-client.md`](docs/design/web-desktop-client.md)
- Every design decision so far: [`docs/design/decisions.md`](docs/design/decisions.md)
- Conventions for agents working in this repo: [`AGENTS.md`](AGENTS.md)
