package labs;

import org.mapstruct.factory.Mappers;

/** 主代码用到了 provided 的 MapStruct：编译能通过，运行时类路径上没有它。 */
public class App {
    public static void main(String[] args) {
        System.out.println("加载 MapStruct 的类：" + Mappers.class.getName());
    }
}
