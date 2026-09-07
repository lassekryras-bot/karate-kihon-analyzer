"""Camera-plane wrist alignment candidates; no anatomical or coaching verdict."""
import math
from collections.abc import Sequence


def visible_wrist_bend_degrees(
    elbow: Sequence[float], hand_wrist: Sequence[float], middle_knuckle: Sequence[float],
) -> float | None:
    """Signed turn from elbow->hand wrist to hand wrist->middle MCP.

    All three points must be in the same image-pixel coordinate frame. Zero is
    a straight continuation; positive is clockwise in image-down coordinates.
    This does not distinguish flexion/extension from sideways deviation or
    forearm rotation. Missing/non-finite or degenerate geometry is unavailable.
    """
    if any(len(p) != 2 or not all(math.isfinite(v) for v in p)
           for p in (elbow, hand_wrist, middle_knuckle)):
        return None
    a = [hand_wrist[i]-elbow[i] for i in (0, 1)]
    b = [middle_knuckle[i]-hand_wrist[i] for i in (0, 1)]
    if min(math.hypot(*a), math.hypot(*b)) <= 1e-9:
        return None
    return math.degrees(math.atan2(a[0]*b[1]-a[1]*b[0], sum(x*y for x,y in zip(a,b))))
