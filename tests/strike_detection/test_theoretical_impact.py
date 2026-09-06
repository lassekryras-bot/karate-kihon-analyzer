import pytest

from karate_analyzer.strike_detection.theoretical_impact import (
    ConfidenceLevel, EventPhase, PhysicalContactStatus, PunchMotionSample,
    SelectedEventFrame, TheoreticalImpactConfig, TheoreticalImpactEvent,
    build_motion_samples, estimate_theoretical_impact, select_analysis_frame,
    select_snapshot_frame,
)

CFG = TheoreticalImpactConfig(smoothing_window_samples=1, confirmation_interval_ms=50)


def samples(reach, times=None, visibility=None, *, angles=None, ratios=None):
    times = times or [i * 50 for i in range(len(reach))]
    visibility = visibility or [.9] * len(reach)
    maximum = max(reach) or 1
    ratios = ratios or [.65 + .35 * value / maximum for value in reach]
    angles = angles or [110 + 65 * value / maximum for value in reach]
    return [PunchMotionSample(i, times[i], value, angles[i], ratios[i], visibility[i]) for i, value in enumerate(reach)]


def estimate(values, candidate, **kwargs):
    return estimate_theoretical_impact(samples(values, **kwargs), CFG, candidate_peak_frame_number=candidate)


def test_clean_outward_acceleration_selects_first_terminal_arrival():
    event, enriched = estimate([0, .2, .6, 1, 1, 1], 3)
    assert event.impact_frame_number == 3
    assert event.peak_forward_velocity_frame_number == 3
    assert enriched[4].normalized_progress_per_second == 0


def test_first_arrival_is_invariant_to_terminal_hold_length():
    short, _ = estimate([0, .4, .8, 1, 1, 1], 3)
    long, _ = estimate([0, .4, .8, 1, 1, 1] + [1] * 10, 3)
    assert short.impact_frame_number == long.impact_frame_number == 3


def test_immediate_reversal_selects_maximum_reach_not_negative_frame():
    event, enriched = estimate([0, .5, 1, .7, .3], 2)
    assert event.impact_frame_number == 2
    assert enriched[3].normalized_progress_per_second < 0
    assert event.retraction_start_ms == 150
    assert "immediate_reversal" in event.evidence_flags


def test_irregular_timestamps_drive_derivative():
    event, enriched = estimate([0, .5, 1, 1, 1], 3, times=[0, 100, 250, 300, 400])
    assert enriched[2].normalized_progress_per_second == pytest.approx(0.5/.15)
    assert event.measurement_window_start_ms == 100


def test_mirrored_geometry_has_identical_outward_velocity_meaning():
    def frames(sign):
        result=[]
        for i, x in enumerate((.2, .5, .8, .8, .6)):
            result.append({"frame_number": i, "timestamp_ms": i*50, "poses": [[
                {"index": 11,"x":.5,"y":.5,"visibility":.9}, {"index":12,"x":.6,"y":.5,"visibility":.9},
                {"index":13,"x":.5+sign*x/2,"y":.5,"visibility":.9}, {"index":15,"x":.5+sign*x,"y":.5,"visibility":.9},
            ]]})
        return result
    left=build_motion_samples(frames(1),"left",analysis_width=1000,analysis_height=500)
    mirrored=build_motion_samples(frames(-1),"left",analysis_width=1000,analysis_height=500)
    _,left=estimate_theoretical_impact(left,CFG,candidate_peak_frame_number=2)
    _,mirrored=estimate_theoretical_impact(mirrored,CFG,candidate_peak_frame_number=2)
    assert [s.normalized_progress_per_second for s in left] == pytest.approx([s.normalized_progress_per_second for s in mirrored])
    assert left[2].normalized_progress_per_second > 0 and left[4].normalized_progress_per_second < 0


def test_noisy_near_zero_hold_keeps_first_arrival():
    event, _ = estimate([0,.4,.8,1,.999,1.001,1,.7],3)
    assert event.impact_frame_number == 3


def test_missing_elbow_or_low_visibility_makes_event_unavailable():
    data=samples([0,.4,.8,1,1])
    data[3]=PunchMotionSample(3,150,1,None,1,.9,required_landmarks_available=False,is_missing=True)
    data[4]=PunchMotionSample(4,200,1,175,1,.2)
    event,_=estimate_theoretical_impact(data,CFG,candidate_peak_frame_number=3)
    assert event.impact_frame_number is None
    assert event.unavailable_reason
    assert event.physical_contact_status is PhysicalContactStatus.NOT_ASSESSED


@pytest.mark.parametrize("late_reach,late_angle,late_ratio", [(.72,139,.72),(.75,142,.75),(.55,121,.60)])
def test_regression_late_retraction_geometry_cannot_win(late_reach,late_angle,late_ratio):
    data=samples([0,.35,.8,1,.98,late_reach], angles=[110,130,160,178,170,late_angle], ratios=[.6,.7,.9,1,.95,late_ratio])
    event,enriched=estimate_theoretical_impact(data,CFG,candidate_peak_frame_number=3)
    assert event.impact_frame_number == 3
    assert enriched[5].normalized_progress_per_second < 0
    assert event.impact_frame_number != 5


def test_candidate_window_excludes_later_motion_contamination():
    data=samples([0,.4,.8,1,1,.7,.4,.8,1.2,1.2])
    event,_=estimate_theoretical_impact(data,CFG,candidate_peak_frame_number=3)
    assert event.impact_frame_number == 3
    assert event.measurement_window_end_ms == 250


def test_analysis_quality_fallback_is_bounded_and_not_retraction():
    event,enriched=estimate([0,.4,.8,1,1,.6],3,visibility=[.9,.9,.9,.5,.9,1])
    analysis=select_analysis_frame(event,enriched,CFG)
    assert analysis is not None
    assert abs(analysis.offset_from_impact_ms) <= CFG.analysis_frame_window_ms
    assert analysis.frame_number != 5
    assert analysis.phase in {EventPhase.IMPACT,EventPhase.TERMINAL_TRANSITION}
    assert select_snapshot_frame(analysis).reason == "same_as_analysis_v1"


def test_unavailable_round_trip_serializes_reason_window_and_provenance():
    event,_=estimate_theoretical_impact(samples([0,1,1])[:2],CFG,candidate_peak_frame_number=1)
    payload=event.to_dict()
    assert payload["impact_frame_number"] is None and payload["theoretical_impact_time_ms"] is None
    assert payload["unavailable_reason"] == "insufficient_valid_samples"
    assert payload["signal_version"] == "analysis_pixel_shoulder_wrist_reach_v2"
    assert payload["scale_strategy"] == "pre_normalized_stable_scale"
    assert TheoreticalImpactEvent.from_dict(payload) == event


def test_contract_validation_and_coordinate_aspect_ratio():
    with pytest.raises(ValueError,match="timestamps must be strictly increasing"):
        estimate_theoretical_impact(samples([0,1,1,1],times=[0,50,50,100]),CFG,candidate_peak_frame_number=1)
    frame={"frame_number":0,"timestamp_ms":0,"poses":[[
        {"index":11,"x":0,"y":0,"visibility":.9},{"index":13,"x":.5,"y":.5,"visibility":.9},
        {"index":15,"x":1,"y":0,"visibility":.9},{"index":12,"x":.2,"y":0,"visibility":.9}]]}
    [square]=build_motion_samples([frame],"left")
    [wide]=build_motion_samples([frame],"left",analysis_width=1600,analysis_height=900)
    assert square.elbow_angle_degrees_2d == pytest.approx(90)
    assert wide.elbow_angle_degrees_2d == pytest.approx(121.28,abs=.01)
    with pytest.raises(ValueError,match="analysis dimensions"):
        build_motion_samples([],"left",analysis_width=0)


def test_selected_frame_contract_round_trip():
    frame=SelectedEventFrame(4,200,0,EventPhase.IMPACT,"test")
    assert SelectedEventFrame.from_dict(frame.to_dict()) == frame
