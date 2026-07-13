from pathlib import Path
from collections import Counter


labels=Path("../merged/labels")


count=Counter()


for txt in labels.glob("*.txt"):

    for line in open(txt):

        cls=int(line.split()[0])

        count[cls]+=1



names=[
"parking_slot",
"car",
"no_parking_area",
"entrance",
"exit",
"direction_arrow",
"stop_line",
"roadblock",
"danger_sign"
]


for i,n in enumerate(names):

    print(
        n,
        ":",
        count[i]
    )