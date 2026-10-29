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
| `krea2-realism-txt2img` | IMAGE   | -             | image/png  | 1024x1024 6 steps ~12 s          |
| `cyberrealistic-txt2img` | IMAGE   | -             | image/png  | 1024x1024 9 steps ~15 s          |
| `moody-txt2img`      | IMAGE   | -             | image/png  | 1024x1024 8 steps ~15 s          |
| `muse-edit`          | EDIT    | image         | image/png  | ~60 s                            |
| `muse-inpaint`       | INPAINT | image + mask  | image/png  | ~60 s                            |
| `wan22-i2v-fastmove` | VIDEO   | image         | video/mp4  | 832x480 5 s ~160 s               |
| `wan22-i2v`          | VIDEO   | image         | video/mp4  | 832x480 5 s ~145 s               |
| `ltx25-i2v`          | VIDEO   | image or -    | video/mp4  | 1216x704 5 s with sound ~115 s   |
| `ltx25-nsfw-i2v`     | VIDEO   | image or -    | video/mp4  | the same, ~150 s                 |

`wan22-i2v` (base Wan 2.2) keeps the face of a real person best of the four: Fast Move and the NSFW merges
drift towards their own faces. Both LTX graphs condition the first frame with strength 1.0 on both stages
(0.7 on the first stage let the face drift).

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

A VIDEO mode also has `"clipSeconds"` and `"sceneSeconds"` (see "Long videos").

Param types: `TEXT`, `INT`, `FLOAT`, `ENUM` (with `options`), `SEED` (empty = random), `BOOL` (true / false). Only params listed in the
catalogue are accepted from the client; the rest of `<id>.params.json` (`filenamePrefix`, LTX `halfWidth`, mask blur
...) is set by the service. Derived values:
- EDIT / INPAINT: `width`/`height` are not client params; they come from the photo: scaled to at most ~1.05 MP
  (1168x880 for 4:3, 1024x1024 for a square), both sides rounded down to a multiple of 16.
- VIDEO size is an `orientation` ENUM (`landscape` / `portrait`): Wan 832x480 / 480x832, LTX 1216x704 / 704x1216
  (the service sets the LTX stage-1 `halfWidth`/`halfHeight`).
- VIDEO length is a client param in seconds (`seconds`: Wan 2-20, LTX 2-30), converted to frames: 16 * s + 1 for Wan (16 fps),
  24 * s + 1 for LTX (24 fps); both satisfy the frame rules of the models (4n+1, 8n+1). LTX 10 s ~195 s, 12 s ~255 s.
  `clipSeconds` of a VIDEO mode (Wan 6, LTX 12) is the longest clip made in one run: a longer video is a long video
  made of scenes of at most `sceneSeconds` (Wan 5, LTX 10), see "Long videos".
- `needsImage` = the job is refused without an image; `acceptsImage` = an image may be given. LTX has
  `needsImage: false, acceptsImage: true`: without an image the video is made from the text alone, the service adds
  `"textOnly": true` to the params of the job (shown in the job JSON, not accepted from the client) and the workflow
  skips its start image.
- VIDEO `keepFace` (BOOL, default false since 2026-10-29; ignored for a long video, where it would pull every scene
  back to the start picture) keeps the face of the start image: the image is given to the model again later
  in the clip, so the face does not drift away (`keepFace` of `<id>.params.json` names how, see `FaceAnchor`). Wan:
  the start image is also the last frame (WanFirstLastFrameToVideo). LTX: the start image is a guide frame about every
  2.7 s and on the last frame, on both stages (LTXVAddGuide at strength 0.5, then LTXVCropGuides); 8 s took 308 s
  instead of 182 s. Strength 1.0 made a turning head jump back to the photo at every guide, 0.3 let the face drift.
  The person comes back to the pose of the picture there and the motion gets calmer, so it is a bad fit for a scene
  where someone leaves the frame. Ignored for a video from the text alone. LTX 12 s with it once spilled out of the
  16 GB of VRAM (6-10 min at ~50 W instead of ~1.5 min): one of the reasons it is off by default.

## Jobs

### `POST /api/jobs` (multipart)

| Part      | Required                  | Content                                                          |
|-----------|---------------------------|------------------------------------------------------------------|
| `request` | yes                       | JSON `{"model": "muse-inpaint", "params": {...}, "sourceJobId": null}` |
| `image`   | EDIT, INPAINT, Wan        | jpeg / png / webp, at most 20 MB                                 |
| `mask`    | INPAINT                   | png, white = area to redraw; resized to the image size           |

`sourceJobId` = a DONE image job of the same user used as the input image instead of the `image` part (animate or
edit a picture generated before, without downloading and uploading it). For LTX both are optional.

For a video, `sourceJobId` may also be an image job still QUEUED or RUNNING: the page sends the picture job and the
video right after it ("a video from a text" for Wan). The video waits in the queue behind it; when its turn comes, the
picture is written as its input (`/input` answers 404 until then). A picture job that failed, was cancelled or deleted
by then fails the video with an error like `"the start image job ended as CANCELLED"`, without ComfyUI. Other kinds
are refused with 400 while the source has not finished.

Answer `202`:

```json
{"jobId": "3f0c...", "status": "QUEUED", "position": 1, "seed": 123456789}
```

`position` = jobs ahead of this one including the running one (0 = it runs now). `status` may already be
RUNNING when the queue was empty. The users take turns: the next job is the oldest one of the user served longest
ago, the jobs of one user keep their order. So `position` may grow when another user adds a job.

Errors (body `{"error": "<code>", "message": "..."}`):
- `400 invalid_params` - unknown model, a param out of range, missing image/mask, unreadable image
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
  "result": null,
  "pinned": false
}
```

- `status`: `QUEUED`, `RUNNING`, `DONE`, `FAILED`, `CANCELLED`
- `progress.phase`: `TRANSLATING` (the Russian texts are translated into English), `LOADING` (running, no sampler
  step reported yet: models load), `SAMPLING` (value/max of the
  current sampler; video and LTX have two samplers, the bar restarts), `DECODING` (after the last step); a long video
  also `STORYBOARD`, `WAITING`, `JOINING`, and `progress.scene` / `progress.scenes` (see "Long videos")
- A run of ComfyUI fails with `"timeout: ComfyUI reported nothing for N min"` when no event of it came for
  `imagegen.stall-timeout` (10 min; a sampler reports every step, a slow one too), and with `"timeout"` after
  `imagegen.job-timeout` (60 min) however lively. Until 2026-10-29 a fixed 15 min failed a slow but alive LTX job.
- `result` when DONE: `{"contentType": "image/png", "size": 1834221, "width": 1024, "height": 1024}`
  (`durationSeconds` for video)
- `translations`: param name -> the English text the model got instead of the typed one. Every text param with
  Cyrillic in it (`prompt`, `negativePrompt`) is translated by a local uncensored model (Ollama on the CPU, see
  `imagegen.translator`) when the job starts; `{}` when nothing needed it. A failed translation fails the job with
  `"prompt translation failed"`. The translation model is unloaded together with the GPU model.
- `expiresAt` = `finishedAt` + 10 days (from the last unpin when later), null for a pinned job
- `pinned` = kept past the 10 days, not deleted until unpinned (`PUT` / `DELETE /api/jobs/{id}/pin`)

### `GET /api/jobs?limit=50`

History of the user, newest first: the same objects as `GET /api/jobs/{id}`. At most 10 days back, pinned jobs of
any age.

### `GET /api/jobs/{id}/result`

The file (`image/png` or `video/mp4`), `Content-Disposition: attachment; filename="<model>-<yyyyMMdd-HHmmss>-<seed>.<ext>"` (time in UTC)
with `?download=true`, `inline` otherwise. Supports `Range` (video seeking). 404 unless DONE.

### `GET /api/jobs/{id}/thumbnail`

A small JPEG of the result for the history cards: the shorter side scaled down to 480 px, a video gives its first
frame (decoded by JCodec in Java, no ffmpeg in the image). Made on the first request (~0.1 s) and kept as
`<jobId>.thumbnail.jpg`; one is made at a time. 404 unless DONE, 500 `thumbnail_failed` when the result cannot be
decoded.

The result, the input and the thumbnail never change once written: they are sent with
`Cache-Control: private, max-age=31536000, immutable`, so the browser keeps them across visits.

### `GET /api/jobs/{id}/input`

The input image the job used (png), for the history card of an edit / video. 404 for IMAGE jobs.

### `DELETE /api/jobs/{id}`

- QUEUED -> removed from the queue, status CANCELLED
- RUNNING -> ComfyUI `/interrupt`, status CANCELLED
- DONE / FAILED / CANCELLED -> files and record deleted
- pinned -> `409 pinned`, nothing deleted
Answer `204`.

### `PUT /api/jobs/{id}/pin`, `DELETE /api/jobs/{id}/pin`

Pins or unpins the job: a pinned job is not deleted by the retention cleanup nor by `DELETE /api/jobs/{id}`.
Answer `200` with the job as `GET /api/jobs/{id}` shows it. Only a DONE job can be pinned (`409 not_done`); unpin
works on any job and starts the 10 days again (`unpinnedAt` in the record), so an old job does not go at the next
cleanup.

## Long videos

A VIDEO job longer than `clipSeconds` of its mode (or with its scenes written out in the prompt) is one continuous
clip made of scenes: the video model makes one scene at a time, and every scene after the first starts from the
last frames of the one before (the "tail": LTX 17 frames ~0.7 s, Wan 9 frames ~0.56 s), given as a batch to the start
image node, so the motion goes on across the seam and not only the pose. The scenes are joined at the end without the
tail each one repeats. Tests 2026-10-29 (a man with a guitar): no visible seam in 30 s; a scene that ended with the
man's back to the camera made the next one invent another face, so the storyboard keeps the faces in view at the end
of a scene.

- The scenes, two ways:
  - written out in `prompt`: every scene starts with a mark line `[Scene 2 · 10 s]` (any separator, Russian
    `[Сцена 2, 10 с]` and Estonian `[Stseen 2 · 10]` too), its prompt follows; text before the first mark belongs to
    the first scene. Each scene lasts 2 to `sceneSeconds`; the sum is the length of the video (`seconds` of the job
    is set to it, at most the max of `seconds`). One mark only = a plain clip with that text. The prompt ideas write this form.
    Scenes that do not fit the mode (written for another one: LTX scenes of 10 s given to Wan) are not refused: the
    sum, cut to the max of `seconds`, becomes the length and the job writes the scenes again from the whole text, as
    below (a plain clip when that length fits one clip).
  - not written out, `seconds` above `clipSeconds`: the length is split into the fewest even scenes (30 s LTX =
    3 x 10, 13 s Wan = 5 + 4 + 4) and the job writes their prompts itself, from `prompt` taken as the idea or the
    story, with the idea model (`STORYBOARD`, on the GPU the job holds: ComfyUI unloads its model for it; the CPU when
    the GPU instance fails); the model looks at the start image.
- A scene typed with Cyrillic is translated once (`english` of the scene); `prompt` of a long video itself is not.
- `keepFace` is ignored; scene N has the seed + N - 1.
- The queue runs one scene per turn: between two scenes the job goes back into the queue (`status` stays RUNNING,
  `progress.phase` `WAITING`, `position` its place), so it holds the other users up by one scene at most. A failed
  step (the storyboard or a scene) is tried once more; a second failure fails the job. The join runs right after the
  last scene (`JOINING`, ~20 s, ComfyUI core `LoadVideo`, `Video Slice`, `ConcatenateVideo`, sound included).
- `progress.scene` / `progress.scenes`: the scene the phase belongs to and how many (absent for a single clip).
- The job JSON has `"scenes": [{"seconds": 10, "prompt": "...", "english": "..."}]` (`prompt` absent until the
  storyboard is written, `english` only for a translated scene); absent for a single clip.
- Time: LTX 30 s ~13 min (scenes of 10 s ~4-4.5 min each), Wan 20 s ~13 min (pieces ~3.2 min each). Wan holds
  20 s only: in the 30 s test its chain blurred and shifted its colours from ~17 s on (black socks turned green), LTX
  stayed clean for 30 s.
- While it runs the scenes and the tail are kept as `<jobId>.scene<N>.mp4` and `<jobId>.tail.png`; they are deleted
  after the join, a failure, a cancel, and at the start of the service (a long video does not go on after a restart:
  it becomes FAILED like any pending job).

## Prompt ideas

### `POST /api/prompt-ideas` (multipart)

The prompts of a video written from a short idea ("люди гуляют в парке") by an uncensored language model that
sees pictures (`imagegen.idea`). Nothing is queued: the page puts the prompts into its fields and the user starts
the jobs as usual. While no job runs or waits the model runs on the GPU (`cv-ollama`, 7-17 s with a cold load): the
model of the last job is unloaded for it, a job submitted meanwhile waits for the idea, and the idea model leaves the
GPU before the job starts. While a job has the GPU, or the GPU instance fails, the CPU one (`cv-ollama-cpu`,
25-50 s) answers. The model must be pulled into both instances.

| Part      | Content                                                                                            |
|-----------|----------------------------------------------------------------------------------------------------|
| `request` | JSON: `idea` (up to 1000 characters, any language; may be empty with a start picture: the model chooses the action from the picture), `videoModel` (a VIDEO mode), `seconds` (null = the default of the mode, clamped to its range), `frame` (true: the start frame is made by a picture model first), `orientation` (`landscape`/`portrait` of that frame), `sourceJobId` (the start picture is this finished image job of the user) |
| `image`   | the start picture of the user (optional; ignored with `frame` or `sourceJobId`)                    |

Three cases, by what the video starts from:

- `frame: true` -> `{"framePrompt": "...", "videoPrompt": "..."}`: both from one answer of the model, the frame prompt
  written first, so the video continues exactly that frame (same people, clothes, place, light).
- a picture (`image` or `sourceJobId`) -> `{"videoPrompt": "..."}`: the model looks at the picture (768 px) and
  animates what it shows; with `sourceJobId` of an IMAGE job its (English) prompt is given to the model as well.
  Without an idea the model chooses the action itself: it lists the people and animals of the picture first, they do
  something lively (a cat plays, a dog runs, a man laughs) and nobody new comes in; a place without them stays empty
  (a camera move, the light, water, clouds, leaves), and the sentences of such a prompt on what is not there ("No
  person enters the frame") are dropped, since the video model draws what they name.
- neither, for a mode that runs from the text alone (LTX) -> `{"videoPrompt": "..."}` describing the looks too.

The prompt style follows `prompt-style` of the mode: `WAN` (2-4 sentences of motion and camera, no sound) or `LTX`
(one paragraph in time order with camera, light and sound; longer for a longer clip: 110-170 words for 5 s, 180-240
for 12 s). The prompts are English, so the translator leaves them.

`seconds` above `clipSeconds` of the mode: `videoPrompt` has the scenes of a long video written out
(`[Scene 1 · 10 s]` + prompt, a blank line, `[Scene 2 · 10 s]` ...), all from one answer: the model writes the cast
(the phrases every scene calls the people by), the place, the background sound (LTX), the story, then every scene
and where everyone is at its end, which is where the next scene starts. ~1 min on the GPU for 30 s; the idea timeout
is 8 min for the CPU (cv-app waits up to 9 min).
Errors: 400 `invalid_params` (empty idea without a picture, too long idea, not a VIDEO mode, a mode that needs a
picture without one, `sourceJobId` not a finished image job of the user), 503 `idea_unavailable` (no model set, no
answer, no JSON).

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
- `/data/jobs/<jobId>.png|.mp4` - the result, `<jobId>.input.png` / `<jobId>.mask.png` - the inputs,
  `<jobId>.thumbnail.jpg` - the thumbnail of the result (made on the first request), `<jobId>.scene<N>.mp4` /
  `<jobId>.tail.png` - the scenes of a long video while it runs

No database: the record is a small file next to the result, so both disappear together. Loaded into memory at
start; a record QUEUED or RUNNING at start becomes FAILED (`"error": "service restarted"`). An hourly job deletes
everything finished more than 10 days ago (`imagegen.retention`), except the pinned jobs.

## cv-app side

`/api/image-generation/**` mirrors the endpoints above one to one (same paths after the prefix, same bodies), only for
ROLE_ADMIN and ROLE_ELITE (403 otherwise, guests 401), adds `X-User-Id`/`X-User-Name`, streams the result and input
files through (with `Range`) and passes `Cache-Control` on. No table in cv-app.
