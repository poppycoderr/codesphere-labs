package labs.ddd.leak.notification.internal;

/** 违规夹具：通知上下文内部的模板表。 */
public final class SmsTemplates {
    public static String confirmed(String session) {
        return "您已报名 " + session;
    }
}
