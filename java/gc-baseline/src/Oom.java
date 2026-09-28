import java.util.ArrayList;
import java.util.List;

/** 不断保留指定大小（KB）的数组直到 OutOfMemoryError，用来观察 -XX:+HeapDumpOnOutOfMemoryError 生成的文件大小。 */
public class Oom {
    public static void main(String[] args) {
        int size = Integer.parseInt(args[0]) * 1024;
        List<byte[]> keep = new ArrayList<>();
        while (true) {
            keep.add(new byte[size]);
        }
    }
}
