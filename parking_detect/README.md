# parking_detect

停车场场景 9 类目标检测（YOLOv5）。本目录为训练/离线推理代码与配置。

## 内容

- `train.py` / `val.py` / `detect.py`：训练、验证、推理入口
- `datasets/parking_dataset/data.yaml`：9 类定义（勿改 id 顺序）
- `datasets/raw_datasets/dataset_tools/`：数据合并与检查脚本
- `runs/train/parking_base/opt.yaml`、`hyp.yaml`、`results.csv`：本次训练参数与曲线记录
- 权重 `best.pt` 体积较大，不在本仓库；需要时组内另传

## 类别

| id | name |
|----|------|
| 0 | parking_slot |
| 1 | car |
| 2 | no_parking_sign |
| 3 | entrance_sign |
| 4 | exit_sign |
| 5 | direction_arrow |
| 6 | stop_line |
| 7 | roadblock |
| 8 | danger_sign |

## 训练（需自备数据集 images/labels）

在本目录执行：

1. `pip install -r requirements.txt`
2. `python train.py --data datasets/parking_dataset/data.yaml --weights yolov5s.pt --img 640 --batch 8 --epochs 120 --device 0 --name parking_base`

本次实验记录见 `runs/train/parking_base/opt.yaml`（batch=8, imgsz=640 等）。

## 推理（有 best.pt 时）

`python detect.py --weights runs/train/parking_base/weights/best.pt --source path/to/images --img 640 --conf 0.25 --iou 0.45`

## 与仓库其它模块

- 本目录：模型训练与配置
- `jetson_server`：小车 HTTP 控制（运行时服务，勿与训练代码混放）