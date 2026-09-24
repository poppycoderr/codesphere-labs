package labs.events;

/** 报名成功后发布的事件。failIn 指定哪个监听器应当抛出异常，用来观察失败的传播。 */
public record Events(String registrationId, String failIn) {
}
