package lab;

/** 按类型和限定符取组件 */
public interface Context {
    Object get(Class<?> type, String qualifier);
}
