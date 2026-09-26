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

  It must end up owned by the user the orchestrator container runs as: the orchestrator itself creates the per-session directory (`workspaces/<sessionId>`) before starting the helper container, so a root-owned root fails with `AccessDeniedException` on the very first tool call. The container's uid:gid only exists once the container runs, so the `chown` is the first step after the start (see "Start it"); the app boots fine without it, only tool calls fail.
- Write access to `/var/run/docker.sock`: the orchestrator drives its per-session helper containers through it (D-30). On a typical VM the socket is `root:docker` mode `660`, and the container user belongs to no host group on its own. Compose adds the host `docker` group to the container (`group_add`), reading the group's gid from the environment at `up`-time — export it in the same shell you run `docker compose` from:

  ```bash
  export HARNESS_DOCKER_GID="$(stat -c '%g' /var/run/docker.sock)"
  ```

  There is no default on purpose: compose refuses to start without the variable, because a guessed gid would surface much later as a cryptic failure on the first tool call.

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
| `KEYCLOAK_ISSUER_URI` | issuer of your realm | `https://keycloak.example.com/realms/myrealm` |
| `KEYCLOAK_JWKS_URI` | JWKS endpoint of the same realm | `https://keycloak.example.com/realms/myrealm/protocol/openid-connect/certs` |
| `HARNESS_WEBHOOK_SECRET` | HMAC-SHA256 secret for webhook capability URLs (api-contracts §4.4) | a long random string |
| `HARNESS_WEBHOOK_BASE_URL` | publicly reachable base for webhook callbacks; use the VM's hostname | `http://spring-harness.internal:8080` |
| `HARNESS_LLM_KEY_V1` | LLM provider key (encrypted at rest, see D-43) | the provider secret |
| `HARNESS_WORKSPACE_ROOT` | absolute host path for workspaces | change only if you moved the directory |

One naming note: the official postgres image bootstraps its superuser from its own hardcoded `POSTGRES_USER`/`POSTGRES_DB` variables, those two lines in the postgres service cannot be renamed. Everything the backend reads is uniformly `POSTGRES_*`.

Do not commit compose edits that contain real secrets. The file itself belongs in the repo, the `CHANGE_ME` placeholders belong in it too. If you prefer git to stop tracking your local edits entirely:

```bash
git update-index --skip-worktree docker-compose.yml
```

The orchestrator image is not built on the VM: `docker-compose.yml` pins a published version of `ghcr.io/rcktsci/spring-harness-orchestrator` and `docker compose up -d` pulls it. The package is public, so no login is needed. Upgrading or rolling back is the same move: change the tag in the `image:` line, then `up -d` again (section 4).

Start it:

```bash
docker compose up -d
docker compose ps            # orchestrator waits for postgres to go healthy first
docker compose logs -f orchestrator
```

Then hand the workspace root to the orchestrator — its uid:gid is readable only now that the container runs (see "What you need" for why this matters):

```bash
OWNER="$(docker compose exec -T orchestrator sh -c 'echo "$(id -u):$(id -g)"')"
sudo chown -R "$OWNER" /srv/harness/workspaces   # use $HARNESS_WORKSPACE_ROOT if you changed it
```

No restart needed; the next tool call creates its session directory with the new owner.

The container user stays whatever the image ships (`USER harness`, non-root): nothing in compose pins a numeric uid, because both host grants are read from the host at deploy time — the socket access via `HARNESS_DOCKER_GID`, the workspace ownership via the `chown` above.

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

## 4. Backend image: CI, releases, and rollback

The orchestrator image is built by a GitHub Actions pipeline and published to GitHub Container Registry; `docker-compose.yml` pins one published version and `docker compose up -d` runs it. This section covers the delivery path, the release procedure, and how to read the running version on the VM.

### 4.1 Deploying the published image

The workflow at `.github/workflows/backend-image.yml` builds the image from `docker/Dockerfile.orchestrator` and pushes it to GHCR on every push to `main` and on every tag `v*`. The package is **public**. The first push creates it private; flip the package to **Public** in the GHCR package settings once. After that, pulls work on the VM with no login and no PAT.

Image name: `ghcr.io/rcktsci/spring-harness-orchestrator`.

`docker-compose.yml` does not build anything — it pins one published version:

```yaml
image: ghcr.io/rcktsci/spring-harness-orchestrator:v0.1.0
```

Deploy, upgrade, and rollback are the same move: set the tag you want in the `image:` line of the `orchestrator` service, then:

```bash
docker compose pull    # fails fast with a clear error if the tag is missing or mistyped
docker compose up -d
```

The explicit `pull` surfaces a wrong tag immediately, not at container start; no `docker login` needed. One deployment note: the VM runs a hand-maintained copy of the compose file (the repository is not cloned there), so a version bump means editing the `image:` line in the live copy on the VM — in the repository the same line is changed by the release commit (see 4.2).

Tag scheme (set by `docker/metadata-action` in the workflow, `flavor: latest=false`):

| Tag | Meaning | When it updates |
|---|---|---|
| `latest` | Head of `main` | Every push to `main` |
| `main` | Branch name | Every push to `main` |
| `sha-<short>` | Immutable commit hash | Every push |
| `vX.Y.Z` / `X.Y.Z` | Semver from the git tag | Only on `v*` tags |

`latest` is not updated by tag pushes — the `latest=false` flavor overrides the default. `latest` follows `main` only, so a tagged release does not silently move it. Pin the compose file on the immutable tags (`vX.Y.Z`, `X.Y.Z`, or `sha-<short>`); `latest` moves under your feet with every push to `main`.

Compose never builds the orchestrator image. A local build for debugging is a separate, deliberate command:

```bash
docker build -f docker/Dockerfile.orchestrator -t ghcr.io/rcktsci/spring-harness-orchestrator:local .
```

Don't point the compose `image:` line at `:local` unless you are debugging on purpose — the file should name exactly what runs.

### 4.2 Cutting a release

One SemVer number covers both the backend (`pom.xml`) and the desktop client (`web-desktop/package.json`). The git tag `vX.Y.Z` is the source of truth for which version is released; the manifests match it.

**Bump rules:**

- **MAJOR** — breaking change to `api/openapi.yaml`, the WebSocket relay protocol (`docs/design/api-contracts.md` §5), or the SSE event stream (§3.1, §3.2).
- **MINOR** — new backward-compatible API or feature.
- **PATCH** — bug fixes that don't change contracts.

While the version is `0.x`, breaking changes are allowed in **MINOR**, but they must carry a `**BREAKING**` marker in the change/PR and a migration note in `CHANGELOG.md`. There is no automated check for breaking changes in CI — the marker and the changelog entry are reviewer-enforced; both reviewers must approve the release commit. Don't skip them.

**Tag rules:**

- Tags go only on commits already in `main`. No tags from feature branches.
- Release tags are **immutable**. The workflow rejects a re-push of `vX.Y.Z` if that tag already exists in GHCR. A fix ships as the next release — `v0.1.1` (PATCH) for a bugfix, `v0.2.0` (MINOR) if a contract changes; overwriting `vX.Y.Z` is forbidden.

**Release procedure:**

1. Set the version in `pom.xml` (the project's `<version>`, not the `<parent>` version) and in `web-desktop/package.json` to `X.Y.Z`. No `-SNAPSHOT` suffix — release commits carry the clean version.
2. Add a `## [X.Y.Z] - YYYY-MM-DD` entry to `CHANGELOG.md` under the right sections (Added / Changed / Fixed / Removed). Use the current date.
3. Pin the new version in `docker-compose.yml`: set the `image:` line of the `orchestrator` service to `ghcr.io/rcktsci/spring-harness-orchestrator:vX.Y.Z`. The release commit ships the compose pin; a release without it is not considered ready.
4. Commit on `main` and push.
5. Tag and push the tag:

   ```bash
   git tag vX.Y.Z
   git push origin vX.Y.Z
   ```

The tag push triggers the workflow. Before it builds and pushes the image, the workflow runs two gates:

- **Version gate** — re-reads `pom.xml` and `web-desktop/package.json`, checks both equal the tag. Mismatch fails with `::error::Version mismatch: …`.
- **Immutability gate** — checks GHCR for the tag. If `vX.Y.Z` already exists, the build fails with the immutability error.

If both gates pass, the image is published with exactly three tags: `vX.Y.Z`, `X.Y.Z`, and `sha-<short>`. A tag run never moves `latest` or `main` — those keep pointing at the last push to `main`.

### 4.3 Checking the version on the VM

Two OCI labels on the running image tell you exactly what's deployed (the container name `harness-orchestrator` comes from `container_name` in the `orchestrator` section of `docker-compose.yml`):

```bash
docker inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' harness-orchestrator
docker inspect --format '{{ index .Config.Labels "org.opencontainers.image.version" }}' harness-orchestrator
```

- `org.opencontainers.image.revision` — the full commit SHA the image was built from. Set on every build. Identical between the `:main`, `:latest`, and `:sha-…` tags that came from the same commit; for a short form in logs and shell history, read the `sha-<short>` tag name itself.
- `org.opencontainers.image.version` — semver on release builds (for example `0.1.0`); on branch builds it is the branch name (`main`) — by design.

Rollback is the same move as an upgrade: set the previous tag in the `image:` line of `docker-compose.yml` — `vX.Y.Z` (or `X.Y.Z`), or `sha-<previous-short>` — and run `docker compose up -d`. Compose pulls the old tag when the line changes.

Release and `sha-<short>` tags are immutable, so a rollback always re-deploys the exact bytes that were live before. Pick whichever tag you noted at the time; the label check above tells you what actually runs after the restart.

## Going deeper

- Architecture, operations, security: [`docs/design/architecture.md`](docs/design/architecture.md), [`docs/design/operations.md`](docs/design/operations.md), [`docs/design/security-multitenancy.md`](docs/design/security-multitenancy.md)
- Public contracts: [`docs/design/api-contracts.md`](docs/design/api-contracts.md), tools catalog in [`docs/design/agent-tools.md`](docs/design/agent-tools.md)
- Desktop client: [`docs/design/web-desktop-client.md`](docs/design/web-desktop-client.md)
- Every design decision so far: [`docs/design/decisions.md`](docs/design/decisions.md)
- Conventions for agents working in this repo: [`AGENTS.md`](AGENTS.md)
