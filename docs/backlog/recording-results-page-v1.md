# Recording Results Page v1 — Developer Requirement Spec

Status: OPEN

## 1. Objective

Redesign the persistent **Recording** results page (`RecordingsActivity` detail
view) to provide a clear three-level hierarchical review:

1. **Recording-level summary** — what was recorded, when, and with what settings.
2. **Session-level analysis** — how this session compares to the user's own
   historical baseline, once measurement and baseline data become available.
3. **Movement-level list** — scrollable catalog of detected movements with
   individual metrics, comparison cues, and navigation to future movement detail.

The page replaces the current unstyled diagnostic listing in `RecordingsActivity`
with the app's established design language. The three-card hierarchy is the
structural anchor: **Recording summary → Session analysis → Movements**.

### Design scope vs data scope

This specification describes the **stable target interface**. It explicitly
separates the UI layout and state handling—which can be built now—from the data
providers that will populate it incrementally. Sections below mark each data
dependency as **available now** or **future provider** so the implementation
never fabricates data to make the layout appear complete.

---

## 2. UI Structure & Hierarchy

The visual reading order is strictly top-to-bottom:

```text
+-------------------------------------------------------------+
| [‹ Back]                     Recording                      |  Top App Header
+-------------------------------------------------------------+
| +---------------------------------------------------------+ |
| | Alternating straight punches                            | |  Recording Summary
| | 15 Sep 2026 · 20:19                                     | |  Card
| | Punches · Front view · Hands-free                       | |
| | [Recording details  ›]                                  | |
| +---------------------------------------------------------+ |
|                                                             |
| +---------------------------------------------------------+ |
| | Session analysis                                        | |  Session Analysis
| | Analysis not available yet                               | |  Card
| | (or metric tiles when measurements exist)               | |
| +---------------------------------------------------------+ |
|                                                             |
| +---------------------------------------------------------+ |
| | Movements (10)                                          | |  Movements
| | +-----------------------------------------------------+ | |  Card
| | | [#1] [placeholder] Movement 1                       | | |
| | |      [silhouette ] (metrics when available)       › | | |
| | +-----------------------------------------------------+ | |
| | | [#2] [placeholder] Movement 2                       | | |
| | |      [silhouette ]                                › | | |
| | +-----------------------------------------------------+ | |
| +---------------------------------------------------------+ |
+-------------------------------------------------------------+
```

### A. Top App Header

- Standard `SubPageHeader` component.
- Left back navigation arrow returning to the recordings browser calendar/list.
- Title: **Recording**.
- No decorative controls, score chips, or extra content in the app bar.

### B. Recording Summary Card

- **Purpose**: Ground the user in what was recorded.
- **Surface**: Uses `R.color.home_card_surface`, `R.color.app_border`, 16 dp
  corner radius (matching existing card conventions).
- **Primary title**: Activity display name via `RecordingSummary.context`
  (e.g. `Alternating straight punches`).
- **Timestamp**: Formatted session start time
  (e.g. `15 Sep 2026 · 20:19`).
- **Metadata tags**: Activity category (`session.expectedCategory`), camera view,
  trigger mode — whichever session fields are populated.

#### Recording details disclosure

A subtle inline trigger (`Recording details ›`) expands to show:

- Full capture duration
- Resolution, frame rate, device
- File reference
- Processing status / phase / timing
- **Watch full recording** action (when `recording.sourceState == AVAILABLE`)
- **View landmarks (debug)** action (when developer mode is on and landmarks
  are ready)
- **Delete recording** action (with confirmation dialog, existing behavior)
- **Process now / Retry** action (when applicable per current logic)

This consolidates all recording-level utility actions into the summary card,
matching the hierarchy. The current standalone bottom "Recording details" block,
"Play all", and separate delete section are removed.

#### Strict exclusions from this card

- NO overall session score.
- NO focus chip.
- NO "X of Y detected" summary badge.
- NO "recording ended early" warning banner.
- NO "Play all movements" action.

### C. Session Analysis Card

- **Purpose**: Compare this session's kinematic metrics with the user's personal
  baseline.
- **Header**: `Session analysis` (bold, `R.color.app_text_primary`).
- **Sub-header**: `Compared with your recent sessions` or baseline description
  when a baseline is available.

#### Target metric slots

The card layout supports four metric tiles in a 2×2 grid (or adaptable list).
These are **target UI slots**—the layout accommodates them regardless of whether
a measurement provider currently populates them:

1. **Target error / precision** — angular or spatial deviation from the ideal
   target. Lower is better.
2. **Max strike speed** — peak velocity of the striking limb. Higher is better.
3. **Max path deviation** — maximum lateral deviation of the striking limb from
   the ideal path. Lower is better.
4. **Hikite max speed** — peak retraction speed of the pulling hand.
   Higher is better.

#### Directional indicator rules

Performance coloring reflects metric semantics, not numeric sign:

- **Target error & Max path deviation**: Lower is better — a decrease (`↓`) is
  positive/improvement, an increase (`↑`) is negative/deficit.
- **Max speed & Hikite max speed**: Higher is better — an increase (`↑`) is
  positive/improvement, a decrease (`↓`) is negative/deficit.

Colors use the existing app tokens:
- Positive improvement: `R.color.app_success`
- Performance deficit: `R.color.app_error`
- Neutral / insufficient data: `R.color.app_text_secondary`

#### Three-state metric display

Each metric tile has three possible states:

| State | Display |
|---|---|
| **Measurement + baseline available** | Value, `↓ 18% vs avg`, colored indicator |
| **Measurement available, no baseline** | Value only, no comparison arrow or percentage |
| **Measurement unavailable** | Hidden or `—` depending on cleanest layout |

Baseline comparison emerges naturally as history accumulates. The UI must never
show comparison arrows or percentages when the user has no historical data.

#### Current data availability

> **Today**: The `STRAIGHT_PUNCH_SEGMENTS` processing plan produces landmarks
> and segmentation but **no analyzers** (`analyzers = emptyList()`). The legacy
> `guided_jodan_session` plan runs `AndroidPunchMovementAnalyzer`, which produces
> `PUNCH_HEIGHT_ERROR_TORSO_RATIO` and `PUNCH_ELBOW_ANGLE` — neither of which
> maps to the four target metrics above.
>
> Therefore: the Session Analysis card will initially render its
> **unavailable state** for every assisted-capture recording. This is correct
> behavior, not a bug.

#### Unavailable state

When no measurement results exist for any target metric slot, the session
analysis card shows a neutral message:

- `Analysis not available yet` — when the session is still processing or the
  plan has no analyzers.
- `No measurements recorded` — when processing completed with no analysis output.

No fake zeroes, no hidden card, no empty grid.

### D. Movements Card

- **Purpose**: Scrollable catalog of individual detected movement segments.
- **Heading**: `Movements` or `Movements (N)` where N is the count.
- **Container**: Vertical list of movement rows with subtle dividers or spacing.
- **Availability**: Movements appear as soon as segmentation identifies them.
  Analysis is an enrichment step that decorates existing rows — it does not gate
  their visibility.

---

## 3. Movement Row Requirements

Each row represents one detected `SessionMovement`.

### 3.1 Layout

| Left | Right |
|---|---|
| Movement index badge (`1`, `2`, …) | Movement label (e.g. `Movement 1`) |
| Thumbnail (vertical/tall) | Metric chips when available |
| Play button overlay | Chevron / tap affordance |

### 3.2 Thumbnail

**Thumbnail generation does not currently exist and must be added as a separate
capability.** The spec defines the desired behavior; implementation is phased.

- **Source frame**: The movement's **canonical analysis / impact frame**
  (`movement.analysisFrameUs` or `occurrenceUs` fallback) selected by the
  movement's presentation data. If a reliable frame cannot be extracted, the
  placeholder remains rather than silently choosing an arbitrary frame.
- **Clean unannotated frame**: No analytical drawing, no skeleton, no
  target-height line, no path overlay, no debug text, and no metric text burned
  into the image. The surrounding movement row carries the summary metrics and
  the dedicated Movement Detail page provides detailed visual evidence.
- **Crop**: Body-aware crop showing enough of the practitioner to make the
  technique legible for both punches and kicks.
- **Aspect ratio**: Vertical / portrait orientation (taller than wide) to
  prevent punch-only bias and ensure legibility for high kicks and stances.
- **Play button**: Small circular play button overlay (≈30–36 dp), translucent,
  positioned consistently. Secondary to the image.

#### Placeholder state

Until the real thumbnail is ready (either because processing is still running
or because the master video is unavailable):

- Display a neutral **grey outlined human silhouette** (dashed or solid outline
  figure conveying "movement exists, preview pending").
- Seamlessly swap to the real thumbnail once available.

#### Progression

```
movement found by segmenter
  → placeholder silhouette (immediately visible)
  → canonical-event thumbnail (when extraction is available)
  → tap opens Movement Detail for synchronized analytical inspection
```

### 3.3 Movement Row Metrics

Each row supports four compact metric positions matching the session-level
target slots:

- **Target error / precision**
- **Max strike speed**
- **Max path deviation**
- **Hikite max speed**

Each position follows the same three-state logic as the session card:
measurement + baseline → full display; measurement only → value without
comparison; no measurement → hidden or `—`.

Currently, assisted-capture recordings produce no per-movement measurement
results. Movement rows will initially display with placeholder thumbnails and
no metric values. This is the correct v1 behavior.

### 3.4 Movement row without metrics

When no measurements exist for a movement, the row still displays:

- Index badge
- Placeholder or finish-frame thumbnail
- Movement label (`Movement 1`)
- Playback timestamps (if useful as secondary info)
- Chevron / tap target

This is a fully functional row. Metrics are additive decoration, not a
structural requirement.

---

## 4. Data Contract

### 4.1 Available now — recording & movement level

Backed by existing models in `TrainingModels.kt` and `RecordingBrowser.kt`:

- `RecordingSummary` — `session: RecordingSession`, `recording: MasterRecording`,
  `processing: RecordingProcessing?`, `movementCount: Int`, plus computed
  `context`, `status`, `countLabel`.
- `SessionMovement` — `movementId`, `startUs`, `endUs`, `playbackStartUs`,
  `playbackEndUs`, `state: MovementState`, segmentation source/version/track.
- `RecordingProcessing` — `phase: ProcessingPhase`, `state: QueueState`, error,
  timing, plan key/version.
- `MovementEvidence` — per-movement query joining movement, recording,
  observation, labels, events, analyses, measurements, and landmark tracks.

The results page should load `RecordingSummary` + `List<SessionMovement>` from
the existing `RecordingsActivity` data path. No new Room queries are required
for the v1 shell.

### 4.2 Future provider — measurement results

The four target metric slots require new analyzers that produce
`MeasurementResult` rows with appropriate `measurementKey` values. These do not
exist today. The UI must handle their absence gracefully (see § 3.3, § C above).

When measurement results become available, the presentation layer will need:

```kotlin
// New presentation types — to be created when the first measurement provider ships

enum class MetricPolarity { LOWER_IS_BETTER, HIGHER_IS_BETTER }
enum class DeltaDirection { UP, DOWN, NEUTRAL }

data class MetricComparison(
    val metricKey: String,
    val label: String,
    val formattedValue: String,
    val polarity: MetricPolarity,
    val deltaPercent: Double?,        // null when no baseline
    val deltaDirection: DeltaDirection,
    val isImprovement: Boolean?,      // null when no baseline
    val comparisonText: String        // e.g. "↓ 18% vs avg" or ""
)
```

### 4.3 Future provider — historical baseline

Historical comparison uses `TrainingRepository.history(query: HistoryQuery)`.
The query infrastructure exists, but the metric keys it would be queried with
do not. The baseline calculation window (last N sessions, last 1000 punches,
etc.) is not yet defined and should be a separate design decision.

### 4.4 Future provider — movement thumbnails

No movement thumbnail extraction pipeline currently exists. The v1 shell
renders the silhouette placeholder for every movement. Thumbnail extraction
is a separate implementation task (see § 7 phasing).

---

## 5. Page States

The results page must handle these states using the existing `ProcessingPhase`,
`QueueState`, `SessionState`, and `MovementState` enums. No second status model
is introduced.

| Page state | Recording summary | Session analysis card | Movements card |
|---|---|---|---|
| **Processing (landmarks / segmentation)** | Normal | `Analysis not available yet` | `Processing movements…` or empty |
| **Segments found, no analysis** | Normal | `Analysis not available yet` | Movement rows with placeholders, no metrics |
| **Analysis running** | Normal | `Analyzing…` | Movement rows, partial metrics emerging |
| **Analysis partially available / abstained** | Normal | Available metrics shown; unavailable slots show `—` | Per-row metrics where available |
| **Analysis complete** | Normal | All available metrics shown | Full metric rows |
| **No movements found** | Normal | `No measurements recorded` | `No movements were detected.` |
| **Processing failed** | Shows error + retry | `Analysis not available` | Error state or empty |

These states derive from `RecordingProcessing.phase`, `RecordingProcessing.state`,
`SessionMovement.state`, and `MovementAnalysis.state`. The UI renders them
directly rather than mapping through a separate state machine.

---

## 6. Interaction Rules

### Movement row tap

Tapping a movement row should eventually navigate to a dedicated
**movement-analysis surface** for frame-by-frame review, graphs, extended
metrics, and technical detail.

This surface does not exist today. The spec defines the interaction contract
(row tap → movement detail), not the destination implementation. Until a
movement detail view is built:

- The tap may open the existing segment playback
  (`LandmarkPlaybackDialog` scoped to `[playbackStartMs, playbackEndMs]`).
- Or the tap may be a no-op with a subtle visual affordance indicating
  "coming soon".
- The implementation must not create a production Activity solely to satisfy
  the spec.

### Thumbnail play button

Tapping the play button launches segment playback scoped to the movement's
`[playbackStartMs, playbackEndMs]` via the existing `LandmarkPlaybackDialog`.

### Recording details disclosure

Tapping `Recording details ›` in the summary card expands inline (or opens a
sheet) to reveal utility actions: watch full recording, delete, debug landmarks,
process/retry. All current `RecordingsActivity.detail()` functionality is
preserved here.

### Back navigation

Returns cleanly to the recordings browser calendar/list (existing behavior).

---

## 7. Visual Style Requirements

All colors and surfaces reference existing Android resource tokens. No
hard-coded hex values in the spec or implementation.

| Element | Resource token |
|---|---|
| Page background | `R.color.app_background` |
| Card surface | `R.color.home_card_surface` |
| Card border | `R.color.app_border` (1 dp) |
| Card corner radius | 16 dp |
| Primary text (headings) | `R.color.app_text_primary`, bold sans-serif |
| Secondary text | `R.color.app_text_secondary` |
| Accent | `R.color.app_accent` |
| Positive improvement | `R.color.app_success` |
| Performance deficit | `R.color.app_error` |
| Neutral / unavailable | `R.color.app_text_secondary` |
| Dividers | `R.color.app_divider` |

### Visual style goals

- Warm off-white page background with flat white cards.
- Rounded corners with subtle borders (matching Learn and Training screens).
- Bold black headings, grey secondary text.
- Red as the primary accent — restrained.
- Green and red for performance indicators only where measurements exist.
- Clean spacing and generous padding.

### Avoid

- Heavy analytics-dashboard aesthetic.
- Too many nested cards or card-in-card patterns.
- Large saturated colored blocks.
- Debug/technical presentation in the primary view (put behind Recording details).

---

## 8. Explicit Exclusions

The following must **not** appear in the redesigned results page:

- ❌ Aggregated overall session score (e.g. "84/100").
- ❌ "Focus" badge / pill.
- ❌ "Play all movements" playback banner.
- ❌ Standalone bottom "Recording details" card — moved to collapsible disclosure
  in the summary card.
- ❌ "Recording ended early" warning banner.
- ❌ Prominent "X of Y detected" repetition count callout.
- ❌ Fabricated or placeholder metric values — when data is unavailable, show
  the unavailable state, never a fake number.

---

## 9. Implementation Phasing

The work is explicitly incremental. Each phase is independently shippable:

### Phase 1: UI shell with current data

Build the three-card layout using only data available today:

- Recording summary card populated from `RecordingSummary`.
- Session analysis card in its **unavailable** state.
- Movement rows populated from `List<SessionMovement>` with grey silhouette
  placeholders and no metric values.
- Recording details disclosure containing existing utility actions.
- All page states from § 5 handled.

**Data dependencies**: `RecordingSummary`, `SessionMovement`,
`RecordingProcessing` — all exist.

### Phase 2: Movement thumbnail generation

Add finish-frame extraction from the master recording at each movement's
`endUs` timestamp:

- Body-aware vertical crop.
- Placeholder → real thumbnail transition.
- Storage and caching strategy.

**Data dependencies**: `MasterRecording.filePath`,
`SessionMovement.endUs` — both exist. New: frame extraction and crop logic.

### Phase 3: Measurement population

Ship one or more analyzers that produce `MeasurementResult` rows for the four
target metric keys. Wire the session analysis card and movement row metrics to
display available measurements.

**Data dependencies**: New analyzer(s), new `MeasurementDefinition` entries,
new `measurementKey` values.

### Phase 4: Personal-baseline comparison

Implement the historical-average calculation using `HistoryQuery`. Define the
baseline window. Wire comparison indicators (arrows, percentages, coloring) to
the session card and movement rows.

**Data dependencies**: Sufficient historical `MeasurementResult` data,
baseline window definition.

### Phase 5: Movement-detail experience

Build the dedicated movement-analysis surface. Wire row tap navigation.

**Data dependencies**: Analysis data, playback infrastructure, graph rendering.

---

## 10. Acceptance Criteria

### Phase 1 (UI shell)

- [ ] Page follows the three-level hierarchy: Recording Summary → Session
      Analysis → Movements.
- [ ] Visual style uses existing resource tokens and matches Training/Learn cards.
- [ ] Session analysis card shows its unavailable state when no measurements exist.
- [ ] Movement list appears once segmentation identifies movements, regardless of
      analysis status.
- [ ] Movement rows show grey silhouette placeholder thumbnails.
- [ ] Movement rows display no metric values (correct for current data state).
- [ ] Recording details disclosure provides: watch full recording, delete, debug
      landmarks, process/retry — all existing functionality preserved.
- [ ] All page states from § 5 are handled correctly.
- [ ] Excluded items (overall score, play all, standalone bottom details card,
      focus chip, fabricated metrics) are absent.

### Phase 2 (thumbnails)

- [ ] Thumbnails extract the movement finish frame.
- [ ] Vertical/tall crop is legible for both punches and kicks.
- [ ] Placeholder silhouette remains when a reliable finish frame is not
      available.
- [ ] Placeholder → real thumbnail transition is seamless.

### Phase 3 (measurements)

- [ ] Session analysis card displays available metrics.
- [ ] Movement rows display per-movement metric values.
- [ ] Unavailable metrics show `—` or are hidden, never fake values.

### Phase 4 (baseline comparison)

- [ ] Directional indicators respect metric polarity (lower-is-better vs
      higher-is-better).
- [ ] Comparison arrows and percentages appear only when baseline data exists.
- [ ] Value-only display when measurement exists but baseline does not.
- [ ] Colors use `app_success` / `app_error` / `app_text_secondary` correctly.

### Phase 5 (movement detail)

- [ ] Tapping a movement row navigates to the movement-detail surface.
- [ ] Detail surface supports frame review, graphs, and extended metrics.
