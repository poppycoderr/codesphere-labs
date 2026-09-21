import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

public class Styles {
    // 1. 多参数构造器：同类型参数可以互换位置
    record Rule(String team, String channel, int minLevel, int maxPerHour) {}

    // 2. 普通 Builder：必填项到 build() 才检查
    static final class RuleBuilder {
        private String team, channel; private int minLevel = 1, maxPerHour = 60;
        RuleBuilder team(String v) { team = v; return this; }
        RuleBuilder channel(String v) { channel = v; return this; }
        RuleBuilder minLevel(int v) { minLevel = v; return this; }
        Rule build() {
            Objects.requireNonNull(team, "team"); Objects.requireNonNull(channel, "channel");
            return new Rule(team, channel, minLevel, maxPerHour);
        }
    }

    // 3. 类型状态 Builder：必填项按步骤出现
    interface NeedTeam { NeedChannel team(String team); }
    interface NeedChannel { Optionals channel(String channel); }
    interface Optionals { Optionals minLevel(int v); Optionals maxPerHour(int v); Rule build(); }
    static NeedTeam rule() {
        return team -> channel -> new Optionals() {
            int min = 1, max = 60;
            public Optionals minLevel(int v) { min = v; return this; }
            public Optionals maxPerHour(int v) { max = v; return this; }
            public Rule build() { return new Rule(team, channel, min, max); }
        };
    }

    // 4. Lambda 配置器
    static final class Spec { String team, channel; int minLevel = 1; Duration quiet = Duration.ZERO; }
    static Rule rule(Consumer<Spec> c) { Spec s = new Spec(); c.accept(s); return new Rule(s.team, s.channel, s.minLevel, 60); }

    public static void main(String[] a) {
        Rule ok = new Rule("payment", "sms", 2, 60);
        Rule swapped = new Rule("sms", "payment", 60, 2);
        System.out.println("构造器参数写反也能编译: " + swapped);
        try { new RuleBuilder().channel("sms").build(); }
        catch (NullPointerException e) { System.out.println("Builder 漏填 team，运行时: NPE " + e.getMessage()); }
        System.out.println("类型状态 Builder: " + rule().team("payment").channel("sms").minLevel(2).build());
        System.out.println("Lambda 配置器: " + rule(r -> { r.team = "payment"; r.channel = "sms"; r.minLevel = 2; }));
    }
}
