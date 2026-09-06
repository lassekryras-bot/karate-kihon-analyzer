import pytest

from karate_analyzer.strike_detection.theoretical_impact import (
    ConfidenceLevel,
    EventPhase,
    PunchMotionSample,
    SelectedEventFrame,
    TheoreticalImpactConfig,
    TheoreticalImpactEvent,
    estimate_theoretical_impact,
    select_analysis_frame,
    select_snapshot_frame,
)


CFG = TheoreticalImpactConfig(smoothing_window_samples=1, confirmation_interval_ms=50)


def samples(progress, times=None, visibility=None):
    times = times or [i * 50 for i in range(len(progress))]
    visibility = visibility or [0.9] * len(progress)
    return [PunchMotionSample(i, times[i], value, minimum_landmark_visibility=visibility[i]) for i, value in enumerate(progress)]


def test_first_confirmed_plateau_is_invariant_to_hold_length():
    short, _ = estimate_theoretical_impact(samples([0, .4, .8, 1, 1, 1]), CFG)
    long, _ = estimate_theoretical_impact(samples([0, .4, .8, 1, 1, 1] + [1] * 10), CFG)
    assert short.impact_frame_number == long.impact_frame_number == 4
    assert short.theoretical_impact_time_ms == long.theoretical_impact_time_ms == 200


def test_immediate_reversal_selects_turning_sample_and_separates_velocity_peak():
    event, _ = estimate_theoretical_impact(samples([0, .5, 1, .7, .3]), CFG)
    assert event.impact_frame_number == 2
    assert event.peak_forward_velocity_frame_number == 1
    assert event.retraction_start_ms == 150
    assert "immediate_reversal" in event.evidence_flags


def test_irregular_timestamps_are_used_for_explicit_progress_derivative():
    _event, enriched = estimate_theoretical_impact(
        samples([0, .5, 1, 1, 1], [0, 100, 300, 400, 500]), CFG
    )
    assert enriched[1].normalized_progress_per_second == pytest.approx(5)
    assert enriched[2].normalized_progress_per_second == pytest.approx(2.5)


def test_duplicate_or_non_monotonic_timestamps_fail_clearly():
    with pytest.raises(ValueError, match="timestamps must be strictly increasing"):
        estimate_theoretical_impact(samples([0, 1, 1, 1], [0, 50, 50, 100]), CFG)


def test_missing_quality_lowers_confidence_without_moving_arrival():
    baseline = samples([0, .4, .8, 1, 1, 1])
    degraded = baseline.copy()
    degraded[1] = PunchMotionSample(1, 50, .4, is_interpolated=True, quality_flags=("interpolated",))
    event, _ = estimate_theoretical_impact(degraded, CFG)
    assert event.impact_frame_number == 4
    assert event.confidence_level is ConfidenceLevel.MEDIUM


def test_analysis_window_phase_guard_and_snapshot_provenance():
    event, enriched = estimate_theoretical_impact(
        samples([0, .4, .8, 1, 1, 1], visibility=[.9, .9, .9, .5, .9, 1]), CFG
    )
    analysis = select_analysis_frame(event, enriched, CFG)
    assert analysis is not None
    assert abs(analysis.offset_from_impact_ms) <= 75
    assert analysis.phase in {EventPhase.IMPACT, EventPhase.TERMINAL_TRANSITION}
    snapshot = select_snapshot_frame(analysis)
    assert snapshot is not None
    assert snapshot.reason == "same_as_analysis_v1"
    assert event.impact_frame_number == 4


def test_contracts_round_trip_and_reject_invalid_ordering():
    event, _ = estimate_theoretical_impact(samples([0, .4, .8, 1, 1, 1]), CFG)
    assert TheoreticalImpactEvent.from_dict(event.to_dict()) == event
    frame = SelectedEventFrame(4, 200, 0, EventPhase.IMPACT, "test")
    assert SelectedEventFrame.from_dict(frame.to_dict()) == frame
    with pytest.raises(ValueError, match="out of order"):
        TheoreticalImpactEvent(100, 2, "decoded_sample_arrival_v1", ConfidenceLevel.LOW, (), None, None, None, None, 200, 100)
