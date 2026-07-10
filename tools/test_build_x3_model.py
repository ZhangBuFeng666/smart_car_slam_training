import math
from pathlib import Path
import unittest

import numpy as np

from tools import build_x3_model


class BuildX3ModelTest(unittest.TestCase):
    def test_ros_z_axis_becomes_three_y_axis(self):
        point = np.array([0.0, 0.0, 1.0, 1.0])

        transformed = build_x3_model.ROS_TO_THREE @ point

        np.testing.assert_allclose(transformed[:3], [0.0, 1.0, 0.0], atol=1e-6)

    def test_wheel_origins_match_the_rosmaster_urdf(self):
        self.assertEqual(
            build_x3_model.WHEEL_ORIGINS["front_left"],
            (0.08, 0.0745, -0.0325),
        )
        self.assertEqual(
            build_x3_model.WHEEL_ORIGINS["back_right"],
            (-0.08, -0.0745, -0.0325),
        )

    def test_wheel_visual_rotation_is_ninety_degrees_about_x(self):
        rotation = build_x3_model.wheel_visual_transform()

        self.assertAlmostEqual(rotation[1, 2], -math.sin(math.pi / 2), places=6)
        self.assertAlmostEqual(rotation[2, 1], math.sin(math.pi / 2), places=6)

    def test_assembled_height_matches_the_x3_urdf(self):
        source_dir = Path("build/x3-model-source")
        if not source_dir.is_dir():
            self.skipTest("Downloaded source meshes are not available")

        parts = build_x3_model.assemble_robot(source_dir)
        vertices = np.vstack([mesh.vertices for mesh in parts.values()])
        height = vertices[:, 1].max() - vertices[:, 1].min()

        self.assertGreater(height, 0.25)
        self.assertLess(height, 0.27)

    def test_astra_camera_sits_on_the_front_bracket(self):
        source_dir = Path("build/x3-model-source")
        if not source_dir.is_dir():
            self.skipTest("Downloaded source meshes are not available")

        parts = build_x3_model.assemble_robot(source_dir)
        body_bounds = parts["x3_body"].bounds
        camera_bounds = parts["astra_camera"].bounds
        camera_center_x = camera_bounds[:, 0].mean()

        self.assertLess(camera_center_x, -0.08)
        self.assertLess(camera_bounds[0, 1], body_bounds[1, 1])
        self.assertLessEqual(camera_bounds[1, 1], body_bounds[1, 1])


if __name__ == "__main__":
    unittest.main()
