# Task 5: cv-ui image generation page (Sonnet, effort high)

Project: `D:\WorkDisk\JAVA\CV-gaile.ee\CV\frontend` (Angular, standalone components, routes in `app.routes.ts`,
texts in `src/assets/i18n/{en,et,ru}.json`). Same branch `image-generation` as task 4 (after task 4 is reviewed).
Read first: `..\imagegen\docs\CONTRACT.md`, memories `image-generation-plan.md` (STEP 2-4), `local-ui-check.md`,
`admin-panel-menu.md`, `estonian-holidays-page.md` (menu "More" group and the width budget), `prerender-setup.md`.

## Route and access

- Route `image-generation` (no language prefix needed: not public, not in the sitemap, not prerendered - add it to
  the server routes as client-only like the other guarded pages), lazy `loadComponent`, a guard that allows
  ROLE_ADMIN or ROLE_ELITE (see `service/admin.guard.ts`; make an `imageGenerationGuard` next to it).
- Menu: an item in the "More" group visible only for ROLE_ADMIN / ROLE_ELITE. Do not break the menu width budget.
- `<title>` and noindex like the other admin pages.

## Page

Layout: settings on the left, result on the right (stacked on a phone, 16 px side gutter, no horizontal scroll).

1. **Mode tabs**: Picture / Photo edit / Video (EN/RU/ET texts). Inside a tab, a model select from `GET models`
   filtered by kind: Picture = IMAGE; Photo edit = EDIT ("by instruction") + INPAINT ("paint the area"); Video = VIDEO.
2. **Form built from the catalogue params**: TEXT -> textarea (prompt big, negative collapsed under "More
   settings"), INT/FLOAT -> number input + range slider with min/max/step, ENUM -> select, SEED -> number input with a
   dice button (empty = random). Defaults from the catalogue; "Reset to defaults" link. Remember the last values per
   model in localStorage (try/catch).
3. **Input image** (EDIT, INPAINT, VIDEO): drop zone + file picker (jpeg/png/webp, at most 20 MB, checked before
   upload), preview; or "use this result" from the history (sends `sourceJobId`).
4. **Mask canvas** (INPAINT): the photo with a canvas on top; brush with a size slider, eraser, clear, undo (at least
   10 steps); touch and mouse (pointer events); the mask is exported at the photo's real resolution as a png,
   white = paint, black elsewhere (the overlay shown semi-transparent red). Disable Generate until something is
   painted.
5. **Generate** -> `POST jobs` (multipart). Then poll `GET jobs/{id}` every 2 s while QUEUED/RUNNING (stop when the
   tab is hidden, resume on visible): show "In queue: N ahead", "Loading the model..." (LOADING), a progress bar for
   SAMPLING with step value/max (restarts for video stage 2 - label it), "Finishing..." (DECODING), the elapsed time
   and "usually ~X s" from typicalSeconds. Cancel button (`DELETE`). Errors: 429 -> "you already have 3 jobs";
   503 -> "generator unavailable"; FAILED -> the error text.
6. **Result**: image (fit to the panel, click = full size in a new tab) or `<video controls>` with the result URL;
   Download button (`/result?download=true`), "Repeat" (same params and seed), "New seed", "Use as input" (to Photo
   edit or Video tab with `sourceJobId`), Delete.
7. **History**: grid of the user's jobs (`GET jobs`), newest first, thumbnails (images = the result itself with
   `loading="lazy"`, video = `<video preload="metadata">`), status badge for running ones, "expires in N days".
   Clicking a card opens it in the result panel and fills the form with its params. A job submitted, then the tab
   closed and reopened, shows up here and is polled again.
8. **GPU state** (small line at the top): from `GET state` every 15 s: "Model loaded (muse-txt2img), unloads at
   12:40" / "Model not loaded, the first generation takes longer" / queue length; button "Unload now" (`POST unload`,
   shows "will unload after the queue" for PENDING).
9. **Presence**: `POST presence/heartbeat` at once and every 60 s while the page is open; on route leave
   (`ngOnDestroy`) `POST presence/leave`; on `pagehide` the same POST with `fetch(url, {method: "POST", keepalive: true,
   headers: {Authorization: ...}})`. NOT `navigator.sendBeacon`: it cannot send the Authorization header, and this
   app authenticates by the access token in that header only (the refresh cookie is SameSite=Strict, /auth/** only).
   Use the same token source as the HTTP interceptor of the app.
   Guard all browser-only code for SSR (`isPlatformBrowser`).

## Rules

- Follow the look of the existing pages (the admin user roles page, the chat page) and their CSS variables; dark
  mode like the rest of the site.
- Texts in en/et/ru (the site's i18n), Estonian may be simple; no hard-coded UI strings.
- Lazy imports only, the main bundle must not grow (check `ng build` stats before/after, see `pagespeed-baseline.md`).
- Check in the browser per `local-ui-check.md` (dev jar + local imagegen app on :8390 + cv-comfyui on :8188):
  one 512x512 8-step Muse picture, one inpaint with a painted mask at the smallest size, no video runs (GPU time).
  Phone width too (Chrome device mode).
- `npm test` / lint as the project uses them must pass. Comments in English. CRLF.
- No commits: leave the diff for Opus. Report: screens checked, bundle size change, open questions.
