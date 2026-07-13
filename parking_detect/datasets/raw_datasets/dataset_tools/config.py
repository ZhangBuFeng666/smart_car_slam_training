CLASSES = {
    "parking_slot": 0,
    "car": 1,
    "no_parking_sign": 2,
    "entrance_sign": 3,
    "exit_sign": 4,
    "direction_arrow": 5,
    "stop_line": 6,
    "roadblock": 7,
    "danger_sign": 8,
}

DATASET_MAP = {
    "parking_slot+car": {0: "parking_slot", 1: "car"},
    "no_parking": {0: "no_parking_sign"},
    "exit_sign": {0: "exit_sign"},
    "roadblock": {0: "roadblock"},
    "direction_arrow": {
        0: "direction_arrow",
        1: None,
        2: "direction_arrow",
        3: "direction_arrow",
    },
    "danger_sign": {0: "danger_sign", 1: None, 2: None, 3: "danger_sign"},
    "stop_line+entrance": {0: "stop_line", 1: "entrance_sign"},
}
