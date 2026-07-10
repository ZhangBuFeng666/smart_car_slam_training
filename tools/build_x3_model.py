from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import numpy as np
import trimesh
from trimesh.transformations import rotation_matrix, translation_matrix
from trimesh.visual.material import PBRMaterial


ROS_TO_THREE = rotation_matrix(-math.pi / 2, [1.0, 0.0, 0.0])

WHEEL_ORIGINS = {
    "front_left": (0.08, 0.0745, -0.0325),
    "front_right": (0.08, -0.0745, -0.0325),
    "back_left": (-0.08, 0.0745, -0.0325),
    "back_right": (-0.08, -0.0745, -0.0325),
}

CAMERA_ORIGIN = (-0.135, 0.0, 0.160)

SOURCE_FILES = {
    "x3_body": "base_link_X3.STL",
    "wheel_front_left": "front_left_wheel_X3.STL",
    "wheel_front_right": "front_right_wheel_X3.STL",
    "wheel_back_left": "back_left_wheel_X3.STL",
    "wheel_back_right": "back_right_wheel_X3.STL",
    "astra_camera": "camera_link.STL",
    "rplidar": "rplidar_s2.stl",
}


def wheel_visual_transform() -> np.ndarray:
    return rotation_matrix(math.pi / 2, [1.0, 0.0, 0.0])


def _material(name: str, color: tuple[int, int, int], metallic: float, roughness: float) -> PBRMaterial:
    return PBRMaterial(
        name=name,
        baseColorFactor=[*color, 255],
        metallicFactor=metallic,
        roughnessFactor=roughness,
    )


MATERIALS = {
    "x3_body": _material("Obsidian chassis", (51, 58, 57), 0.62, 0.28),
    "wheel": _material("Mecanum wheels", (20, 23, 22), 0.18, 0.62),
    "astra_camera": _material("Astra camera", (106, 111, 108), 0.52, 0.3),
    "rplidar": _material("RPLIDAR", (35, 39, 38), 0.35, 0.38),
}


def _load_mesh(path: Path) -> trimesh.Trimesh:
    mesh = trimesh.load_mesh(path, process=True)
    if not isinstance(mesh, trimesh.Trimesh):
        raise ValueError(f"Expected one mesh in {path}")
    mesh.remove_unreferenced_vertices()
    mesh.fix_normals()
    return mesh


def _part_transform(name: str) -> np.ndarray:
    if name.startswith("wheel_"):
        side = name.removeprefix("wheel_")
        return translation_matrix(WHEEL_ORIGINS[side]) @ wheel_visual_transform()
    if name == "astra_camera":
        # The mesh origin sits inside the camera body and overlaps the top of the X3 mast.
        return translation_matrix(CAMERA_ORIGIN)
    if name == "rplidar":
        scale = np.diag([0.001, 0.001, 0.001, 1.0])
        mesh_rotation = rotation_matrix(-math.pi / 2, [1.0, 0.0, 0.0])
        return translation_matrix((0.0, 0.0, 0.0825)) @ mesh_rotation @ scale
    return np.eye(4)


def _part_material(name: str) -> PBRMaterial:
    if name.startswith("wheel_"):
        return MATERIALS["wheel"]
    return MATERIALS[name]


def assemble_robot(source_dir: Path) -> dict[str, trimesh.Trimesh]:
    missing = [filename for filename in SOURCE_FILES.values() if not (source_dir / filename).is_file()]
    if missing:
        raise FileNotFoundError(f"Missing X3 source meshes: {', '.join(missing)}")

    parts: dict[str, trimesh.Trimesh] = {}
    for name, filename in SOURCE_FILES.items():
        mesh = _load_mesh(source_dir / filename)
        mesh.apply_transform(ROS_TO_THREE @ _part_transform(name))
        mesh.visual.material = _part_material(name)
        parts[name] = mesh

    vertices = np.vstack([mesh.vertices for mesh in parts.values()])
    minimum = vertices.min(axis=0)
    maximum = vertices.max(axis=0)
    center_offset = np.array(
        [-(minimum[0] + maximum[0]) / 2, -minimum[1], -(minimum[2] + maximum[2]) / 2]
    )
    center_transform = translation_matrix(center_offset)
    for mesh in parts.values():
        mesh.apply_transform(center_transform)
    return parts


def export_glb(source_dir: Path, output_path: Path) -> dict[str, object]:
    parts = assemble_robot(source_dir)
    scene = trimesh.Scene()
    for name, mesh in parts.items():
        scene.add_geometry(mesh, node_name=name, geom_name=name)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(scene.export(file_type="glb"))

    exported = trimesh.load(output_path, force="scene")
    bounds = np.asarray(exported.bounds)
    if not np.isfinite(bounds).all() or len(exported.geometry) != len(SOURCE_FILES):
        raise ValueError("Exported GLB failed geometry validation")

    extents = bounds[1] - bounds[0]
    if extents[1] <= 0.25 or extents[1] >= 0.27:
        raise ValueError(f"Unexpected X3 height after assembly: {extents[1]:.4f} m")

    return {
        "output": str(output_path),
        "bytes": output_path.stat().st_size,
        "parts": sorted(exported.geometry.keys()),
        "bounds_m": bounds.round(6).tolist(),
        "extents_m": extents.round(6).tolist(),
        "triangles": int(sum(len(mesh.faces) for mesh in exported.geometry.values())),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Assemble the ROSMASTER X3 visual meshes into one GLB")
    parser.add_argument("--source-dir", type=Path, default=Path("build/x3-model-source"))
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("app/src/main/assets/vehicle_stage/x3.glb"),
    )
    args = parser.parse_args()
    print(json.dumps(export_glb(args.source_dir, args.output), indent=2))


if __name__ == "__main__":
    main()
