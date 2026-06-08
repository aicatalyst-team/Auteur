# Auteur — An AI Film Studio Pipeline for Short Video Creators

> **Multi-role studio · Preset-driven · Self-hosted**

[中文文档 →](./README.md)

Auteur turns "make a short video" into a **virtual film studio**: every step is owned by a distinct AI role (screenwriter, cinematographer, art director, voice actor, composer, critic, producer…), and they hand off through explicit on-disk artifacts. Give it a topic, click generate, and the studio runs the full pipeline end-to-end — script, subtitles, shot list, images, voice-over, BGM, cover art, final video — all persisted, all rerunnable, every step open to human override.

The "content format" (vertical story, horizontal documentary, character-locked drama with branded look-and-feel…) is determined by a single row in the **preset** table. A preset declares each role's prompt yaml, model, art style, voice, Remotion composition, aspect ratio, watermark, BGM rules. Switching format = creating a new preset row in the UI. **No code changes.**

```
topic → brainstorm → screenwriter (+ critic / fact-checker) → voice-over
                ↓
        cinematographer (+ critic) → art director (+ auditor) → producer → analyst
```

---

## What makes Auteur unique: a multi-role AI studio

Auteur isn't a prompt-chaining script. It's a **virtual studio with division of labor, peer review, feedback loops, and a metric-driven retrospective layer**. Each role is its own Spring Service with its own prompt template, its own LLM call/retry policy, and its own DB output table.

### Creative roles

| Role | Service | Responsibility | Output |
|---|---|---|---|
| 🎯 **Brainstormer** | `BrainstormService` | Generates 5-10 topic candidates from historical performance + series continuity, weighted by dynasty/genre/hook scoring | `topic` |
| 📝 **Screenwriter** | `ScriptService` | Expands a topic into a 5-section narrative (A-hook / B-buildup / C-mid / D-reveal / E-coda); high-score topics route to flagship model, others to a budget batch model | `script` + `script_section × 5` |
| 🔍 **Script Critic** | `ScriptCriticService` | LLM self-review with score; below threshold, the critique gets injected back into the writer's context for one rewrite pass | `critic_log` |
| 📚 **Fact-Checker** | `FactCheckService` + `FactCheckFixService` | Extracts historical claims from the script, verifies each, suggests fixes or auto-rewrites | `fact_check_issue` |
| 🪝 **Hook Extractor** | `HookExtractor` | Mines "next-episode preview" hooks from finished scripts to seed the next topic | `series_hook` |
| 🎬 **Cinematographer** | `StoryboardService` | Breaks the script into 20-28 shots (Chinese prompt + English prompt + shot_type + duration); optional **PRECISE_BY_CUE** mode anchors every shot to the SRT timeline by literal text matching | `storyboard_shot × N` |
| 🎬 **Storyboard Critic** | `StoryboardCriticService` | Hard-rule checks: shot variety, extreme-closeup ratio, anchor hit rate | `critic_log` |
| 🎨 **Art Director** | `ImageGenService` + `ShotPromptRefineService` | Batch image generation per shot, with concurrency cap + retry; identity-lock presets inject reference images | `image_asset` |
| 🖼️ **Image Auditor** | `ImageAuditService` | Post-generation review: composition / hands / watermarks / identity consistency, flagged in `review_issues` | `image_asset.review_issues` |
| 🎙️ **Voice Actor** | `VoiceGenService` (Volcano Doubao TTS) | Synthesizes narration + SRT subtitles per `voice_id` / `speed_ratio`, stamps real audio duration back | `voice_asset` + `.srt` |
| 🎵 **Music Curator** | `BgmService` + `BgmMoodTagger` | LLM-derived mood tags drive a Jamendo (CC-licensed) track lookup; track is locked once selected | `bgm_track` + `script_bgm_choice` |
| 🖼️ **Cover Designer** | `CoverGenerationService` + `Java2DCoverRenderer` | Extracts script highlights into multiple cover variants, includes brand identity | `cover_asset` |
| 🎬 **Director** | `DirectorNoteService` | Cross-role visual style + narrative arc + key beats notes — every downstream role consumes it | `director_note` |
| 🎞️ **Producer** | `VideoAssemblyService` + `FfmpegVideoRenderer` / `RemotionVideoRenderer` | Parses SRT → ShotTimingResolver computes per-shot real duration → assembles ImageClips → ffmpeg/Remotion renders → burns subtitles + sidechaincompress BGM ducking | `video_asset` |
| 📊 **Analyst** | `InsightService` + `WeeklyReviewService` + `VideoAttributionService` | Pulls real-world play / retention / engagement metrics scraped by the browser extension; runs hook attribution and weekly retrospective; feeds back to the brainstormer | `weekly_review`, `genre_stat_snapshot` |
| 🧭 **Series Planner** | `SeriesResolver` | Connects "next-episode hooks" across videos into a topic seed for the next round | `series_hook` |

### How the roles collaborate

1. **Explicit artifact handoff.** Each role reads upstream DB tables and writes downstream ones. This is *not* prompt chaining — any single step can be retried in isolation, and every intermediate is visible/editable/deletable in the UI.
2. **Critic-feedback loops.** When self-review roles (Script Critic / Storyboard Critic / Image Auditor) score below threshold, the critique is injected back into the original role's prompt for one rewrite pass. This bounds LLM uncertainty to a tight self-correction loop instead of letting it pollute the whole pipeline.
3. **Shared director's notes.** `DirectorNoteService` maintains a central note (visual style + narrative arc + key beats) that screenwriter, cinematographer, art director, and producer all read. This prevents per-role drift.
4. **Pipeline state machine.** The `pipeline_run` table tracks `PENDING / RUNNING / DONE / FAILED` per stage; the frontend polls `GET /api/runs/{id}` for progress; failures are resumable.

---

## Other key features

**1. Preset-driven.** A single preset row controls every content-format-related knob: each role's prompt yaml, dimensions, model, voice, composition, watermark, BGM toggles, identity lock — switching formats = creating a preset row in the UI. Every save snapshots a `preset_version`; the UI offers one-click rollback. Export = `GET /api/presets/{id}` (returns JSON), import = `POST /api/presets` — versionable in Git.

The repo ships:
- **freeform** — generic baseline (seeded by default); takes `theme + tone + duration_minutes` and produces a vertical short.
- **LifeCopy** — code present but *not seeded by default*; a 1920×1080 horizontal docu-story with identity lock, comic art style, page-flip sound hook, and PRECISE_BY_CUE strict alignment.

**2. PRECISE_BY_CUE: literal shot-to-audio anchoring.** A standard pipeline guesses how many seconds a sentence takes; misalignment between voice and visuals is the perennial problem. In PRECISE_BY_CUE mode, the cinematographer must give each shot an `anchor_text` that is a *literal substring of the script*; after voice-over, the SRT parser maps that anchor onto the real audio timeline, and **shot duration = the seconds the anchor actually occupies in the SRT**. The backend validates: substring match (after normalization), monotonic anchor positions across consecutive shots (no LLM reordering), and falls back to `anchor_match=false` flagging when a shot can't be anchored — the video still renders, but logs and UI surface the issue. This kills the "subtitles don't match the cut" problem at generation time.

**3. Metric-driven retrospective.** The `extension/` directory is a Chrome extension that hooks into the creator dashboards of Douyin / Bilibili / WeChat Channels / Kuaishou; it scrapes plays, retention, and engagement and POSTs them back to Auteur into `published_video`. `WeeklyReviewService` then computes which dynasty × genre × hook combinations performed best, what last week's plans actually shipped, and emits a "weight table + improvement focus" for next week. The Brainstormer reads this when generating new candidates — a **meta-learning layer** for the pipeline.

**4. Local-first with graceful degradation.** TOS object storage configured? Use it. Not configured? Fall back to local `backend/storage/` + a `/api/files/...` static endpoint. Volcano TTS missing? Voice stage gracefully disabled. Jamendo missing? BGM recommendation off, producer renders without BGM. Remotion not installed? `auteur.video.provider=ffmpeg` runs the pure ffmpeg path. LLM speaks OpenAI-compatible — point it at vLLM, DeepSeek, or any commercial gateway. The backend never refuses to start because of a missing optional dependency.

---

## Architecture

```
backend/      Spring Boot 3.3 + JPA + Flyway + MySQL    16 AI roles + pipeline orchestration + REST
frontend/     Vue 3 + Vite + TypeScript + Pinia          Creator workbench + preset library UI
renderer/     Remotion (TypeScript)                      Optional video compositor
extension/    Chrome extension (4 platforms)             Scrapes Douyin / Bilibili / WeChat / Kuaishou metrics
docs/         Design docs
```

The data flow is the schema: `topic → script → storyboard_shot → image_asset → voice_asset → video_asset → published_video → weekly_review`. Roles never RPC each other; everything is decoupled through tables.

---

## Quick start

**Prereqs:** JDK 21, Node 20+, MySQL 8.0+, ffmpeg, an OpenAI-compatible LLM gateway.

```sql
CREATE DATABASE auteur CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

```bash
cd backend/src/main/resources
cp application-local.yml.example application-local.yml
# Fill: spring.datasource.password, auteur.llm.base-url + api-key
# Optional: auteur.voice.volcano.api-key, auteur.tos.*, auteur.bgm.jamendo.client-id

cd backend && mvn spring-boot:run                  # :8082
cd frontend && npm install && npm run dev          # :5174 → /api proxied to :8082
cd renderer && npm install                         # one-time, downloads Chromium ~150 MB
```

Open http://localhost:5174 → top-right admin toggle → Preset Library shows the seeded `freeform` preset.

**First video:**
1. Topic Pool → AI Brainstorm → pick `freeform` → write a theme → generate
2. Topic detail → Configure preset input → fill `theme / tone / duration_minutes`
3. Generate Script (async, ~30s)
4. Script workbench → skip fact-check (off for `freeform`) → Voice & Subtitles
5. Storyboard workbench → generate shot prompts → Image workbench → batch generate
6. Video Assembly → click Compose → ffmpeg/Remotion renders → output in `backend/storage/video/`

---

## Custom presets

1. Preset Library → New Preset (admin mode)
2. Fill basics + `input_schema` (defines the dynamic form fields shown when creating a topic)
3. Edit the three core prompt YAMLs: brainstorm / script / storyboard. Reference input fields via `{{key}}`.
4. Pick a composition: `StoryHorizontal` for landscape, `StoryVertical` for portrait, or write your own Remotion composition and register it in `renderer/src/Root.tsx`.
5. Save → use the preset to create a topic.

Every save snapshots a `preset_version`. The History panel offers one-click rollback.

---

## Configuration

Required in `application-local.yml`:

```yaml
spring.datasource.password: <mysql password>
auteur.llm.base-url: <openai-compatible gateway>
auteur.llm.api-key: <key>
```

Optional (empty → feature degrades gracefully):

| Key | Purpose |
|---|---|
| `auteur.voice.volcano.api-key` | Volcano Doubao TTS. Empty → voice disabled |
| `auteur.tos.access-key/secret-key/bucket` | Volcano TOS. Empty → local path, no public URL |
| `auteur.bgm.jamendo.client-id` | Jamendo lookup. Empty → BGM disabled |
| `auteur.video.ffmpeg.binary-path` | ffmpeg path. Default `/opt/homebrew/bin/ffmpeg` |
| `auteur.video.remotion.enabled` | Remotion renderer toggle. Default true |
| `auteur.alert.feishu.webhook-url` | Feishu alerting bot. Empty → no alerts |
| `auteur.extension.token` | Extension write-back auth. **Override the default in production.** |

---

## Security notes

- `application-local.yml` is gitignored. **Never commit real credentials.**
- The browser extension authenticates to the backend with `auteur.extension.token` — override the default via env var in production.
- Preset `visibility=private` is a **soft flag**, not real authz. The UI gates by an `X-Auteur-Admin` header. For public deployments, put a real auth layer (Nginx basic auth, OAuth2 proxy) in front.
- For self-hosted LLM, recommended: vLLM + Caddy reverse proxy + IP allow-list. For commercial gateways (DeepSeek / Anthropic / Zhipu), use https + key rotation.

---

## Contributing

PRs and issues welcome. Read `docs/PRESET_REFACTOR_PLAN.md` first.

When opening a PR:
- Backend: ensure `mvn -DskipTests compile` passes
- Frontend: ensure `npx vue-tsc --noEmit` passes
- Schema changes: add a Flyway migration with the next `V*` number
- Preset code changes: update the corresponding `preset_seeds/<name>/` files

## License

TBD.
