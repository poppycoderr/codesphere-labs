import sys
import unittest

sys.path.insert(0, "src")
from cpu import Contract, Fault, State, assemble, run, step  # noqa: E402


class StepTest(unittest.TestCase):

    def test_step_does_not_mutate_old_state(self):
        s0 = State(mem=tuple(assemble(["XCH 0x10"])), acc=5)
        s1 = step(s0, Contract())
        self.assertEqual(s0.acc, 5)
        self.assertEqual(s0.mem[0x10], 0)
        self.assertEqual((s1.acc, s1.mem[0x10]), (0, 5))

    def test_add_sets_carry_and_wraps(self):
        mem = assemble(["LDA 0x10", "ADD 0x11", "HLT"])
        mem[0x10], mem[0x11] = 255, 1
        s, _, _ = run(mem)
        self.assertEqual((s.acc, s.z, s.c), (0, True, True))

    def test_sub_borrow(self):
        mem = assemble(["LDA 0x10", "SUB 0x11", "HLT"])
        mem[0x10], mem[0x11] = 0, 1
        s, _, _ = run(mem)
        self.assertEqual((s.acc, s.c), (255, True))

    def test_illegal_opcode_faults_at_its_address(self):
        mem = assemble(["OUT"])
        mem[2] = 0xEE
        s, _, end = run(mem)
        self.assertEqual(s.pc, 2)
        self.assertIn("0xEE", end)

    def test_fault_is_exception_from_step(self):
        with self.assertRaises(Fault):
            step(State(mem=(0xEE, 0) + (0,) * 254), Contract())

    def test_zero_memory_halts(self):
        _, n, end = run([0] * 256)
        self.assertEqual((n, end), (1, "停机"))


if __name__ == "__main__":
    unittest.main()
