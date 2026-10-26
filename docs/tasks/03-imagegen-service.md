# Task 3: cv-imagegen-app around the queue core (Sonnet, effort medium)

Project: `D:\WorkDisk\JAVA\CV-gaile.ee\imagegen`. Read first: `docs/CONTRACT.md` (the API you implement),
memory `image-generation-plan.md` (context), the sibling `..\whisper` (same patterns: Spring Boot 4.1, Java 25,
Lombok, RestClient, YAML settings, Dockerfile, compose service on `kokoro-net`).

Already written by Opus, DO NOT change their behaviour (only add what is listed below; if something there looks wrong,
stop and write it in the report instead of changing it):
- `job/JobQueue` (single worker, limits, cancel, `unloadIfIdle`), `gpu/UnloadPolicy` (presence, idle/button/leave
  rules), `job/Job`, `JobStatus`, `JobKind`, `Progress`, `JobResult`, `ComfyGateway`, `JobRepository`,
  `ImagegenProperties`, `ClockConfig`, `application.yml`, tests `JobQueueTest`, `UnloadPolicyTest`.

## What to build

1. **Model catalogue** (`model` package): `imagegen.models` in `application.yml` lists the 7 catalogue modes of
   CONTRACT.md with kind, title, description, typicalSeconds and the client params (type, default, min, max, step,
   required). Defaults from `workflows/<id>.params.json`. Move `workflows/*.json` to `src/main/resources/workflows/` (one copy only;
   the root folder goes away). `GET /api/models`.
   Ranges: Muse steps 8-40, cfg 1.0-4.0; fluxedUp steps 20-50, guidance 2.0-6.0; image sizes 512-2048 step 16;
   edit/inpaint: prompt + seed + steps + cfg/guidance; Wan: prompt, negativePrompt, seed, seconds 2-6, orientation
   ENUM landscape/portrait (832x480 / 480x832); LTX: prompt, negativePrompt, seed, seconds 2-8, orientation ENUM
   (1216x704 / 704x1216).
2. **Param validation and derivation** (`JobFactory` or similar): unknown model / unknown param / out of range ->
   400 `invalid_params`; random seed when empty (0..2^31-1, returned); EDIT/INPAINT size from the photo (CONTRACT.md);
   video seconds -> frames; LTX half sizes; `filenamePrefix` = jobId. Unit tests for every derivation.
3. **Input files**: decode jpeg/png/webp with ImageIO (add `com.twelvemonkeys.imageio:imageio-webp` for webp), apply
   EXIF orientation for jpeg (TwelveMonkeys `imageio-jpeg` + metadata, or `com.drewnoakes:metadata-extractor`), write
   `<jobId>.input.png`; mask -> resized to the image size, `<jobId>.mask.png`. `sourceJobId`: the DONE image result
   of the same user is copied as the input.
4. **`FileJobRepository implements JobRepository`**: `/data/jobs/<jobId>.json` (Jackson, write to `.tmp` then atomic
   move), loads all at start into an in-memory index (`Map<UUID, Job>`), QUEUED/RUNNING at start -> FAILED
   "service restarted". Lookup by id, list by user (newest first, limit), delete (record + all files of the job).
   `@Scheduled` hourly cleanup of jobs finished more than `imagegen.retention` ago. Jobs created at start time
   need `Job` to be rebuilt from json: add a constructor or factory to `Job` only if needed, keep fields as they are.
5. **`ComfyUiGateway implements ComfyGateway`** (`comfy` package), against cv-comfyui :8188 (ComfyUI v0.42.1):
   - template = `workflows/<model>.json` (API format); set every `params.json` target `node.input` from the job
     params (`targets` lists all inputs a param goes to); input images: `POST /upload/image` (multipart `image`,
     `overwrite=true`, name `<jobId>.input.png` / `<jobId>.mask.png`), then the file name into the image targets
   - `POST /prompt {"prompt": workflow, "client_id": <uuid per app>}` -> `prompt_id`
   - progress over the websocket `ws://.../ws?clientId=<same uuid>` (Spring `StandardWebSocketClient` or the JDK
     `java.net.http.WebSocket`): `progress` {value, max, prompt_id} -> SAMPLING; `executing` with node = the output
     node after the last progress -> DECODING; `executing` node null / `execution_success` -> done;
     `execution_error` / `execution_interrupted` -> JobFailedException with the exception message. One websocket
     for the app, reconnect when closed; if the websocket is down, fall back to polling `GET /history/{prompt_id}`
     every 2 s.
   - output: `GET /history/{prompt_id}` -> outputs of the output node (`params.json` "output"): images[] for
     SaveImage, for video the node gives `images`/`gifs`/`videos` depending on the node: check what
     `wan22-i2v-fastmove` and `ltx25-i2v` actually return (run them once against the live cv-comfyui, see below)
     -> `GET /view?filename=&subfolder=&type=output` -> `/data/jobs/<jobId>.png|.mp4`; JobResult with size, width,
     height (images: ImageIO; video: from params) and durationSeconds.
   - deadline: past it -> `POST /interrupt`, JobFailedException("timeout").
   - `interrupt()` -> `POST /interrupt`; `free()` -> `POST /free {"unload_models": true, "free_memory": true}`;
     `isUp()` -> `GET /system_stats` with a 3 s timeout.
   - Unit tests with a mock HTTP server (MockRestServiceServer or OkHttp MockWebServer) for the substitution, the
     upload, history parsing; no test needs a GPU.
6. **REST controllers** exactly per CONTRACT.md (paths, codes, error body, `X-User-Id` required -> 400 if missing,
   other users' jobs -> 404). `POST /api/jobs` calls `UnloadPolicy.beforeSubmit(userId)` before `JobQueue.submit`.
   `GET /api/state` = `UnloadPolicy.state()` + `comfyuiUp`. Result download: `ResponseEntity<Resource>` (Range works
   for it in Spring MVC), `Content-Disposition` per the contract. `@WebMvcTest` tests for every endpoint
   (the core beans mocked).
7. **Docker**: root `Dockerfile` like whisper's (port 8390), compose service `spring-app` -> container
   `cv-imagegen-app`, `127.0.0.1:8390:8390`, volume `results:/data` with `name: imagegen-results`, `TZ`,
   `COMFYUI_URL` default ok, `kokoro-net`; the data dir owned by the app user (create it in the Dockerfile like
   whisper does for its cache). `application-dev.yml`: `comfyui-url: http://localhost:8188`, `data-dir: ./data`
   (add `data/` to .gitignore).
8. **One live check** (cv-comfyui is running on 127.0.0.1:8188): run the app with the dev profile and generate one
   `muse-txt2img` 512x512 8 steps through `POST /api/jobs` with curl, poll until DONE, download the result; then
   `POST /api/unload`. Do NOT run video jobs more than once each (minutes of GPU; the prod chat shares the GPU):
   only the one run per video model needed to see the output node format, 2 s length.

## Rules

- Comments in English, in the style of the existing classes (why, not what). Lombok `@UtilityClass` for utility
  classes. Line endings CRLF (`unix2dos` new files).
- `MAVEN_SKIP_RC=1 ./mvnw verify` must pass. No commits: Opus reviews the diff first.
- Do not touch `..\CV`. Do not change `comfyui-service/` (except reporting problems).
- Report at the end: what was built, deviations from the contract (with reasons), test count, the live check output
  (times), open questions.
