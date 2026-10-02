# 验证记录：名字绑定与对象别名

> 轻量记录：按 [验证标准](../../docs/verification-standard.md) 的十个部分合并为七节。

## 一、待验证结论与实际结果

CPython 3.14.8。

1. `def add(item, bucket=[])` 连续调用三次，返回 `[1]`、`[1, 2]`、`[1, 2, 3]`；默认值改为 `None` 后三次返回 `[1]`、`[2]`、`[3]`。
2. `b = a` 后 `b.append(3)`，`a` 变为 `[1, 2, 3]`；函数里 `x.append(99)` 改变调用方的列表，`x = x + [99]` 不改变。
3. `[[0] * 3] * 3` 后改 `grid[0][0]`，三行都变；列表推导式逐行新建则只变一行。`dict.fromkeys(['a', 'b'], [])` 后向 `'a'` 追加，`'b'` 也变。
4. `dict(orig)` 后向副本的嵌套列表追加，原对象的列表也变；`copy.deepcopy` 后不变；原对象内两个位置指向同一个列表时，深拷贝后的副本里这两个位置仍是同一个对象。
5. 列表 `x += [2]` 改变别名 `y`，`p = p + [2]` 不改变 `q`；元组 `s += (2,)` 不改变 `t`；`tup = ([1],)` 后 `tup[0] += [2]` 抛 `TypeError`，而 `tup` 已是 `([1, 2],)`。
6. `[lambda: i for i in range(3)]` 依次调用得 `[2, 2, 2]`；`lambda i=i: i` 得 `[0, 1, 2]`。
7. 类属性 `items = []` 在实例间共享；`@dataclass` 字段写 `items: list = []` 在定义时抛 `ValueError`；`field(default_factory=list)` 各实例独立。
8. 两个内容相同的列表 `==` 为 True，`is` 为 False。

## 二、环境

见 [`evidence/environment.txt`](evidence/environment.txt)。

## 三、执行步骤

见 `scripts/verify.sh` 与 `src/aliasing.py`。

## 四、证据

见 [`evidence/README.md`](evidence/README.md)。文件由 `make evidence` 生成，未手工修改。

## 五、误差、限制与不能推出的结论

- 这些是语言数据模型的行为，结果是确定的。
- 没有涉及小整数、短字符串的对象复用：那是 CPython 的实现细节，不应依赖，所以不在实验里。
- `deepcopy` 对自定义类、带 `__deepcopy__` 的对象、文件句柄等资源的行为没有覆盖。

## 六、复现与清理

```bash
make verify
make evidence
make clean
```

## 七、验证历史

| 日期 | 结果 | 文章是否需要更新 |
|---|---|---|
| 2026-10-02 | 首次建立，输出与预期逐行一致 | 新文章，结论取自本次证据 |
