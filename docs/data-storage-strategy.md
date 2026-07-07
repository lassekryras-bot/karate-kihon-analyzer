# Data Storage Strategy

## Purpose

This document describes how the project should store training data, analysis results, videos, snapshots, overlays, GIFs, and reports.

The strategy is designed for daily home training, where a user may create many recordings over time. It balances long-term learning history, visual coaching value, storage efficiency, privacy, and the ability to regenerate coaching visuals later.

This is an architecture strategy only. It does not define a database schema, exact file format, UI implementation, cloud sync implementation, or video compression details.

---

## Core Principle

Compact structured analysis data is the source of truth.

Visuals are derived coaching artifacts.

Original video is optional long-term storage.

The app should permanently store compact analysis data that represents what happened during training. Rendered visuals should be treated as generated artifacts created from that analysis data whenever possible. Original videos should not be required after analysis has completed unless the user explicitly chooses a video archive mode.

---

## Source Of Truth: Compact Analysis Data

The app should permanently store structured analysis data by default.

Examples include:

- Training sessions
- Strike events
- Landmark timelines
- Peak frames
- Measurements
- Confidence values
- Analysis results
- Technique feedback

This data is compact compared with video and rendered media. It is also the best long-term representation of training progress because it can be queried, compared, summarized, and used to regenerate visual coaching artifacts.

Structured analysis data should answer questions such as:

- What sessions did the user complete?
- Which strikes were detected?
- What happened during each strike?
- What were the relevant body landmarks over time?
- Which frame represented impact or peak extension?
- What measurements and confidence values were calculated?
- What feedback did the analyzer produce?
- How did the user's technique change over time?

---

## Visuals As Derived Coaching Artifacts

Snapshots, overlays, punch-path images, GIFs, reports, and comparison views are important, but they should usually be derived from stored analysis data.

Examples of generated visual artifacts include:

- Impact snapshots
- Punch-path overlays
- Best and worst punch snapshots
- Movement timeline renderings
- Animated punch paths
- GIFs
- Rendered overlay videos
- Technique reports
- Before/after comparison views

These artifacts are valuable because they make the analysis understandable to the user. However, retaining every generated image, animation, and rendered video forever can create uncontrolled storage growth.

The system should prefer storing enough structured data to recreate visuals later, then selectively retaining only the most useful generated artifacts.

---

## Original Video As Optional Media

Original videos are the largest and most privacy-sensitive assets.

The app should not require the original video after analysis has completed, unless the user explicitly wants video archive mode or a specific feature depends on the original footage.

Original video retention should be user configurable. Possible options include:

- Delete after analysis
- Keep for a short time
- Keep latest sessions only
- Keep manually selected sessions
- Always keep

If a visual artifact or future workflow cannot be regenerated without the original video, that dependency should be clearly marked so the user understands the storage tradeoff.

---

## Storage Layers

### Layer 1: Permanent Analysis Data

Keep by default.

This layer is the long-term source of truth.

It contains:

- Session metadata
- Camera view context
- Calibration information
- Strike events
- Landmark timelines
- Peak-frame landmarks
- Analysis measurements
- Confidence values
- Technique feedback

Layer 1 should be compact enough to keep indefinitely for normal daily use. It should support long-term history, trend analysis, progress review, and regeneration of visual artifacts.

### Layer 2: Recommended Visual Artifacts

Normally keep.

This layer contains selected visuals with high coaching value, such as:

- Selected impact snapshots
- Best punch snapshots
- Worst punch snapshots
- Key coaching overlays
- User-favorited visuals

These artifacts help the user review and understand training sessions quickly. They should still be considered derived artifacts rather than the authoritative record.

Layer 2 should focus on visuals that are especially useful, user-selected, or difficult to reproduce exactly.

### Layer 3: Temporary Generated Visuals

Generate on demand or cache temporarily.

This layer contains larger or more numerous generated assets, such as:

- Animated punch paths
- GIFs
- Rendered overlay videos
- Comparison videos
- Experimental debug renderings

These artifacts can consume significant storage if retained indefinitely. They should be regenerated later from stored Strike Events whenever possible.

Layer 3 should be safe to delete unless the user favorites, exports, or explicitly saves a generated artifact.

### Layer 4: Original Video

User configurable.

Original video retention should be an explicit storage choice, not an assumed requirement.

Possible retention modes include:

- Delete after analysis
- Keep for a short time
- Keep latest sessions only
- Keep manually selected sessions
- Always keep

The default product experience should not depend on retaining every original video forever. Video archive mode should be available for users who want complete historical media storage and accept the privacy and storage costs.

---

## Visual Coaching

Visuals are still central to the product.

The visual layer is the coaching interface. Most users will understand their technique through rendered artifacts more easily than through raw measurements.

The user should be able to inspect:

- Impact snapshots
- Punch path overlays
- Movement timeline renderings
- Before/after comparisons
- Best vs worst strikes
- Progress over time

The storage strategy should not minimize visuals or treat them as unimportant. Instead, it should separate visual coaching value from permanent storage requirements.

Whenever possible, visuals should be recreated from stored analysis data. This allows the app to provide a rich visual coaching experience while avoiding unbounded media retention.

---

## Storage Modes

Future versions of the app may expose storage modes that match different user needs.

### Minimal

Keep:

- Analysis data only

Use when:

- Storage is limited
- The user does not want media retained
- Privacy is the highest priority

Minimal mode should preserve long-term learning history while avoiding retained images, generated media, and original videos.

### Standard

Keep:

- Analysis data
- Selected snapshots
- Important overlays

Use when:

- The user wants a balanced coaching history
- The product needs a sensible default
- Storage efficiency still matters

Standard mode should probably be the default mode. It preserves the source of truth and a useful set of visual review artifacts without retaining all generated media.

### Visual Training

Keep:

- Analysis data
- More snapshots
- Selected GIFs or animations
- User-favorited visuals

Use when:

- The user wants a strong visual coaching history
- The user frequently reviews technique visually
- The user values retained coaching media more than minimum storage usage

Visual Training mode should retain more visual artifacts than Standard mode while still treating original video as optional.

### Archive

Keep:

- Analysis data
- Snapshots
- Generated visuals
- Original video

Use when:

- The user explicitly wants full historical media storage
- The user accepts higher storage usage
- The user accepts the privacy implications of retained video

Archive mode should be opt-in because it stores the most personal and storage-intensive data.

---

## Privacy

Videos and renderings may contain sensitive personal information, including:

- The user's face
- The user's home environment
- Family members or background activity
- Personal training habits
- Training schedule and frequency

The app should avoid retaining personal video by default unless the user chooses that behavior.

Generated visuals can also contain private information if they include camera frames or recognizable backgrounds. The product should distinguish between synthetic renderings, cropped or anonymized visuals, and visuals based on original camera frames.

Privacy-sensitive retention choices should be clear, reversible where possible, and easy to understand.

---

## Regeneration Strategy

The app should aim to regenerate visual artifacts from stored Strike Events.

A stored Strike Event should contain enough information to recreate:

- Path overlays
- Impact snapshots
- Timeline views
- Measurement diagrams
- Best/worst strike comparisons
- Progress summaries

Regeneration is easiest when visuals are synthetic or based primarily on landmarks, measurements, and timing metadata.

Some visuals may require original video frames. For example, a snapshot using the actual camera image at impact may not be exactly reproducible if the original video has been deleted. In that case, the artifact should declare its dependency on original video or retained frame imagery.

The system should prefer deterministic rendering from stored analysis data so that deleted cache files can be recreated consistently.

---

## Cleanup Strategy

Future cleanup rules should prevent uncontrolled storage growth.

Possible cleanup rules include:

- Delete temporary generated visuals after N days
- Delete original videos after analysis
- Keep only the latest N full-video sessions
- Keep favorited sessions permanently
- Keep manually exported artifacts outside automatic cleanup
- Warn when storage usage is high
- Allow manual export before deletion
- Allow users to review what will be deleted

Cleanup should respect user intent. Favorited visuals, manually selected sessions, and exported reports should not be removed unexpectedly.

The cleanup system should also explain consequences. For example, deleting an original video may preserve analysis history but prevent exact regeneration of visuals that depended on camera frames.

---

## Relationship To Strike Event Timeline

The Strike Event Timeline design described in `docs/strike-event-timeline-design.md` should be treated as the compact long-term representation of movement.

Strike Events are the structured record of what happened during training. They should capture movement timing, landmarks, measurements, confidence values, and key moments such as peak extension or impact.

Videos and rendered media are optional supporting artifacts around that timeline.

This relationship should guide future architecture decisions:

- Store Strike Events permanently.
- Use Strike Events to regenerate coaching visuals.
- Retain selected visual artifacts for review convenience.
- Retain original video only when the user chooses that tradeoff.

---

## Out Of Scope

This document does not define:

- Database schema
- Exact file format
- UI implementation
- Cloud sync implementation
- Video compression details
- Specific cache eviction algorithms
- Specific media directory layout

Those decisions should be made in future implementation documents and should follow the principles in this strategy.

---

## Expected Contributor Guidance

Future contributors should design storage features around the following assumptions:

- Permanent analysis data is the source of truth.
- Visuals matter because they are the coaching interface.
- Most visuals should be derived artifacts.
- Temporary generated media should be safe to clean up.
- Original video should be optional after analysis.
- Privacy should bias the product away from retaining personal video by default.
- Daily training must be supported without uncontrolled storage growth.
- If a feature depends on retained original video, that dependency should be explicit.
