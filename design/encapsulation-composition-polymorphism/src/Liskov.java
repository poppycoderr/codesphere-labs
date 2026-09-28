/** 里氏替换：可变的 Square 继承可变的 Rectangle，按矩形契约写的调用方得到错误结果；各自实现接口的不可变 record 没有这个问题。 */
public class Liskov {
    static class Rectangle {
        protected int width;
        protected int height;

        void setWidth(int w) {
            this.width = w;
        }

        void setHeight(int h) {
            this.height = h;
        }

        int area() {
            return width * height;
        }
    }

    static class Square extends Rectangle {
        @Override
        void setWidth(int w) {
            this.width = w;
            this.height = w;
        }

        @Override
        void setHeight(int h) {
            this.width = h;
            this.height = h;
        }
    }

    /** 调用方按 Rectangle 的契约「宽和高可以独立设置」写代码。 */
    static int resize(Rectangle r) {
        r.setWidth(5);
        r.setHeight(4);
        return r.area();
    }

    sealed interface Shape permits Rect, Sq {
        int area();
    }

    record Rect(int width, int height) implements Shape {
        public int area() {
            return width * height;
        }
    }

    record Sq(int side) implements Shape {
        public int area() {
            return side * side;
        }
    }

    public static void main(String[] args) {
        System.out.println("可变继承：resize(new Rectangle()) = " + resize(new Rectangle()) + "，resize(new Square()) = " + resize(new Square()));
        System.out.println("不可变 record：new Rect(5, 4).area() = " + new Rect(5, 4).area() + "，new Sq(4).area() = " + new Sq(4).area());
    }
}
