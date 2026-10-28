"""最小的累加器 CPU：8 位累加器 ACC、8 位程序计数器 PC、零标志 Z 与进位标志 C、256 字节内存。

每条指令两个字节：操作码、地址。每一步是一个时钟沿：先由旧状态算出下一状态，再一次性提交。
"""
from dataclasses import dataclass, field, replace

HLT, LDA, ADD, SUB, STA, JMP, JZ, JC, XCH, OUT = range(10)
NAMES = {HLT: "HLT", LDA: "LDA", ADD: "ADD", SUB: "SUB", STA: "STA", JMP: "JMP", JZ: "JZ", JC: "JC", XCH: "XCH", OUT: "OUT"}
OPCODES = {v: k for k, v in NAMES.items()}


class Fault(Exception):
    """非法操作码：停在这条指令上，PC 指向它。"""


@dataclass(frozen=True)
class State:
    mem: tuple
    pc: int = 0
    acc: int = 0
    z: bool = False
    c: bool = False
    halted: bool = False
    out: tuple = ()


@dataclass
class Contract:
    """指令集里容易各说各话的几条约定。"""
    lda_sets_flags: bool = True        # LDA 是否按读入的值更新 Z
    illegal: str = "fault"             # 非法操作码：fault 停机报错，nop 跳过


def assemble(lines, size=256):
    """把 ["LDA 0x80", "HLT", ...] 组装成内存；数据用 (地址, 值) 另行写入。"""
    mem = [0] * size
    for i, line in enumerate(lines):
        op, *arg = line.split()
        mem[2 * i] = OPCODES[op]
        mem[2 * i + 1] = int(arg[0], 0) if arg else 0
    return mem


def step(s: State, k: Contract) -> State:
    """一个时钟沿：只读 s，返回下一状态；所有寄存器与内存都基于沿前的旧值计算。"""
    op, a = s.mem[s.pc], s.mem[(s.pc + 1) & 0xFF]
    pc = (s.pc + 2) & 0xFF
    if op == HLT:
        return replace(s, halted=True)
    if op not in NAMES:
        if k.illegal == "nop":
            return replace(s, pc=pc)
        raise Fault(f"非法操作码 0x{op:02X}，地址 0x{s.pc:02X}")
    mem = s.mem
    if op == LDA:
        v = s.mem[a]
        return replace(s, pc=pc, acc=v, z=(v == 0) if k.lda_sets_flags else s.z)
    if op == ADD:
        r = s.acc + s.mem[a]
        return replace(s, pc=pc, acc=r & 0xFF, z=(r & 0xFF) == 0, c=r > 0xFF)
    if op == SUB:
        r = s.acc - s.mem[a]
        return replace(s, pc=pc, acc=r & 0xFF, z=(r & 0xFF) == 0, c=r < 0)
    if op == STA:
        mem = s.mem[:a] + (s.acc,) + s.mem[a + 1:]
        return replace(s, pc=pc, mem=mem)
    if op == XCH:                       # 同一个沿上 ACC ← M[a]、M[a] ← ACC
        mem = s.mem[:a] + (s.acc,) + s.mem[a + 1:]
        return replace(s, pc=pc, mem=mem, acc=s.mem[a])
    if op == JMP:
        return replace(s, pc=a)
    if op == JZ:
        return replace(s, pc=a if s.z else pc)
    if op == JC:
        return replace(s, pc=a if s.c else pc)
    if op == OUT:
        return replace(s, pc=pc, out=s.out + (s.acc,))
    raise AssertionError(op)


def run(mem, k=None, limit=10_000):
    """运行到停机或步数上限；返回 (最终状态, 步数, 结局)。"""
    k = k or Contract()
    s = State(mem=tuple(mem))
    for n in range(limit):
        if s.halted:
            return s, n, "停机"
        try:
            s = step(s, k)
        except Fault as e:
            return s, n, str(e)
    return s, limit, "达到步数上限"


class NaiveCPU:
    """逐句给寄存器赋值的写法：后面的语句读到的是前面刚改过的新值。"""

    def __init__(self, mem):
        self.mem, self.pc, self.acc = list(mem), 0, 0

    def xch(self, a):
        self.acc = self.mem[a]
        self.mem[a] = self.acc


class LatchedCPU:
    """写回延后一拍的机器：STA 在下一个沿才写进内存。halt_drains 决定 HLT 是否先提交挂起的写。"""

    def __init__(self, mem, halt_drains):
        self.mem, self.pc, self.acc = list(mem), 0, 0
        self.pending = None
        self.halt_drains = halt_drains
        self.halted = False

    def tick(self):
        op, a = self.mem[self.pc], self.mem[self.pc + 1]
        if op == HLT:
            if self.halt_drains and self.pending:
                self._commit()
            self.halted = True
            return
        self._commit()
        if op == LDA:
            self.acc = self.mem[a]
        elif op == ADD:
            self.acc = (self.acc + self.mem[a]) & 0xFF
        elif op == STA:
            self.pending = (a, self.acc)
        self.pc += 2

    def _commit(self):
        if self.pending:
            a, v = self.pending
            self.mem[a] = v
            self.pending = None

    def run(self, limit=100):
        while not self.halted and limit:
            self.tick()
            limit -= 1
        return self
