"""Deterministic PNG snapshot rendering for completed punch analyses and strike events."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw, ImageFont

from karate_analyzer.angle_analyzer import Point2D
from karate_analyzer.frame_extractor import ExtractedFrameMetadata, extract_frame
from karate_analyzer.analyzers.jodan_height_analyzer import attach_jodan_height_analysis
from karate_analyzer.session_analyzer import PunchAnalysis

_BACKGROUND_COLOR = "white"
_IDEAL_LINE_COLOR = "green"
_ACTUAL_LINE_COLOR = "red"
_SHOULDER_POINT_COLOR = "blue"
_CHIN_POINT_COLOR = "purple"
_WRIST_POINT_COLOR = "black"
_TEXT_COLOR = "black"
_JODAN_GOOD_COLOR = "#1EAD49"
_JODAN_NEEDS_WORK_COLOR = "#FF5A1F"
_JODAN_UNKNOWN_COLOR = "#777777"
_TOLERANCE_BAND_COLOR = (176, 38, 255, 42)

_LANDMARK_COLOR = "#00D084"
_BONE_COLOR = "#00A3FF"
_STRIKE_ARM_COLOR = "#FF5A1F"
_ACTUAL_PUNCH_LINE_COLOR = "#FF5A1F"
_HEAD_COLOR = "#B026FF"
_JODAN_COLOR = "#B026FF"
_JODAN_REFERENCE_LINE_COLOR = (176, 38, 255, 150)
_CHIN_REFERENCE_COLOR = "#00E5FF"
_OPTIMAL_PUNCH_LINE_COLOR = "#FFD23F"
_IDEAL_TARGET_POINT_COLOR = "#FFD23F"
_PANEL_FILL = (255, 255, 255, 218)
_PANEL_OUTLINE = "#222222"

_LOGICAL_X_MIN = -0.2
_LOGICAL_X_MAX = 1.2
_LOGICAL_Y_MIN = -0.6
_LOGICAL_Y_MAX = 0.6
_MARGIN_RATIO = 0.1
_MIN_MARGIN = 20
_POINT_RADIUS = 5
_LANDMARK_RADIUS = 4
_LINE_WIDTH = 3
_BONE_WIDTH = 2
_TEXT_ORIGIN = (16, 16)
_TEXT_LINE_SPACING = 16

_BODY_CONNECTIONS = (
    (11, 12),
    (11, 13),
    (13, 15),
    (12, 14),
    (14, 16),
    (11, 23),
    (12, 24),
    (23, 24),
    (23, 25),
    (25, 27),
    (24, 26),
    (26, 28),
)
_SIDE_LANDMARK_INDICES = {
    "left": {"shoulder": 11, "elbow": 13, "wrist": 15},
    "right": {"shoulder": 12, "elbow": 14, "wrist": 16},
}


@dataclass(frozen=True)
class StrikeSnapshotRenderInstructions:
    """Presentation-only instructions for one strike snapshot."""

    strike_number: int
    strike_side: str
    peak_frame_number: int | None = None
    analysis_frame_number: int | None = None
    timestamp_seconds: float | None = None
    confidence: float | None = None
    jodan_reference: dict[str, Any] | None = None
    jodan_height_analysis: dict[str, Any] | None = None
    impact_point: dict[str, Any] | None = None
    chin_reference: dict[str, Any] | None = None


def render_punch_snapshot(
    punch: PunchAnalysis,
    width: int = 800,
    height: int = 600,
) -> Image.Image:
    """Render a completed punch analysis to a deterministic Pillow image.

    The renderer is presentation-only: it consumes fields that already exist on
    ``PunchAnalysis`` and does not perform karate analysis, scoring, pose
    detection, or impact-frame selection.
    """

    image = Image.new("RGB", (width, height), _BACKGROUND_COLOR)
    draw = ImageDraw.Draw(image)

    shoulder = _map_point(punch.shoulder, width, height)
    chin = _map_point(punch.chin, width, height)
    wrist = _map_point(punch.wrist, width, height)

    _draw_reference_layer(draw, shoulder, chin)
    _draw_analysis_layer(draw, shoulder, wrist)
    _draw_pose_layer(draw, shoulder, chin, wrist)
    _draw_text_layer(draw, punch)

    return image


def save_punch_snapshot(
    punch: PunchAnalysis,
    output_path: Path,
    width: int = 800,
    height: int = 600,
) -> None:
    """Render ``punch`` and save it as a PNG image at ``output_path``."""

    image = render_punch_snapshot(punch, width=width, height=height)
    image.save(output_path, format="PNG")


def render_strike_snapshot(
    background_image: str | Path | Image.Image,
    landmarks: list[dict[str, Any]],
    instructions: StrikeSnapshotRenderInstructions,
) -> Image.Image:
    """Render strike-event overlays on an extracted video frame.

    This function is deliberately presentation-only. It consumes a background
    image, landmark dictionaries, and render instructions; it does not decode
    video, call MediaPipe, detect strikes, or score karate technique.
    """

    image = _load_background_image(background_image).convert("RGBA")
    overlay = Image.new("RGBA", image.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)

    points = _landmark_points(landmarks, image.size)
    _draw_body_connections(draw, points)
    _draw_all_landmarks(draw, points)
    _draw_chin_reference(draw, instructions.chin_reference, image.size)
    _draw_jodan_guides(draw, points, instructions, image.size)
    _draw_strike_landmarks(draw, points, instructions.strike_side)
    if instructions.jodan_reference is None:
        _draw_head_reference(draw, points, landmarks, image.size)
    _draw_strike_text_panel(draw, instructions)

    return Image.alpha_composite(image, overlay).convert("RGB")


def save_strike_snapshot(
    background_image: str | Path | Image.Image,
    landmarks: list[dict[str, Any]],
    instructions: StrikeSnapshotRenderInstructions,
    output_path: str | Path,
) -> None:
    """Render one strike snapshot and save it as PNG."""

    output = Path(output_path)
    output.parent.mkdir(parents=True, exist_ok=True)
    render_strike_snapshot(background_image, landmarks, instructions).save(
        output, format="PNG"
    )


def strike_snapshot_filename(strike_number: int, strike_side: str) -> str:
    """Return the deterministic PNG filename for a rendered strike event."""

    return f"strike-{strike_number:03d}-{strike_side.lower()}.png"


def render_strike_snapshots_from_analysis(
    *,
    video_path: str | Path,
    analysis_path: str | Path,
    output_directory: str | Path,
    frame_directory: str | Path | None = None,
) -> list[Path]:
    """Extract peak frames and render one annotated PNG for each strike event."""

    video = Path(video_path)
    analysis = _load_strike_landmark_events(analysis_path)
    output = Path(output_directory)
    frames = Path(frame_directory) if frame_directory else output / "extracted-frames"
    output.mkdir(parents=True, exist_ok=True)
    frames.mkdir(parents=True, exist_ok=True)

    rendered_paths = []
    for event in analysis:
        instructions = _instructions_from_event(event)
        render_frame_number = (
            instructions.analysis_frame_number
            if instructions.analysis_frame_number is not None
            else instructions.peak_frame_number
        )
        if render_frame_number is None:
            raise ValueError(
                f"Strike {instructions.strike_number} is missing analysis_frame_number and peak_frame_number"
            )
        frame_path = frames / f"frame-{render_frame_number:06d}.png"
        metadata = extract_frame(video, render_frame_number, frame_path)
        event = attach_jodan_height_analysis(event, image_height=metadata.frame_height)
        instructions = _with_timestamp_from_metadata(
            _instructions_from_event(event), metadata
        )
        output_path = output / strike_snapshot_filename(
            instructions.strike_number, instructions.strike_side
        )
        save_strike_snapshot(
            frame_path, _landmarks_from_event(event), instructions, output_path
        )
        rendered_paths.append(output_path)

    if not rendered_paths:
        raise ValueError(f"No strike events found in analysis file: {analysis_path}")
    return rendered_paths


def _load_background_image(background_image: str | Path | Image.Image) -> Image.Image:
    if isinstance(background_image, Image.Image):
        return background_image.copy()
    with Image.open(background_image) as image:
        return image.copy()


def _landmark_points(
    landmarks: list[dict[str, Any]], image_size: tuple[int, int]
) -> dict[int, tuple[int, int]]:
    width, height = image_size
    points = {}
    for landmark in landmarks:
        index = landmark.get("index")
        if index is None or landmark.get("x") is None or landmark.get("y") is None:
            continue
        points[int(index)] = (
            round(float(landmark["x"]) * width),
            round(float(landmark["y"]) * height),
        )
    return points


def _draw_body_connections(
    draw: ImageDraw.ImageDraw, points: dict[int, tuple[int, int]]
) -> None:
    for start, end in _BODY_CONNECTIONS:
        if start in points and end in points:
            draw.line((points[start], points[end]), fill=_BONE_COLOR, width=_BONE_WIDTH)


def _draw_all_landmarks(
    draw: ImageDraw.ImageDraw, points: dict[int, tuple[int, int]]
) -> None:
    for point in points.values():
        _draw_point(draw, point, _LANDMARK_COLOR, radius=_LANDMARK_RADIUS)


def _draw_chin_reference(
    draw: ImageDraw.ImageDraw,
    chin_reference: dict[str, Any] | None,
    image_size: tuple[int, int],
) -> None:
    chin_point = _normalized_point_to_pixels(chin_reference, image_size)
    if chin_point is None:
        return
    _draw_point(draw, chin_point, _CHIN_REFERENCE_COLOR, radius=_POINT_RADIUS)
    draw.text(
        (chin_point[0] + 8, max(0, chin_point[1] - 14)),
        "Chin ref",
        fill=_TEXT_COLOR,
        font=ImageFont.load_default(),
    )


def _draw_jodan_guides(
    draw: ImageDraw.ImageDraw,
    points: dict[int, tuple[int, int]],
    instructions: StrikeSnapshotRenderInstructions,
    image_size: tuple[int, int],
) -> None:
    analysis = instructions.jodan_height_analysis or {}
    jodan_reference = instructions.jodan_reference
    target_point = analysis.get("target_point") or jodan_reference
    jodan_point = _normalized_point_to_pixels(target_point, image_size)
    if jodan_point is None:
        return

    width, _height = image_size
    draw.line(
        ((0, jodan_point[1]), (width, jodan_point[1])),
        fill=_JODAN_REFERENCE_LINE_COLOR,
        width=1,
    )

    actual_start = _normalized_point_to_pixels(
        analysis.get("actual_line_start"), image_size
    )
    actual_end = _normalized_point_to_pixels(
        analysis.get("actual_line_end"), image_size
    )
    ideal_start = _normalized_point_to_pixels(
        analysis.get("ideal_line_start"), image_size
    )
    ideal_end = _normalized_point_to_pixels(analysis.get("ideal_line_end"), image_size)

    if ideal_start is not None and ideal_end is not None:
        _draw_wide_line(draw, ideal_start, ideal_end, fill=_OPTIMAL_PUNCH_LINE_COLOR)
        _draw_point(draw, ideal_end, _IDEAL_TARGET_POINT_COLOR, radius=_POINT_RADIUS)

    if actual_start is not None and actual_end is not None:
        draw.line(
            (actual_start, actual_end), fill=_ACTUAL_PUNCH_LINE_COLOR, width=_LINE_WIDTH
        )
        _draw_point(
            draw,
            actual_end,
            _jodan_height_color(str(analysis.get("status", "unknown"))),
            radius=_POINT_RADIUS + 2,
        )

    if actual_start is not None and actual_end is not None and ideal_end is not None:
        _draw_angle_marker(
            draw,
            actual_start,
            actual_end,
            ideal_end,
            str(analysis.get("status", "unknown")),
        )

    _draw_point(draw, jodan_point, _JODAN_COLOR, radius=_POINT_RADIUS)
    draw.text(
        (jodan_point[0] + 8, max(0, jodan_point[1] - 14)),
        "Jodan reference height",
        fill=_TEXT_COLOR,
        font=ImageFont.load_default(),
    )


def _draw_wide_line(
    draw: ImageDraw.ImageDraw,
    start: tuple[int, int],
    end: tuple[int, int],
    *,
    fill: str,
) -> None:
    draw.line((start, end), fill=fill, width=_LINE_WIDTH + 4)


def _draw_angle_marker(
    draw: ImageDraw.ImageDraw,
    shoulder: tuple[int, int],
    actual_end: tuple[int, int] | None,
    ideal_end: tuple[int, int],
    status: str,
) -> None:
    if actual_end is None:
        return
    marker_radius = 24
    actual_marker = _point_on_segment(shoulder, actual_end, marker_radius)
    ideal_marker = _point_on_segment(shoulder, ideal_end, marker_radius)
    draw.line(
        (actual_marker, shoulder, ideal_marker),
        fill=_jodan_height_color(status),
        width=2,
    )


def _point_on_segment(
    start: tuple[int, int], end: tuple[int, int], distance: float
) -> tuple[int, int]:
    dx = end[0] - start[0]
    dy = end[1] - start[1]
    length = max(1.0, (dx * dx + dy * dy) ** 0.5)
    scale = min(1.0, distance / length)
    return round(start[0] + (dx * scale)), round(start[1] + (dy * scale))


def _normalized_point_to_pixels(
    point: dict[str, Any] | None, image_size: tuple[int, int]
) -> tuple[int, int] | None:
    if not point or point.get("x") is None or point.get("y") is None:
        return None
    width, height = image_size
    return round(float(point["x"]) * width), round(float(point["y"]) * height)


def _jodan_height_color(status: str) -> str:
    if status == "good":
        return _JODAN_GOOD_COLOR
    if status in {"too_low", "too_high"}:
        return _JODAN_NEEDS_WORK_COLOR
    return _JODAN_UNKNOWN_COLOR


def _draw_strike_landmarks(
    draw: ImageDraw.ImageDraw, points: dict[int, tuple[int, int]], strike_side: str
) -> None:
    indices = _SIDE_LANDMARK_INDICES.get(strike_side.lower())
    if not indices:
        return
    arm_points = [points[index] for index in indices.values() if index in points]
    if len(arm_points) >= 2:
        draw.line(arm_points, fill=_STRIKE_ARM_COLOR, width=_LINE_WIDTH)
    for point in arm_points:
        _draw_point(draw, point, _STRIKE_ARM_COLOR, radius=_POINT_RADIUS)


def _draw_head_reference(
    draw: ImageDraw.ImageDraw,
    points: dict[int, tuple[int, int]],
    landmarks: list[dict[str, Any]],
    image_size: tuple[int, int],
) -> None:
    head_point = points.get(0)
    if head_point is None:
        width, height = image_size
        head = next(
            (
                landmark
                for landmark in landmarks
                if landmark.get("source") == "mouth_midpoint"
            ),
            None,
        )
        if head and head.get("x") is not None and head.get("y") is not None:
            head_point = (
                round(float(head["x"]) * width),
                round(float(head["y"]) * height),
            )
    if head_point is not None:
        _draw_point(draw, head_point, _HEAD_COLOR, radius=_POINT_RADIUS + 1)


def _draw_strike_text_panel(
    draw: ImageDraw.ImageDraw,
    instructions: StrikeSnapshotRenderInstructions,
) -> None:
    font = ImageFont.load_default()
    lines = [
        f"Strike #{instructions.strike_number}",
        f"Side: {instructions.strike_side.title()}",
        f"Peak Frame: {_format_optional(instructions.peak_frame_number)}",
        f"Analysis Frame: {_format_optional(instructions.analysis_frame_number)}",
        f"Timestamp: {_format_timestamp(instructions.timestamp_seconds)}",
        f"Confidence: {_format_confidence(instructions.confidence)}",
    ]
    analysis = instructions.jodan_height_analysis or {}
    if (
        instructions.jodan_reference is not None
        or analysis.get("target_point") is not None
    ):
        lines.extend(
            [
                "Actual punch line",
                "Ideal punch line",
                "Jodan reference height",
            ]
        )
    if instructions.jodan_height_analysis is not None:
        result = str(analysis.get("status", "unknown")).replace("_", " ")
        angle = analysis.get("signed_angle_degrees")
        if angle is None:
            lines.append(f"Jodan angle: Unknown ({result})")
        else:
            lines.append(
                f"Jodan angle: {float(angle):+.1f}° {result.replace('_', ' ')}"
            )
        lines.append(f"Reference: {_format_reference_source(analysis)}")
        lines.append(f"Reference confidence: {_format_reference_confidence(analysis)}")
        if analysis.get("unknown_reason"):
            lines.append(f"Unknown reason: {analysis['unknown_reason']}")
    x, y = _TEXT_ORIGIN
    line_height = _TEXT_LINE_SPACING
    panel_width = max(_text_length(font, line) for line in lines) + 20
    panel_height = (line_height * len(lines)) + 16
    draw.rounded_rectangle(
        (x - 8, y - 8, x - 8 + panel_width, y - 8 + panel_height),
        radius=6,
        fill=_PANEL_FILL,
        outline=_PANEL_OUTLINE,
    )
    for index, line in enumerate(lines):
        draw.text((x, y + (index * line_height)), line, fill=_TEXT_COLOR, font=font)


def _text_length(font: ImageFont.ImageFont, line: str) -> int:
    try:
        return round(font.getlength(line))
    except AttributeError:
        return font.getsize(line)[0]


def _format_optional(value: int | None) -> str:
    return "Unknown" if value is None else str(value)


def _format_timestamp(value: float | None) -> str:
    return "Unknown" if value is None else f"{value:.3f}s"


def _format_confidence(value: float | None) -> str:
    return "Unknown" if value is None else f"{value:.2f}"


def _format_reference_source(analysis: dict[str, Any]) -> str:
    return str(analysis.get("reference_source") or "unknown")


def _format_reference_confidence(analysis: dict[str, Any]) -> str:
    return str(analysis.get("reference_confidence") or "unknown")


def _load_strike_landmark_events(analysis_path: str | Path) -> list[dict[str, Any]]:
    payload = json.loads(Path(analysis_path).read_text(encoding="utf-8"))
    if isinstance(payload, list):
        events = payload
    elif isinstance(payload, dict):
        events = payload.get("punch_event_landmarks", [])
    else:
        events = []
    if not isinstance(events, list):
        raise ValueError("Analysis file must contain a punch_event_landmarks list")
    return events


def _instructions_from_event(event: dict[str, Any]) -> StrikeSnapshotRenderInstructions:
    visibility = event.get("visibility", {}) or {}
    confidence = visibility.get("minimum_required_landmark_visibility")
    return StrikeSnapshotRenderInstructions(
        strike_number=int(event.get("event_index", 0)),
        strike_side=str(
            event.get("observed_side") or event.get("expected_side") or "unknown"
        ),
        peak_frame_number=event.get("peak_frame_number"),
        analysis_frame_number=event.get("analysis_frame_number"),
        timestamp_seconds=event.get("timestamp_seconds"),
        confidence=None if confidence is None else float(confidence),
        jodan_reference=event.get("jodan_reference"),
        jodan_height_analysis=(event.get("analysis") or {}).get("jodan_height"),
        impact_point=event.get("impact_point"),
        chin_reference=event.get("chin_reference"),
    )


def _with_timestamp_from_metadata(
    instructions: StrikeSnapshotRenderInstructions, metadata: ExtractedFrameMetadata
) -> StrikeSnapshotRenderInstructions:
    if instructions.timestamp_seconds is not None or metadata.timestamp_seconds is None:
        return instructions
    return StrikeSnapshotRenderInstructions(
        strike_number=instructions.strike_number,
        strike_side=instructions.strike_side,
        peak_frame_number=instructions.peak_frame_number,
        analysis_frame_number=instructions.analysis_frame_number,
        timestamp_seconds=metadata.timestamp_seconds,
        confidence=instructions.confidence,
        jodan_reference=instructions.jodan_reference,
        jodan_height_analysis=instructions.jodan_height_analysis,
        impact_point=instructions.impact_point,
        chin_reference=instructions.chin_reference,
    )


def _landmarks_from_event(event: dict[str, Any]) -> list[dict[str, Any]]:
    landmarks = []
    side = str(event.get("observed_side") or event.get("expected_side") or "").lower()
    indices = _SIDE_LANDMARK_INDICES.get(side, {})
    for name in ("shoulder", "elbow", "wrist"):
        landmark = event.get(name)
        if landmark is not None:
            landmarks.append({"index": indices.get(name), **landmark})
    impact = event.get("impact_point")
    if impact is not None:
        landmarks.append({"index": None, **impact})
    chin_reference = event.get("chin_reference")
    if chin_reference is not None:
        landmarks.append({"index": None, **chin_reference})
    head = event.get("head_reference_candidate")
    if head is not None:
        head_index = 0 if head.get("source") == "nose" else None
        landmarks.append({"index": head_index, **head})
    return landmarks


def _map_point(point: Point2D, width: int, height: int) -> tuple[int, int]:
    """Map synthetic logical coordinates to screen coordinates."""

    horizontal_margin = max(_MIN_MARGIN, round(width * _MARGIN_RATIO))
    vertical_margin = max(_MIN_MARGIN, round(height * _MARGIN_RATIO))
    drawable_width = width - (2 * horizontal_margin)
    drawable_height = height - (2 * vertical_margin)

    x_fraction = (point.x - _LOGICAL_X_MIN) / (_LOGICAL_X_MAX - _LOGICAL_X_MIN)
    y_fraction = (point.y - _LOGICAL_Y_MIN) / (_LOGICAL_Y_MAX - _LOGICAL_Y_MIN)

    screen_x = horizontal_margin + (x_fraction * drawable_width)
    screen_y = height - vertical_margin - (y_fraction * drawable_height)

    return round(screen_x), round(screen_y)


def _draw_reference_layer(
    draw: ImageDraw.ImageDraw,
    shoulder: tuple[int, int],
    chin: tuple[int, int],
) -> None:
    draw.line((shoulder, chin), fill=_IDEAL_LINE_COLOR, width=_LINE_WIDTH)


def _draw_analysis_layer(
    draw: ImageDraw.ImageDraw,
    shoulder: tuple[int, int],
    wrist: tuple[int, int],
) -> None:
    draw.line((shoulder, wrist), fill=_ACTUAL_LINE_COLOR, width=_LINE_WIDTH)


def _draw_pose_layer(
    draw: ImageDraw.ImageDraw,
    shoulder: tuple[int, int],
    chin: tuple[int, int],
    wrist: tuple[int, int],
) -> None:
    _draw_point(draw, shoulder, _SHOULDER_POINT_COLOR)
    _draw_point(draw, chin, _CHIN_POINT_COLOR)
    _draw_point(draw, wrist, _WRIST_POINT_COLOR)


def _draw_point(
    draw: ImageDraw.ImageDraw,
    point: tuple[int, int],
    color: str,
    *,
    radius: int = _POINT_RADIUS,
) -> None:
    x, y = point
    draw.ellipse(
        (
            x - radius,
            y - radius,
            x + radius,
            y + radius,
        ),
        fill=color,
    )


def _draw_text_layer(draw: ImageDraw.ImageDraw, punch: PunchAnalysis) -> None:
    lines = [
        f"Punch number: {punch.punch_number}",
        f"Expected punch side: {punch.expected.side}",
        f"Expected target: {punch.expected.target}",
        f"Classification: {punch.score.classification}",
        f"Deviation degrees: {punch.score.deviation_degrees:.2f}",
        f"Direction: {punch.score.direction}",
        f"Impact frame number: {punch.impact_frame_number}",
    ]
    font = ImageFont.load_default()
    x, y = _TEXT_ORIGIN

    for index, line in enumerate(lines):
        draw.text(
            (x, y + (index * _TEXT_LINE_SPACING)),
            line,
            fill=_TEXT_COLOR,
            font=font,
        )
