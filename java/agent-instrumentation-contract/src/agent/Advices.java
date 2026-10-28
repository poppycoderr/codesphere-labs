package agent;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/** 各场景的 advice。advice 的代码会被复制进被测方法，所以这里引用的类都要对被测类可见。 */
public class Advices {

    public static class EnterThrows {
        @Advice.OnMethodEnter
        public static void enter() {
            throw new IllegalStateException("advice bug");
        }
    }

    public static class EnterThrowsSuppressed {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter() {
            throw new IllegalStateException("advice bug");
        }
    }

    public static class Count {
        @Advice.OnMethodEnter
        public static void enter() {
            Rec.ENTER.incrementAndGet();
        }

        @Advice.OnMethodExit
        public static void exit() {
            Rec.EXIT.incrementAndGet();
        }
    }

    public static class CountOnThrowable {
        @Advice.OnMethodEnter
        public static void enter() {
            Rec.ENTER.incrementAndGet();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Thrown Throwable t) {
            Rec.EXIT.incrementAndGet();
            Rec.thrown = t;
        }
    }

    public static class ReturnValue {
        @Advice.OnMethodExit
        public static void exit(@Advice.Return(typing = Assigner.Typing.DYNAMIC) Object r) {
            Rec.LOG.add("return=" + r + "（" + (r == null ? "null" : r.getClass().getSimpleName()) + "）");
        }
    }

    public static class CtorThisOnEnter {
        @Advice.OnMethodEnter
        public static void enter(@Advice.This Object self) {
            Rec.LOG.add("enter " + self);
        }
    }

    public static class CtorThisOnExit {
        @Advice.OnMethodExit
        public static void exit(@Advice.This Object self) {
            Rec.LOG.add("constructed " + self.getClass().getSimpleName());
        }
    }

    public static class Report {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void exit(@Advice.Argument(0) Object arg) throws Exception {
            Rec.report(Rec.safeArg(arg));
        }
    }

    public static class Capture {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter(@Advice.Argument(0) Object arg) {
            Rec.LOG.add(Rec.safeArg(arg));
        }
    }
}
