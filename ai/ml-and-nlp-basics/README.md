# 机器学习与 NLP 基础

对应文章：[python-and-ml-basics.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ai/python-and-ml-basics.md)、[ml-and-nlp-basics.md](https://github.com/poppycoderr/codesphere/blob/master/docs/ai/ml-and-nlp-basics.md)。

在固定 digest 的 `python:3.14.7-slim` 容器里安装 `requirements.txt`（精确锁定版本）后运行：

1. `src/ml.py`：鸢尾花数据集，`random_state=42` 分层切出 30 条测试集；Pipeline + `GridSearchCV` 选 k；逐个 k 的训练、测试、5 折交叉验证准确率；k=1 在 20 种随机划分下的测试准确率；把花萼宽度放大 1000 倍后有无标准化的对比；k=5 的混淆矩阵与精确率、召回率；四个模型在全部 150 条数据上的 5 折交叉验证；
2. `src/nlp.py`：三条分好词的工单的词袋矩阵与 TF-IDF 权重；四个词的一次缩放点积注意力（`numpy.random.default_rng(7)`）。

## 快速运行

```bash
make verify     # 需要 Docker；约 30 秒（首次需要下载依赖）
make evidence
make clean
```
