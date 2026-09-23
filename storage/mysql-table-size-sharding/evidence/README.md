# 证据说明

全部由 `make evidence`（即 `scripts/verify.sh evidence`）生成。

| 文件 | 内容 |
|---|---|
| `evidence/btree.tsv` | 四张表的行数、平均行长、根页页号、树高、根页记录数、叶子页数、每页行数与文件大小 |
| `evidence/point-lookups.tsv` | 冷、热两轮点查的平均耗时与物理读次数 |
| `evidence/ddl.tsv、ddl-inplace-rejected.txt` | 三类 DDL 与 `COUNT(*)` 的耗时，`INPLACE` 改列类型的报错 |
| `evidence/assertions.txt` | 断言与推算结果 |
| `environment.txt` | 操作系统、CPU 数、Docker 版本与组件版本 |

规范化：无：输出为原始内容。
