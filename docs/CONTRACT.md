# imagegen: API contract

Image and video generation for gaile.ee, only for users with ROLE_ADMIN or ROLE_ELITE (both get every model).

```
browser (cv-ui page) --> cv-app /api/image-generation/** --> cv-imagegen-app :8390 --> cv-comfyui :8188
                         role check, user id header          queue, unload rules,       ComfyUI v0.42.1,
                                                              results + history (10 d)   models volume
```

The browser never calls cv-imagegen-app directly. cv-imagegen-app has no authentication of its own (like
cv-whisper-app): it is reachable only inside the Docker network `kokoro-net`, and cv-app passes the user in headers.

## Headers from cv-app

| Header        | Value                                  |
|---------------|----------------------------------------|
| `X-User-Id`   | id of the user in cv-app (long)        |
| `X-User-Name` | user name, for logs only (optional)    |

Every job belongs to the `X-User-Id` that created it. A job of another user answers 404, as if it did not exist.

## Models and modes

`GET /api/models` -> list of the generation modes, built from `workflows/<id>.json` + `<id>.params.json`
and the catalogue in `application.yml` (`imagegen.models`):

| id                   | kind    | input         | output     | typical time (RTX 5060 Ti, warm) |
|----------------------|---------|---------------|------------|----------------------------------|
| `muse-txt2img`       | IMAGE   | -             | image/png  | 1024x1024 16 steps ~50 s         |
| `fluxedup-txt2img`   | IMAGE   | -             | image/png  | 1024x1024 35 steps ~150-170 s    |
| `muse-edit`          | EDIT    | image         | image/png  | ~60 s                            |
| `muse-inpaint`       | INPAINT | image + mask  | image/png  | ~60 s                            |
| `fluxedup-inpaint`   | INPAINT | image + mask  | image/png  | ~140-160 s                       |
| `wan22-i2v-fastmove` | VIDEO   | image         | video/mp4  | 832x480 5 s ~160 s               |
| `ltx25-i2v`          | VIDEO   | image or -    | video/mp4  | 1216x704 5 s with sound ~115 s   |

`wan22-i2v` (base Wan 2.2) stays in `workflows/` but is not in the catalogue (Fast Move replaces it).

Response:

```json
[{
  "id": "muse-txt2img",
  "kind": "IMAGE",
  "title": "Muse (Krea 2)",
  "description": "...",
  "needsImage": false,
  "acceptsImage": false,
  "needsMask": false,
  "outputType": "image/png",
  "typicalSeconds": 50,
  "params": [
    {"name": "prompt", "type": "TEXT", "default": "", "required": true},
    {"name": "negativePrompt", "type": "TEXT", "default": ""},
    {"name": "steps", "type": "INT", "default": 16, "min": 8, "max": 40},
    {"name": "cfg", "type": "FLOAT", "default": 2.5, "min": 1.0, "max": 4.0, "step": 0.1},
    {"name": "width", "type": "INT", "default": 1024, "min": 512, "max": 2048, "step": 16},
    {"name": "height", "type": "INT", "default": 1024, "min": 512, "max": 2048, "step": 16},
    {"name": "seed", "type": "SEED"}
  ]
}]
```

Param types: `TEXT`, `INT`, `FLOAT`, `ENUM` (with `options`), `SEED` (empty = random). Only params listed in the
catalogue are accepted from the client; the rest of `<id>.params.json` (`filenamePrefix`, LTX `halfWidth`, mask blur
...) is set by the service. Derived values:
- EDIT / INPAINT: `width`/`height` are not client params; they come from the photo: scaled to at most ~1.05 MP
  (1168x880 for 4:3, 1024x1024 for a square), both sides rounded down to a multiple of 16.
- VIDEO size is an `orientation` ENUM (`landscape` / `portrait`): Wan 832x480 / 480x832, LTX 1216x704 / 704x1216
  (the service sets the LTX stage-1 `halfWidth`/`halfHeight`).
- VIDEO length is a client param in seconds (`seconds`: Wan 2-6, LTX 2-12), converted to frames: 16 * s + 1 for Wan (16 fps),
  24 * s + 1 for LTX (24 fps); both satisfy the frame rules of the models (4n+1, 8n+1). LTX 10 s ~195 s, 12 s ~255 s.
- `needsImage` = the job is refused without an image; `acceptsImage` = an image may be given. LTX has
  `needsImage: false, acceptsImage: true`: without an image the video is made from the text alone, the service adds
  `"textOnly": true` to the params of the job (shown in the job JSON, not accepted from the client) and the workflow
  skips its start image.

## Jobs

### `POST /api/jobs` (multipart)

| Part      | Required                  | Content                                                          |
|-----------|---------------------------|------------------------------------------------------------------|
| `request` | yes                       | JSON `{"model": "muse-inpaint", "params": {...}, "sourceJobId": null}` |
| `image`   | EDIT, INPAINT, Wan        | jpeg / png / webp, at most 20 MB                                 |
| `mask`    | INPAINT                   | png, white = area to redraw; resized to the image size           |

`sourceJobId` = a DONE image job of the same user used as the input image instead of the `image` part (animate or
edit a picture generated before, without downloading and uploading it). For LTX both are optional.

Answer `202`:

```json
{"jobId": "3f0c...", "status": "QUEUED", "position": 1, "seed": 123456789}
```

`position` = jobs ahead of this one including the running one (0 = it runs now). `status` may already be
RUNNING when the queue was empty.

Errors (body `{"error": "<code>", "message": "..."}`):
- `400 invalid_params` - unknown model, a param out of range, missing image/mask, unreadable image
- `429 too_many_jobs` - the user has 3 jobs QUEUED or RUNNING, or the whole queue has 10
- `503 comfyui_unavailable` - ComfyUI does not answer `/system_stats`

### `GET /api/jobs/{id}`

```json
{
  "jobId": "3f0c...",
  "model": "muse-inpaint",
  "kind": "INPAINT",
  "status": "RUNNING",
  "position": 0,
  "progress": {"phase": "SAMPLING", "value": 7, "max": 16},
  "params": {"prompt": "...", "steps": 16, "seed": 123456789},
  "translations": {"prompt": "..."},
  "seed": 123456789,
  "createdAt": "2026-10-26T12:00:00Z",
  "startedAt": "2026-10-26T12:00:03Z",
  "finishedAt": null,
  "expiresAt": null,
  "error": null,
  "result": null
}
```

- `status`: `QUEUED`, `RUNNING`, `DONE`, `FAILED`, `CANCELLED`
- `progress.phase`: `TRANSLATING` (the Russian texts are translated into English), `LOADING` (running, no sampler
  step reported yet: models load), `SAMPLING` (value/max of the
  current sampler; video and LTX have two samplers, the bar restarts), `DECODING` (after the last step)
- `result` when DONE: `{"contentType": "image/png", "size": 1834221, "width": 1024, "height": 1024}`
  (`durationSeconds` for video)
- `translations`: param name -> the English text the model got instead of the typed one. Every text param with
  Cyrillic in it (`prompt`, `negativePrompt`) is translated by a local uncensored model (Ollama on the CPU, see
  `imagegen.translator`) when the job starts; `{}` when nothing needed it. A failed translation fails the job with
  `"prompt translation failed"`. The translation model is unloaded together with the GPU model.
- `expiresAt` = `finishedAt` + 10 days

### `GET /api/jobs?limit=50`

History of the user, newest first: the same objects as `GET /api/jobs/{id}`. At most 10 days back.

### `GET /api/jobs/{id}/result`

The file (`image/png` or `video/mp4`), `Content-Disposition: attachment; filename="<model>-<yyyyMMdd-HHmmss>-<seed>.<ext>"` (time in UTC)
with `?download=true`, `inline` otherwise. Supports `Range` (video seeking). 404 unless DONE.

### `GET /api/jobs/{id}/input`

The input image the job used (png), for the history card of an edit / video. 404 for IMAGE jobs.

### `DELETE /api/jobs/{id}`

- QUEUED -> removed from the queue, status CANCELLED
- RUNNING -> ComfyUI `/interrupt`, status CANCELLED
- DONE / FAILED / CANCELLED -> files and record deleted
Answer `204`.

## GPU state and unload

The model takes ~13 GB of the 16 GB VRAM shared with Ollama, Whisper and the TTS services, so it is unloaded
(ComfyUI `POST /free {"unload_models": true, "free_memory": true}`) by these rules:

1. Idle: 30 min (`imagegen.idle-unload`) after the last job finished, with nothing queued.
2. Button: `POST /api/unload` -> unload now if nothing is queued or running; otherwise after the queue drains.
3. Leaving the page: `POST /api/presence/leave` -> if no other user has the page open, the same as the button.
   Another user's running or queued job is never cut off: the unload waits for the queue to drain.
4. A page that disappears without `leave` (tab closed, browser killed, sleep): presence expires 150 s after the last
   heartbeat and counts as a leave.

A job submitted after an unload loads the model again (+5-30 s); a pending unload is dropped when a user who is
present submits a job.

### `POST /api/presence/heartbeat`

The open page calls it every 60 s (through cv-app). `204`.

### `POST /api/presence/leave`

Route leave (`ngOnDestroy`) and `pagehide` (`navigator.sendBeacon`, so cv-app takes it as POST with the session cookie).
`204`.

### `POST /api/unload`

`202 {"state": "UNLOADED" | "PENDING"}`.

### `GET /api/state`

```json
{
  "comfyuiUp": true,
  "loaded": true,
  "lastModel": "muse-txt2img",
  "queueLength": 2,
  "runningJobId": "3f0c...",
  "unloadPending": false,
  "idleUnloadAt": "2026-10-26T12:40:00Z",
  "presentUsers": 1
}
```

## ComfyUI folders

ComfyUI keeps every uploaded photo and every result in its input/ and output/ folders and has no API to delete them.
They are the volume `imagegen-comfy-io` (/io in cv-comfyui, /comfy-io in cv-imagegen-app, both uid 1000): the
service deletes the inputs after the prompt and the output once downloaded (`ComfyFiles`).

## Storage

Volume `imagegen-results` mounted at `/data`:
- `/data/jobs/<jobId>.json` - the job record (everything of `GET /api/jobs/{id}` plus the user id)
- `/data/jobs/<jobId>.png|.mp4` - the result, `<jobId>.input.png` / `<jobId>.mask.png` - the inputs

No database: the record is a small file next to the result, so both disappear together. Loaded into memory at
start; a record QUEUED or RUNNING at start becomes FAILED (`"error": "service restarted"`). An hourly job deletes
everything finished more than 10 days ago (`imagegen.retention`).

## cv-app side

`/api/image-generation/**` mirrors the endpoints above one to one (same paths after the prefix, same bodies), only for
ROLE_ADMIN and ROLE_ELITE (403 otherwise, guests 401), adds `X-User-Id`/`X-User-Name`, streams the result and input
files through (with `Range`). No table in cv-app.
