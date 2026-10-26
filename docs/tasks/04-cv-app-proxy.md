# Task 4: cv-app proxy for image generation (Sonnet, effort medium)

Project: `D:\WorkDisk\JAVA\CV-gaile.ee\CV` (multi-module Maven: common, persistence, security, services, web-api).
Read first: `..\imagegen\docs\CONTRACT.md` (section "cv-app side" and the endpoints), memories
`image-generation-plan.md`, `admin-user-roles.md`, `backend-test-setup.md`, `api-rename-2026-10.md`.
Start a branch `image-generation` from master.

## What to build

- `/api/image-generation/**` = the imagegen endpoints one to one: `models`, `jobs` (POST multipart, GET list,
  GET/DELETE by id, `/result`, `/input`), `state`, `unload`, `presence/heartbeat`, `presence/leave`. Check how
  other controllers are mapped (the `/api` prefix may come from nginx or a servlet path; follow
  `web-api/.../controller/user/AdminUserController.java` and `ChatController.java`).
- Access: only ROLE_ADMIN or ROLE_ELITE (`common/.../enums/Role.java`; see how `GeminiAccess` /
  `UserRoleService` read the roles, ROLE_ELITE has no expiry, ROLE_PREMIUM is NOT enough). Guests 401, other users 403.
  ROLE_ELITE gets every model, same as the admin.
- Headers to imagegen: `X-User-Id` (user id), `X-User-Name`; never pass anything from the browser through as these.
- RestClient bean `imagegenClient` in `web-api/.../config/RestClientConfig.java` next to the whisper one, setting
  `imagegen.url` (prod `http://cv-imagegen-app:8390`, dev `http://localhost:8390` in `application-dev.yml`),
  timeouts: connect 3 s, read 30 s (jobs are async, no request waits for a generation).
- Status codes and error bodies of imagegen pass through unchanged (400/404/429/503). imagegen down -> 503
  `{"error": "imagegen_unavailable"}`.
- Multipart POST: forward `request`, `image`, `mask` parts as they came (limits 20 MB per file; check
  `spring.servlet.multipart` of cv-app and nginx `client_max_body_size` in `etc/` for the path, raise only for this
  path if needed).
- `/result` and `/input`: stream the body through (no buffering of a 30 MB video in memory), pass `Range` request
  header and `Content-Range`, `Accept-Ranges`, `Content-Type`, `Content-Length`, `Content-Disposition` back;
  206 must work (look at how `TranscriptionFileService` streams the whisper answer through).
- `presence/leave` is also called by `navigator.sendBeacon` (POST, `text/plain` body, cookie auth): accept any
  content type, no CSRF problem (check the security config; if CSRF is on for POST, see how other POSTs pass it).
- docker-compose of CV: nothing to add (imagegen has its own compose); cv-app reaches `cv-imagegen-app` over
  `kokoro-net` (check cv-app is on that network).
- Observability: nothing new needed; just log job submit/cancel at info with user id and model.

## Tests

- Unit tests (`*Test`) for the access rule (admin, elite, premium, user, guest) and the header building.
- One `*IT` (Testcontainers pattern of the project) or a `@WebMvcTest` with `MockRestServiceServer` for: POST job
  pass-through, 403 for ROLE_USER, Range 206 pass-through, 503 when imagegen is down.
- `MAVEN_SKIP_RC=1 mvn verify` for the touched modules must pass.

## Rules

- Comments in English in the style of the surrounding code; `@UtilityClass` for utilities; CRLF line endings.
- No DB table, no migration (the contract keeps the records in imagegen).
- No commits on master; commit on the branch only after Opus reviewed the diff (leave it uncommitted).
- Report: endpoints, files changed, test count, deviations and open questions.
