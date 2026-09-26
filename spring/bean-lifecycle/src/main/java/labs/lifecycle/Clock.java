package labs.lifecycle;

/** PriceCache 的构造器依赖。 */
public class Clock {
    public Clock() {
        Journal.add("Clock 构造");
    }
}
