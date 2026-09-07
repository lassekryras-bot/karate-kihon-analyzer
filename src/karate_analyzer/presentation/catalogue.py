"""Resolve packaged wiki examples without changing exercise result selection."""
from copy import deepcopy
from typing import Any


def resolve_measurement_presentation(
    definition: dict[str, Any],
    *,
    context: str,
    current_bundle: dict[str, Any] | None = None,
    current_presentation_id: str | None = None,
    packaged_bundles: dict[str, dict[str, Any]] | None = None,
    default_wiki_example: dict[str, str] | None = None,
) -> dict[str, Any]:
    """Resolve the entire presentation and its matching motion atomically.

    Wiki overrides select both graph and motion from an installed example asset.
    They never replace an exercise's current punch. Asset IDs are local catalogue
    keys, not URLs or executable code.
    """
    if context == "exercise":
        bundle = current_bundle
        presentation_id = current_presentation_id
        source = "current_punch"
    elif context == "wiki":
        example = definition.get("wiki_example") or default_wiki_example
        if not example:
            raise ValueError("No wiki example configured")
        bundle = (packaged_bundles or {}).get(example["bundle_id"])
        presentation_id = example["presentation_id"]
        source = "packaged_example"
    else:
        raise ValueError("Context must be exercise or wiki")
    if not bundle or bundle.get("contract") != "karate_measurement_presentation_v2":
        raise ValueError("Missing or unsupported presentation bundle")
    matches = [p for p in bundle["presentations"]
               if p["presentation_id"] == presentation_id
               and p["measurement_id"] == definition["measurement_id"]]
    if len(matches) != 1:
        raise ValueError("Expected one matching measurement presentation")
    presentation = matches[0]
    motion = bundle.get("motions", {}).get(presentation["motion_id"])
    if motion is None:
        raise ValueError("Presentation references missing motion")
    return deepcopy({"context": context, "source": source,
                     "frame_geometry": bundle.get("frame_geometry"),
                     "presentation": presentation, "motion": motion})
