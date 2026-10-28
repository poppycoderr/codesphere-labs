package lab.biz;

/** 被测的业务代码：每个场景用一个独立的类，避免互相影响。 */
public class Biz {

    public static class Price {
        public int total(int qty) {
            return qty * 100;
        }
    }

    public static class PriceSafe {
        public int total(int qty) {
            return qty * 100;
        }
    }

    public static class Refund {
        public int refund(int amount) {
            if (amount < 0) {
                throw new IllegalArgumentException("negative amount");
            }
            return amount;
        }
    }

    public static class RefundOnThrowable {
        public int refund(int amount) {
            if (amount < 0) {
                throw new IllegalArgumentException("negative amount");
            }
            return amount;
        }
    }

    public static class Prim {
        public long sum(int a) {
            return a + 1L;
        }

        public void nothing(int a) {
        }
    }

    public static class Order {
        public Order() {
        }
    }

    public static class Order2 {
        public Order2() {
        }
    }

    public static class Loaded {
        public int ping(int x) {
            return x;
        }
    }

    public static class Loaded2 {
        public int ping(int x) {
            return x;
        }
    }

    public static class Schema {
        public int ping(int x) {
            return x;
        }
    }

    public static class Twice {
        public int run(int x) {
            return x;
        }
    }

    public static class Pay {
        public String pay(String card) {
            return "ok";
        }
    }

    public static class Login {
        public boolean login(String password) {
            return true;
        }
    }
}
