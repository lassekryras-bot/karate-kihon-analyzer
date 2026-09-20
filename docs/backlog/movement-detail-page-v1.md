# Movement Detail Page v1 — Developer Requirement Spec

Status: IN PROGRESS

## Device feedback follow-up - 2026-09-20

Manual testing found playback oscillating between frames and an oversized overlay on
first opening that corrected after a timeline interaction. The local fix separates
observed playback positions from explicit seek requests, so clamping at a movement
boundary cannot seek the decoder back repeatedly. Compact and expanded views use
nearest-frame seeks, coalesce rapid requests, and ignore stale positions until seek
completion. Play/resume no longer reissues a seek.

The overlay waits for valid video dimensions, fits the image aspect ratio, and redraws
on preparation and video layout changes even while paused. Its initial sizing no longer
depends on stepping a frame or tapping a measurement.

Regression coverage includes early/stale decoder positions, movement-end stopping,
rapid seeks and decoder replacement, and the initial portrait/landscape layout in both
players. `:app:testDebugUnitTest` passed all 396 tests, including the seven new
regression tests, and `:app:assembleDebug` succeeded. Device retest remains pending: open a movement without touching the controls, check alignment,
then play in Video and Analysis, pause/resume, step, and repeat in expanded inspection.

## Local remediation status — 2026-09-20

Movement Detail now resolves one analysis using the approved analyzer-version order.
Its measurements, target geometry, active arm, canonical timestamp and landmark track
stay associated with that analysis. Missing tracks are not replaced with another run's
track. Analysis mode explains unavailable evidence; Analysis Debug includes the selected
analysis ID, state, diagnostic reason and evidence availability.

Target geometry is produced during analysis and persisted in Room's nullable
`MovementAnalysis.geometryJson` column (schema 9). The versioned
`straight_punch_geometry_v1` payload is validated in full before rendering. Unversioned,
unsupported or malformed geometry is unavailable; existing results and recordings are
preserved. Reanalyzing a recording produces the new geometry payload. See
[the presentation contract](../measurement-presentation-contract.md#recording-target-geometry).

Compact and expanded playback preserve paused intent and the shared playback rate.
Dialog cleanup is owned by the lifecycle and is idempotent. Graph playback keeps the
hidden video paused. Backgrounding and recreation restore inspection state while paused.
Frame stepping remains
disabled without recorded samples. Graph ingestion, recording-level speed/deviation
measurements and structured technique findings are still deferred; no values, physical
units or technique faults are inferred from arbitrary measurement keys or pipeline status.

### Manual acceptance still pending

1. Open an existing recording, then reanalyze it. Check retained results, target rays,
   active arm, canonical frame and debug provenance. Legacy geometry may be unavailable
   before reanalysis; recordings and results must remain readable.
2. Pause, expand, select 0.25×, close inspection and play. Confirm both views use 0.25×,
   changing speed while paused does not start video, and no second audio/playback starts.
3. Repeat play → expand → close → reopen; check retained bounds and a single timeline.
   Switch Video / Analysis / Graph and step recorded samples while paused.
4. Rotate and background/return with inspection open; verify timestamp, mode, selected
   evidence and rate restoration without automatic playback.
5. Check missing media/landmarks and absent or legacy geometry: show neutral unavailable
   text, retain valid results, and keep detailed diagnostics behind developer mode.
6. Check portrait/landscape video-overlay alignment, TalkBack tab/control labels,
   large text and normal page scrolling. Graph currently shows its unavailable state.

### Automated validation

The regression tests exercise analysis/version selection, strict track loading with a
real alternative landmark file, exact persisted ray endpoints, atomic rejection of
malformed geometry, geometry persistence through analysis and database reopening,
decoder speed changes while paused/inactive, repeated dialog ownership transfer and
state restoration after backgrounding/recreation. Media-player test doubles model
nonzero-speed autoplay, delayed `isPlaying` status and invalid early pause calls.

Validation before the device-feedback follow-up on 2026-09-20:

- `:app:testDebugUnitTest`: 389 passed, no failures or skips.
- `:karate-analyzer-core:test`: 296 passed (unchanged core task remained up to date).
- `python -m pytest -q`: 345 passed.
- `:app:assembleDebug`: successful; APK at
  `android/KarateClipRecorder/app/build/outputs/apk/debug/app-debug.apk`.

`:app:lintDebug` found
four pre-existing `NewApi` errors in `res/values/styles.xml` and
`res/values-night/styles.xml`: `windowLightNavigationBar` needs API 27 and
`enforceNavigationBarContrast` needs API 29, while the minimum is 26. Those theme files
are unchanged by this task. No movement-code lint errors were reported.

Physical-device acceptance and the deferred analyzer capabilities keep this task
IN PROGRESS.

## 1. Naming

### Page name

Use **Movement Detail** as the product and implementation name for the page.

The page represents one detected movement inside one recording and explains the result for that movement. It is not a lesson page and it is not only a video player.

Recommended user-facing header:

- `Movement 1`
- Secondary context: `Straight punch · Left arm`

Recommended repository filename:

`docs/backlog/movement-detail-page-v1.md`

### Shared component name

Use **Movement Presentation Player** for the reusable synchronized presentation component.

The player is broader than this page. It can present different synchronized representations of recorded or prerecorded movement:

- Video
- Analysis
- Graph
- Pose

Each consumer chooses which modes it exposes.

For **Movement Detail v1**, expose only:

**Video | Analysis | Graph**

Pose remains a supported shared renderer for the Measurement Wiki, tutorials, learning-path content, and developer inspection, but it is not a primary tab on this page.

---

## 2. Objective

Create a dedicated **Movement Detail** page opened from one movement row on the Recording Results page.

The page answers three questions in order:

1. **What happened?** — show the movement and its primary results.
2. **What should I notice?** — show meaningful findings that need attention.
3. **Why did the app reach this result?** — let the user inspect synchronized video, analytical drawing, or graph evidence.

The page must stay understandable without requiring the user to read graphs or debug data.

The normal page is a report first and an inspection tool second.

---

## 3. Core design principles

### 3.1 Result → finding → evidence

The information hierarchy is:

**Result**
→ fixed movement-level outcome such as `Max speed · 8.1 m/s`

**Finding**
→ a concise qualitative issue such as `Wrist flexion at impact`

**Evidence**
→ video, analytical drawing, or graph that explains the result/finding

The page must not make the user infer the main result from a graph.

### 3.2 One synchronized timeline

Video, analysis drawings, graphs, and future Pose rendering must use one canonical movement timeline.

Changing the selected timestamp/frame in one representation changes it everywhere.

The timeline is shared state. Individual renderers must not maintain independent playback positions.

### 3.3 Analyzer calculates; presentation renders

The Movement Detail UI must not perform biomechanics or classification math.

The analyzer / analysis pipeline owns facts such as:

- canonical analysis frame
- active arm
- target classification
- target-height error
- max speed
- timestamp of max speed
- path deviation
- hikite values
- wrist-flexion classification
- tolerances
- important events
- analysis confidence / abstention
- analysis provenance

The presentation layer may perform drawing-only transformations such as:

- normalized coordinates → display pixels
- scale and crop transformations
- rotation / mirroring
- drawing an already-defined line, point, path, arc, label, or marker
- formatting stored values and units

Rule:

> The player may transform analysis evidence into pixels, but it must not create new analysis facts.

### 3.4 Progressive disclosure

The default page must remain clean.

Detailed interaction happens only when the user asks for it by:

- selecting a Key Result
- selecting a finding
- changing Evidence Player mode
- expanding the player
- enabling debug mode in Settings

---

## 4. Page hierarchy

The normal portrait page reads top-to-bottom:

1. Shared app chrome / back navigation
2. Movement title and context
3. Movement Presentation Player
4. Key Results
5. Needs Attention, only when findings exist
6. Analysis Debug, only when debug mode is enabled

There is no standalone graph section below the Key Results.

Conceptually:

**Movement 1**  
Straight punch · Left arm

**Movement Presentation Player**  
Video | Analysis | Graph

**Key Results**  
Target height  
Max speed  
Max path deviation  
Hikite max speed

**Needs Attention**  
Only detected findings

**Analysis Debug**  
Only when debug setting is enabled

---

## 5. Entry and navigation

### 5.1 Entry

Tapping a movement row on Recording Results opens Movement Detail for that exact `SessionMovement`.

The movement row no longer opens playback directly.

### 5.2 Back navigation

Back returns to the exact Recording Results page and preserves its useful scroll/navigation state where practical.

### 5.3 Identity

Movement Detail is addressed by stable movement/session identity, not by a temporary list index alone.

The display number (`Movement 1`, `Movement 2`, etc.) is presentation metadata.

---

## 6. Movement Presentation Player

## 6.1 Purpose

The Movement Presentation Player is the main visual surface.

It presents different synchronized representations of the same movement and same timeline position.

For Movement Detail v1 the visible modes are:

- **Video**
- **Analysis**
- **Graph**

The player should occupy nearly the full available content width.

### 6.2 Initial state

When the page opens:

- playback is paused
- timeline is positioned at the movement's canonical analysis / impact frame when available
- preferred initial mode is **Analysis** when useful analysis evidence exists
- otherwise fall back to **Video**
- do not automatically start playback

The canonical frame is provided by analysis data. The UI does not derive it.

### 6.3 Video mode

Video mode shows the original master MP4 bounded to the movement's retained playback interval.

Video mode represents:

> What actually happened?

Requirements:

- use retained playback bounds for comfortable inspection
- do not crop the authoritative recording in a way that changes analysis geometry
- playback position remains synchronized with the shared timeline
- switching away and back preserves the current timestamp
- playback speed is shared with other time-based renderers where applicable

### 6.4 Analysis mode

Analysis mode presents the actual frame/video plus analyzer-provided visual evidence.

Analysis mode represents:

> Where and how did the result occur?

Possible overlays include, when provided:

- target lines / points
- striking wrist/fist point
- active arm
- wrist or elbow geometry
- path / deviation geometry
- body reference geometry
- important event markers

The renderer must not independently calculate these analytical facts.

The available overlay set is determined by the selected result/finding and the available analysis output.

### 6.5 Graph mode

Graph mode presents the time-series evidence associated with the currently selected result.

Graph is not permanently a “speed graph”.

Examples:

- Max speed → wrist-speed plot
- Hikite max speed → hikite-speed plot
- Path deviation → deviation-over-time plot when that is the approved evidence form
- Joint angle → angle-over-time plot
- Target error → target-error plot only if that measurement has a useful temporal presentation

The selected result determines which plot definition is active.

If a result has no meaningful graph representation, Graph may remain available for another selected metric or show a clear unavailable state. The UI must not invent a graph merely because Graph mode exists.

### 6.6 Future/shared Pose renderer

Pose is a shared Movement Presentation Player capability but is not a Movement Detail v1 tab.

Pose consumes prerecorded landmarks and renders:

- raw skeleton
- simplified person
- designed teaching figure
- highlighted joints / limbs
- trajectories
- instructional geometry

Intended consumers include:

- Measurement Wiki
- tutorials
- Learning Path
- measurement explanations
- developer inspection

Pose may originate from landmarks captured from a human recording even when the original video is not shown.

---

## 7. Shared timeline behavior

### 7.1 Timeline identity

Use real recorded / landmark timestamps as the canonical timeline identity.

Do not implement frame stepping as a fixed arithmetic increment such as:

`+16.67 ms` or `+33.33 ms`

Frame-rate assumptions may be invalid because of:

- 30 fps vs 60 fps
- irregular timestamps
- dropped frames
- decoder behavior
- differences between video and landmark sample timing

### 7.2 Frame number

Frame number is useful presentation/debug information but is secondary to timestamp identity.

Where a canonical frame index exists, show it consistently.

### 7.3 Frame stepping

Expanded inspection supports:

- Previous frame/sample
- Next frame/sample
- Play / Pause / Replay
- timeline seeking
- playback-speed selection
- jump to named evidence events

Stepping moves to the previous/next known valid timeline sample according to the player's timeline contract.

### 7.4 Named events

The timeline may expose named navigation events supplied by analysis/presentation data, such as:

- Start
- Impact / canonical analysis frame
- Peak speed
- Maximum deviation
- Finish
- Chamber
- Maximum extension
- Retraction

The generic player does not know karate semantics; it renders the named events it receives.

---

## 8. Compact vs expanded player

### 8.1 Compact player on the page

The compact player must support normal page scrolling without gesture conflict.

It may support:

- mode switching
- play / pause
- simple previous/next frame controls if visually appropriate
- tapping explicit event/result controls
- Expand

Avoid making every pixel of a compact graph a horizontal drag surface if that interferes with vertical page scrolling.

### 8.2 Expanded inspection mode

Expand opens a dedicated inspection presentation using substantially more screen area.

Expanded mode supports:

- precise timeline scrubbing
- frame/sample stepping
- playback-speed changes
- larger graph
- current sample values
- named event navigation
- richer analysis overlays where available

Expanded mode is still the same Movement Presentation Player and the same timeline state.

It is not a separate analysis calculation path.

### 8.3 Orientation

Expanded media and graph views must work in portrait.

Landscape should be supported, especially for graph inspection, but must not be required.

Rotation should reflow the presentation without changing the selected timestamp, result, or evidence mode.

---

## 9. Graph presentation rules

Graph shape can be visually misleading if arbitrary scaling changes between views.

The generic Graph renderer therefore draws a plot definition supplied by the measurement presentation contract.

The measurement presentation definition owns decisions such as:

- label
- unit
- series
- permitted/meaningful y-axis strategy
- zero inclusion if required
- fixed comparison scale if required
- important points
- selected/maximum marker
- named events
- explanatory metadata

The generic graph renderer owns:

- axes
- labels
- series drawing
- current timeline cursor
- marker drawing
- touch-to-position behavior in expanded mode
- accessibility descriptions

Expanding a graph gives more resolution; it must not silently change the meaning of the scale to exaggerate or suppress a peak.

Comparable graphs should use comparable scale rules where the measurement definition requires that.

---

## 10. Key Results

### 10.1 Purpose

Key Results answer:

> What was the outcome of this movement?

They are fixed movement-level results.

They do not change while the timeline cursor moves.

### 10.2 v1 result slots

The first straight-punch implementation supports:

1. **Target height**
   - Example: `Chūdan · 6.9° high`

2. **Max speed**
   - Example: `8.1 m/s`

3. **Max path deviation**
   - Example: `3.4 cm`

4. **Hikite max speed**
   - Example: `5.6 m/s`

Only show real results that exist.

Do not fabricate placeholder numeric values.

### 10.3 Interaction

Each Key Result may include an evidence presentation instruction.

Example behavior:

| Result | Preferred mode | Focus |
|---|---|---|
| Target height | Analysis | Canonical impact frame + target geometry |
| Max speed | Graph | Wrist-speed plot + peak sample |
| Max path deviation | Analysis or Graph | Measurement-defined best evidence |
| Hikite max speed | Graph | Hikite-speed plot + peak sample |

Tapping a Key Result:

1. selects that result
2. chooses its preferred evidence mode
3. moves the shared timeline to its evidence timestamp/event
4. configures relevant overlay or plot
5. keeps manual `Video | Analysis | Graph` switching available afterward

Example:

`Max speed` → Graph at peak speed → user switches to Analysis → same timestamp → user switches to Video → same timestamp.

### 10.4 Current-frame values

Current-frame/sample values such as:

`Current speed · 6.7 m/s`

belong to inspection presentation, not Key Results.

Do not replace a fixed result such as `Max speed · 8.1 m/s` merely because the user scrubs to another frame.

---

## 11. Needs Attention

### 11.1 Purpose

Needs Attention contains concise, analyzer-produced qualitative findings.

Examples:

- Wrist flexion at impact
- Hikite too low
- Elbow outside allowed alignment
- Excessive forward lean

### 11.2 Conditional display

Only findings that are present are shown.

Do not render a long checklist of green “OK” states.

If there are no findings, the entire section is absent.

### 11.3 Classification ownership

The UI must not contain thresholds such as:

`if wristAngle > X then show warning`

The analyzer / measurement logic owns:

- raw measurement
- tolerance
- classification
- evidence timestamp
- provenance

The UI renders the returned finding.

### 11.4 User-facing wording

Prefer useful qualitative wording over unnecessary precision.

Example:

**Wrist flexion at impact**  
`Your wrist was outside the straight-wrist tolerance.`

The raw angle and threshold may remain available in Analysis Debug.

### 11.5 Interaction

Tapping a finding selects its preferred evidence.

Example:

`Wrist flexion at impact`
→ Analysis
→ impact timestamp
→ wrist-alignment overlay

---

## 12. Analysis Debug

### 12.1 Visibility

The section is named:

**Analysis Debug**

It appears only when the app's debug setting is enabled.

When debug is off, the section does not occupy space on the page.

### 12.2 Purpose

Analysis Debug answers:

> Exactly what data and analysis path produced this result?

It is not part of the normal coaching experience.

### 12.3 Suggested debug content

Movement:

- movement ID
- logical start/end
- retained playback start/end
- duration
- displayed movement number
- active arm

Timeline:

- canonical analysis timestamp
- canonical frame/sample index
- important analyzer event timestamps

Measurements:

- raw values
- tolerance / margin values
- measurement units
- abstention / unavailable reasons
- confidence / visibility / quality values where available

Provenance:

- analyzer key/version
- segmenter key/version
- target-definition version
- landmark track identity
- MLS/source identity
- analysis run identity
- body-measurement/calibration snapshot identity when used

### 12.4 Actionable debug rows

Where useful, tapping a debug timestamp/event should move the shared player to that evidence position.

Developer-only identifiers may be copyable if there is an existing debug interaction pattern.

---

## 13. Data and ownership

### 13.1 Existing repository foundation

Existing repository structures already provide useful foundations:

- `TrainingRepository.movementEvidence(sessionId, displayedNumber)`
- `MovementEvidence`
- `SessionMovement`
- `MasterRecording`
- `MovementAnalysis`
- `MeasurementResult`
- `LandmarkTrack`

Movement Detail should consume repository/domain data through a presentation/state layer rather than directly querying Room from the view.

### 13.2 Existing UI/player reuse candidates

Inspect and reuse/refactor concepts from:

- `assisted/LandmarkPlaybackDialog.kt`
- `wiki/WikiMotionControls.kt`
- `wiki/MeasurementWikiView.kt`
- the existing shared timestamp behavior between wiki pose animation and graph
- existing playback-speed and important-frame navigation patterns

Do not copy these into a second independent playback architecture.

The requirement is to converge toward shared movement-presentation primitives where practical.

### 13.3 Presentation metadata

The analysis/presentation layer should be able to describe, per result/finding:

- display label
- formatted value
- unit
- preferred evidence mode
- evidence timestamp/event
- overlay definition/reference if available
- graph definition/reference if available
- supporting explanation
- debug/provenance reference

The renderer must handle partial availability.

### 13.4 Activity / processing-plan control

The activity and analysis/processing plan determine what analysis exists.

The page must not assume that every movement has every result.

Examples:

- one activity may provide target height only
- another may provide kick chamber and extension results
- a stance activity may provide no video-time graph
- some movements may abstain because evidence quality is insufficient

The UI renders the available presentation data.

---

## 14. Page states

### 14.1 Movement found, analysis incomplete

Show:

- Movement title/context
- Video mode if recording is available
- neutral analysis-unavailable state
- only Key Results/findings that already exist

Do not fabricate pending values.

### 14.2 Analysis complete

Show all available Key Results and findings.

Initial player mode follows the preferred initial evidence contract.

### 14.3 Partial analysis / abstention

Show available results normally.

Unavailable measurements are omitted or explicitly marked unavailable when omission would be confusing.

If an analyzer abstained, user-facing wording should remain concise; detailed reason belongs in Analysis Debug.

### 14.4 Video unavailable but landmarks/results retained

Movement Detail remains usable when persisted evidence survives but the source video is unavailable.

Hide or disable Video with a clear unavailable state.

Analysis and Graph may remain available if their required evidence is retained.

### 14.5 Landmark evidence unavailable

Video can remain available.

Analysis overlays, Pose, and landmark-derived plots must not pretend to be available.

### 14.6 No graph for selected result

Graph mode shows a clear neutral unavailable message or another explicitly selected graph; it must not synthesize unrelated data.

---

## 15. Recording Results thumbnail follow-up

The Movement Detail work requires a correction to the existing Recording Results thumbnail specification.

### 15.1 D1 thumbnail decision

For the movement row thumbnail:

- use a raw extracted frame representing the canonical analysis / impact event selected by the movement's presentation data
- no analytical drawing
- no skeleton
- no target-height line
- no path overlay
- no debug text
- no metric text burned into the image

The surrounding movement row carries the result information.

### 15.2 Thumbnail purpose

The thumbnail is for:

- recognition
- quick scanning
- movement identity
- transition into Movement Detail

It is not the detailed explanation surface.

### 15.3 No silent frame substitution

If the configured canonical thumbnail event cannot be resolved, use an explicit fallback defined by the movement/activity presentation contract or retain a placeholder.

Do not silently select an arbitrary nearby frame in UI code.

### 15.4 Required documentation update

`docs/backlog/recording-results-page-v1.md` currently describes a finish/terminal-frame thumbnail with optional overlays.

That section must be revised when this work is implemented so the two requirements do not contradict each other.

---

## 16. Visual style

Movement Detail must use the established app design language.

### Use

- warm parchment/off-white page background
- charcoal primary text
- strong section headings
- restrained terracotta/red primary accent
- muted Enso palette colors for meaningful secondary categories
- lightly outlined rounded cards
- generous spacing
- minimal shadow
- shared chrome / navigation patterns

### Evidence Player

The player is the visual anchor and should use as much useful page width as possible.

Do not put unrelated metric cards beside the player on a phone.

### Key Results

Use calm compact result rows/cards.

Avoid making the section look like a multicolor analytics dashboard.

### Needs Attention

Use warm ochre/terracotta treatment.

Informative rather than alarmist.

### Avoid

- heavy clinical/dashboard aesthetic
- graph permanently occupying a second large section
- multiple independent timelines
- dense debug text in the normal view
- excessive colored metric tiles
- overall movement score
- fake precision
- duplicated result text inside overlays unless needed for evidence readability

---

## 17. Interaction and gesture rules

Normal Movement Detail is vertically scrollable.

The compact player must not capture gestures in a way that makes vertical scrolling unreliable.

Rules:

- explicit controls may be tapped in compact mode
- Graph preview inside the player may follow the shared cursor
- precise horizontal scrubbing belongs primarily to expanded inspection mode
- expanded graph may use direct drag/touch scrubbing
- provide accessible slider/button alternatives to drag interactions
- all primary actions require usable touch targets
- mode switching must not reset timeline position
- selecting a result must not start playback automatically unless explicitly designed later

---

## 18. Accessibility

- Support TalkBack labels for player modes and controls.
- Announce selected result and evidence mode meaningfully.
- Do not rely on color alone for findings or selected state.
- Maintain logical focus order.
- Provide at least platform-appropriate touch target sizes.
- Support large font scaling without hiding Key Result values/actions.
- Graph data must have an accessible textual summary.
- Frame stepping and seeking must be available without precision dragging.
- Expanded mode must remain dismissible with standard Back behavior.

---

## 19. Research / design rationale

### 19.1 Shared `Video | Analysis | Graph` player

**Pros**

- keeps the page substantially less cluttered
- makes multiple evidence forms clearly related
- allows Key Results to open the best evidence automatically
- preserves one place for time navigation
- scales to future measurements

**Cons**

- a graph is less immediately visible than a permanently displayed graph
- users must understand that tabs are alternative views of the same evidence

**Decision**

Use the shared player. Result-driven switching provides strong discoverability without permanently consuming page space.

### 19.2 One shared timeline

**Pros**

- prevents Video, Analysis, and Graph desynchronization
- makes result-to-evidence navigation predictable
- supports future Pose reuse
- makes frame stepping and named events consistent

**Cons**

- requires deliberate timeline/state ownership
- video frames and landmark samples may not map perfectly one-to-one

**Decision**

Use actual timestamp identity and one timeline controller/state owner.

### 19.3 Analyzer-owned calculations

**Pros**

- results stay reproducible across UI surfaces
- Measurement Wiki, Movement Detail, exports, and future coaching can agree
- UI remains testable and generic
- avoids duplicated biomechanics logic

**Cons**

- analyzers/presentation contracts must persist enough evidence geometry for rich explanations

**Decision**

Persist/return sufficient evidence; do not move calculations into renderers.

### 19.4 Compact report + expanded inspection

**Pros**

- avoids graph-vs-page-scroll gesture conflicts
- keeps normal use simple
- allows precise developer/user inspection when wanted
- scales better across phone sizes/orientation

**Cons**

- one extra action is required for precision scrubbing

**Decision**

Normal page prioritizes reading/scrolling. Expanded mode prioritizes manipulation/inspection.

### 19.5 Result-specific evidence routing

**Pros**

- user does not need to decide which evidence type explains a metric
- bridges summary and explainability
- keeps graphs optional rather than mandatory

**Cons**

- requires presentation metadata for each result
- inconsistent routing would feel unpredictable

**Decision**

Each result/finding explicitly declares its preferred evidence mode and focus event.

### 19.6 Shared Pose renderer outside this page

**Pros**

- reuses real captured motion without exposing source video
- supports Measurement Wiki explanations
- supports tutorials and Learning Path
- can evolve from raw skeleton into designed teaching graphics

**Cons**

- a designed Pose renderer can become a separate rendering system if not kept on the shared timeline/data contract

**Decision**

Keep Pose as a first-class Movement Presentation Player renderer, but hide it from Movement Detail v1.

---

## 20. Explicit exclusions for v1

Do not add:

- overall movement score
- overall “good/bad punch” score
- Pose tab on Movement Detail
- standalone graph section below the player
- graphs for every measurement by default
- calculations inside UI renderers
- UI-owned karate thresholds
- raw debug data when debug mode is off
- automatic playback on page open
- arbitrary frame-time arithmetic
- independent player state for Video, Analysis, and Graph
- thumbnail overlays on Recording Results D1

---

## 21. Implementation phasing

### Phase 1 — Movement Detail shell

- Create Movement Detail navigation from movement rows.
- Load stable movement evidence.
- Build page hierarchy.
- Add Key Results and conditional Needs Attention.
- Add debug-only Analysis Debug shell.
- Correct Recording Results D1 thumbnail contract.

### Phase 2 — Shared Movement Presentation Player foundation

- Extract/refactor shared timeline concepts from existing playback/wiki implementations.
- Implement shared timeline state.
- Implement Video mode.
- Implement Analysis mode with renderer-only geometry transforms.
- Preserve position across mode switches.
- Add expand/collapse behavior.

### Phase 3 — Graph renderer and result routing

- Add generic Graph mode.
- Add measurement presentation/plot definitions.
- Wire Key Result taps to preferred mode + focus timestamp/event.
- Add expanded graph inspection.
- Add frame/sample stepping.

### Phase 4 — Analysis Debug completeness

- Expose raw values, versions, evidence IDs, margins, confidence and provenance.
- Make timestamp rows actionable.
- Verify debug setting completely hides the section in normal mode.

### Phase 5 — Shared Pose extraction/reuse

- Refactor existing wiki pose animation toward the same shared movement timeline/presentation contract where practical.
- Keep Measurement Wiki behavior intact.
- Make Pose available to Wiki/tutorial consumers without adding it to Movement Detail v1.

---

## 22. Testing requirements

### State tests

Verify:

- Video-only movement
- Video + analysis
- Video + analysis + graph
- analysis retained but video missing
- partial results
- analyzer abstention
- no findings
- one finding
- multiple findings
- debug on/off

### Timeline tests

Verify:

- mode switching preserves timestamp
- result tap selects correct mode and timestamp
- finding tap selects correct evidence
- frame/sample step uses known timeline samples
- rotation preserves timestamp/mode/result
- expand/collapse preserves state
- playback bounds remain inside retained movement bounds

### Rendering tests

Verify:

- coordinate transforms match video crop/rotation/mirroring
- analysis overlay uses stored evidence geometry
- Graph cursor tracks timeline
- graph scale rules come from the measurement presentation definition
- current sample values do not replace fixed Key Results

### Navigation tests

Verify:

- Recording Results row → correct movement
- Back → correct recording
- page restoration after process/activity recreation
- debug section visibility follows Settings

### Accessibility tests

Verify:

- TalkBack traversal
- large font
- controls usable without drag
- graph has textual accessible summary
- selected tab is announced
- findings do not rely on color alone

---

## 23. Acceptance criteria

- [ ] Tapping a Recording Results movement opens Movement Detail instead of direct playback.
- [ ] The page title identifies the movement and activity context.
- [ ] The Movement Presentation Player exposes exactly `Video | Analysis | Graph` on this page.
- [ ] Pose is not shown as a Movement Detail tab.
- [ ] Player opens paused at the canonical analysis/impact timestamp when available.
- [ ] Video, Analysis, and Graph share one timeline position.
- [ ] Switching modes never resets the selected timestamp.
- [ ] Key Results are fixed movement-level outcomes.
- [ ] Key Result taps configure the player to the result's preferred evidence mode and timestamp/event.
- [ ] Current-frame values are kept separate from fixed Key Results.
- [ ] Needs Attention appears only when analyzer-produced findings exist.
- [ ] Finding taps open the corresponding evidence.
- [ ] No analyzer threshold/math is duplicated in UI code.
- [ ] Graph is part of the Movement Presentation Player and does not appear as a separate page section.
- [ ] Compact mode preserves reliable vertical page scrolling.
- [ ] Expanded mode supports precision seeking/frame stepping.
- [ ] Real timestamps/samples, not assumed fixed frame intervals, drive the timeline.
- [ ] Analysis Debug is completely hidden when debug mode is off.
- [ ] Analysis Debug exposes useful raw values and provenance when debug mode is on.
- [ ] Recording Results D1 thumbnail uses a clean canonical event frame with no analytical overlay.
- [ ] Page uses the established warm app visual language.
- [ ] Missing/partial evidence produces explicit unavailable states rather than fabricated data.
- [ ] Existing Wiki/player concepts are reused/refactored where appropriate instead of building an unrelated second playback architecture.

---

## 24. Repository references to review during implementation

Existing project material relevant to this work:

- `docs/backlog/recording-results-page-v1.md`
- `docs/app-measurement-wiki.md`
- `docs/measurement-wiki-authoring-guide.md`
- `docs/target-height-foundation.md`
- `android/KarateClipRecorder/app/src/main/java/dk/lasse/karatecliprecorder/training/TrainingRepository.kt`
- `android/KarateClipRecorder/app/src/main/java/dk/lasse/karatecliprecorder/training/TrainingModels.kt`
- `android/KarateClipRecorder/app/src/main/java/dk/lasse/karatecliprecorder/assisted/LandmarkPlaybackDialog.kt`
- `android/KarateClipRecorder/app/src/main/java/dk/lasse/karatecliprecorder/wiki/WikiMotionControls.kt`
- `android/KarateClipRecorder/app/src/main/java/dk/lasse/karatecliprecorder/wiki/MeasurementWikiView.kt`

These are reuse/integration references, not a requirement to preserve their current internal structure.
