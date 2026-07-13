from dataclasses import dataclass
from typing import Dict, Optional, Tuple


@dataclass(frozen=True)
class ClassMapping:
    event_type: str
    label: str
    severity: str


CLASS_MAPPINGS: Dict[str, ClassMapping] = {
    "standing_water": ClassMapping("standing_water", "积水", "HIGH"),
    "wet_surface": ClassMapping("standing_water", "湿滑路面", "HIGH"),
    "pothole": ClassMapping("pavement_defect", "道面坑洞", "MEDIUM"),
    "pavement_crack": ClassMapping("pavement_defect", "道面裂缝", "MEDIUM"),
    "foreign_object": ClassMapping("obstacle", "道面异物", "MEDIUM"),
    "debris": ClassMapping("obstacle", "散落物", "MEDIUM"),
    "paper_box": ClassMapping("obstacle", "纸箱", "MEDIUM"),
    "illegal_parking": ClassMapping("illegal_parking", "禁停车辆", "MEDIUM"),
    "no_parking_sign": ClassMapping("illegal_parking", "禁停标志", "MEDIUM"),
    "person": ClassMapping("obstacle", "行人", "MEDIUM"),
    "smoke": ClassMapping("obstacle", "烟雾", "MEDIUM"),
    "parking_slot": ClassMapping("parking_slot", "车位", "LOW"),
    "occupied_slot": ClassMapping("parking_slot", "占用车位", "LOW"),
}


def map_class(class_name: str) -> Optional[ClassMapping]:
    return CLASS_MAPPINGS.get(class_name.casefold())
