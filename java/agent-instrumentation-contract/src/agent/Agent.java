package agent;

import java.lang.instrument.Instrumentation;

/** 静态 agent 入口：只保存 Instrumentation，具体转换由实验主程序按场景安装。 */
public class Agent {

    public static volatile Instrumentation inst;

    public static void premain(String args, Instrumentation instrumentation) {
        inst = instrumentation;
    }
}
