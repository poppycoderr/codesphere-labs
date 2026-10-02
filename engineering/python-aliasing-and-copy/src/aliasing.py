"""名字绑定与对象别名：默认参数、嵌套列表、浅拷贝、+=、闭包、类属性。全部是确定的单线程代码。"""
import copy
import sys
from dataclasses import dataclass, field


def out(key, fact):
    print(f"{key}\t{fact}")


def add_bad(item, bucket=[]):
    bucket.append(item)
    return bucket


def add_good(item, bucket=None):
    if bucket is None:
        bucket = []
    bucket.append(item)
    return bucket


def main():
    out("env", f"python={sys.version.split()[0]}")

    # 1. 默认参数只在 def 执行时求值一次
    r = [list(add_bad(i)) for i in (1, 2, 3)]
    out("default.mutable", f"def add(item, bucket=[]) 连续调用三次，各返回 {r[0]}、{r[1]}、{r[2]}；默认值对象现在是 {add_bad.__defaults__[0]}")
    r = [add_good(i) for i in (1, 2, 3)]
    out("default.none", f"默认值改为 None、函数内新建列表：三次各返回 {r[0]}、{r[1]}、{r[2]}")

    # 2. 赋值与传参都只是多了一个名字
    a = [1, 2]
    b = a
    b.append(3)
    out("alias.assign", f"b = a 之后 b.append(3)：a 是 {a}，a is b = {a is b}")

    def mutate(x):
        x.append(99)

    def rebind(x):
        x = x + [99]
        return x

    c = [1]
    mutate(c)
    d = [1]
    rebind(d)
    out("alias.argument", f"函数里 x.append(99)：调用方的列表变成 {c}；函数里 x = x + [99]：调用方的列表仍是 {d}")

    # 3. 用乘法造二维列表
    grid = [[0] * 3] * 3
    grid[0][0] = 1
    out("alias.multiply", f"[[0] * 3] * 3 之后只改 grid[0][0]：{grid}")
    grid = [[0] * 3 for _ in range(3)]
    grid[0][0] = 1
    out("alias.comprehension", f"用列表推导式逐行新建：{grid}")
    shared = dict.fromkeys(["a", "b"], [])
    shared["a"].append(1)
    out("alias.fromkeys", f"dict.fromkeys(['a', 'b'], []) 之后只向 'a' 追加：{shared}")

    # 4. 浅拷贝只复制最外层
    orig = {"name": "v1", "tags": ["x"]}
    shallow = dict(orig)
    shallow["name"] = "v2"
    shallow["tags"].append("y")
    out("copy.shallow", f"dict(orig) 之后改副本的 name 并向副本的 tags 追加：原对象 {orig}")
    orig = {"name": "v1", "tags": ["x"]}
    deep = copy.deepcopy(orig)
    deep["tags"].append("y")
    out("copy.deep", f"copy.deepcopy 之后向副本的 tags 追加：原对象 {orig}")
    inner = [1]
    pair = [inner, inner]
    dp = copy.deepcopy(pair)
    out("copy.deep_shared", f"原对象里两个位置指向同一个列表，deepcopy 之后副本里两个位置是同一个对象 = {dp[0] is dp[1]}，与原来的不是同一个 = {dp[0] is not inner}")

    # 5. += 是原地修改还是重新绑定，取决于类型
    x = y = [1]
    x += [2]
    p = q = [1]
    p = p + [2]
    out("iadd.list", f"x = y = [1] 后 x += [2]：y 是 {y}；p = q = [1] 后 p = p + [2]：q 是 {q}")
    s = t = (1,)
    s += (2,)
    out("iadd.tuple", f"s = t = (1,) 后 s += (2,)：t 是 {t}（元组不可变，+= 创建了新对象）")
    tup = ([1],)
    try:
        tup[0] += [2]
        raised = "没有报错"
    except TypeError as e:
        raised = f"抛出 TypeError（{e}）"
    out("iadd.tuple_of_list", f"tup = ([1],) 后 tup[0] += [2]：{raised}，而 tup 已经是 {tup}")

    # 6. 闭包捕获的是变量，不是当时的值
    fs = [lambda: i for i in range(3)]
    out("closure.late", f"[lambda: i for i in range(3)] 依次调用：{[f() for f in fs]}")
    fs = [lambda i=i: i for i in range(3)]
    out("closure.bound", f"[lambda i=i: i for i in range(3)] 依次调用：{[f() for f in fs]}")

    # 7. 类属性在实例之间共享
    class Cart:
        items = []

        def add(self, x):
            self.items.append(x)

    c1, c2 = Cart(), Cart()
    c1.add("book")
    out("class.attribute", f"类属性 items = []，c1.add('book') 之后 c2.items 是 {c2.items}")
    try:
        @dataclass
        class Bad:
            items: list = []
        result = "定义成功"
    except ValueError as e:
        result = f"定义时抛出 ValueError（{str(e)[:44]}…）"
    out("dataclass.mutable_default", f"@dataclass 字段写 items: list = []：{result}")

    @dataclass
    class Good:
        items: list = field(default_factory=list)

    g1, g2 = Good(), Good()
    g1.items.append(1)
    out("dataclass.default_factory", f"field(default_factory=list)：g1.items 追加后 g2.items 是 {g2.items}")

    # 8. 相等与同一个对象
    m, n = [1, 2], [1, 2]
    out("identity", f"两个内容相同的列表：== 为 {m == n}，is 为 {m is n}")


if __name__ == "__main__":
    main()
