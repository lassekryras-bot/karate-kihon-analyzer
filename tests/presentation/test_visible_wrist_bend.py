import math
import pytest
from karate_analyzer.diagnostics.wrist_alignment import visible_wrist_bend_degrees


def test_straight_and_camera_plane_bends():
    assert visible_wrist_bend_degrees((0,0),(10,0),(20,0)) == 0
    assert visible_wrist_bend_degrees((0,0),(10,0),(20,10)) == pytest.approx(45)
    assert visible_wrist_bend_degrees((0,0),(10,0),(20,-10)) == pytest.approx(-45)


def test_translation_scale_and_mirroring():
    assert visible_wrist_bend_degrees((50,50),(70,50),(90,70)) == pytest.approx(45)
    assert visible_wrist_bend_degrees((0,0),(-10,0),(-20,10)) == pytest.approx(-45)


@pytest.mark.parametrize('points',[
    ((0,0),(0,0),(1,1)),((0,0),(1,1),(1,1)),
    ((0,0),(math.nan,1),(2,2)),((0,0),(1,1),(2,math.inf)),
])
def test_degenerate_geometry_is_unavailable(points):
    assert visible_wrist_bend_degrees(*points) is None
