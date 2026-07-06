"""Deterministic PNG snapshot rendering for completed punch analyses."""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from karate_analyzer.angle_analyzer import Point2D
from karate_analyzer.session_analyzer import PunchAnalysis

_BACKGROUND_COLOR = "white"
_IDEAL_LINE_COLOR = "green"
_ACTUAL_LINE_COLOR = "red"
_SHOULDER_POINT_COLOR = "blue"
_CHIN_POINT_COLOR = "purple"
_WRIST_POINT_COLOR = "black"
_TEXT_COLOR = "black"

_LOGICAL_X_MIN = -0.2
_LOGICAL_X_MAX = 1.2
_LOGICAL_Y_MIN = -0.6
_LOGICAL_Y_MAX = 0.6
_MARGIN_RATIO = 0.1
_MIN_MARGIN = 20
_POINT_RADIUS = 5
_LINE_WIDTH = 3
_TEXT_ORIGIN = (16, 16)
_TEXT_LINE_SPACING = 16


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
) -> None:
    x, y = point
    draw.ellipse(
        (
            x - _POINT_RADIUS,
            y - _POINT_RADIUS,
            x + _POINT_RADIUS,
            y + _POINT_RADIUS,
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
