"""最小 CPU 的几个契约，每个场景输出一行「键<TAB>事实」。"""
import sys
sys.path.insert(0, "src")
from cpu import Contract, LatchedCPU, NaiveCPU, assemble, run, step, State, LDA, STA, ADD, HLT, XCH


def out(key, text):
    print(f"{key}\t{text}")


X, Y, R, ONE, ZERO = 0x80, 0x81, 0x82, 0x83, 0x84


def multiply(x, y, lda_flags=True, fixed=False):
    test = ["LDA 0x81", "ADD 0x84"] if fixed else ["LDA 0x81"]
    prog = test + ["JZ 0x40", "SUB 0x83", "STA 0x81", "LDA 0x82", "ADD 0x80", "STA 0x82", "JMP 0x00"]
    mem = assemble(prog)
    done = assemble(["LDA 0x82", "OUT", "HLT"])
    mem[0x40:0x46] = done[0:6]
    mem[X], mem[Y], mem[ONE], mem[ZERO] = x, y, 1, 0
    s, n, end = run(mem, Contract(lda_sets_flags=lda_flags))
    return s, n, end


# 一、取指、译码、执行：6 × 7
s, n, end = multiply(6, 7)
out("mul", f"循环加法算 6 × 7：输出 {list(s.out)}，{n} 步后{end}")
s, n, end = multiply(6, 0)
out("mul.zero", f"算 6 × 0：输出 {list(s.out)}，{n} 步后{end}")

# 二、同一个沿：交换指令
mem = [0] * 256
mem[X] = 9
naive = NaiveCPU(mem)
naive.acc = 5
naive.xch(X)
s0 = State(mem=tuple(assemble(["XCH 0x80", "HLT"])[:0x80]) + (9,) + (0,) * 127, acc=5)
s1 = step(s0, Contract())
out("xch", f"ACC=5、M[0x80]=9 执行 XCH：逐句赋值得到 ACC={naive.acc}、M={naive.mem[X]}；由旧状态算新状态得到 ACC={s1.acc}、M={s1.mem[X]}")

# 三、标志位契约：同一段程序
s, n, end = multiply(6, 7, lda_flags=False)
out("flags.lda", f"同一段乘法程序，约定改为 LDA 不更新 Z：输出 {list(s.out)}，{n} 步后{end}（JZ 读到的是上一次 ADD 留下的 Z，结果累加到 256 回绕为 0 时才退出）")
a, na, _ = multiply(6, 7, lda_flags=True, fixed=True)
b, nb, _ = multiply(6, 7, lda_flags=False, fixed=True)
out("flags.fixed", f"在 JZ 前补一条 ADD 0（显式设置标志）：LDA 更新 Z 时输出 {list(a.out)}（{na} 步），不更新时输出 {list(b.out)}（{nb} 步）")
mem = assemble(["LDA 0x80", "ADD 0x81", "JC 0x10", "OUT", "HLT"])
mem[0x10:0x14] = assemble(["OUT", "HLT"])[0:4]
mem[X], mem[Y] = 200, 100
s, n, end = run(mem)
out("flags.carry", f"200 + 100：ACC={s.acc}，C={int(s.c)}，跳到了进位分支（8 位累加器只保留低 8 位）")

# 四、HLT 与挂起的写
prog = assemble(["LDA 0x80", "ADD 0x81", "STA 0x82", "HLT"])
prog[X], prog[Y] = 40, 2
drop = LatchedCPU(prog, halt_drains=False).run()
drain = LatchedCPU(prog, halt_drains=True).run()
out("halt", f"STA 之后紧跟 HLT，写回延后一拍：HLT 立即停机时 M[0x82]={drop.mem[R]}，先提交挂起的写再停机时 M[0x82]={drain.mem[R]}")

# 五、非法操作码与漏写 HLT
for fill, label in ((0x00, "空白内存为 0"), (0xFF, "空白内存为 0xFF")):
    for policy in ("fault", "nop"):
        mem = [fill] * 256
        mem[0:4] = assemble(["LDA 0x80", "OUT"])[0:4]
        mem[X] = 7
        s, n, end = run(mem, Contract(illegal=policy), limit=1000)
        out(f"illegal.{fill:02x}.{policy}", f"漏写 HLT，{label}，非法操作码按 {policy} 处理：{n} 步后{end}，OUT {len(s.out)} 次")
