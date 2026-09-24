package labs.ddd.constleak.registration;

import labs.ddd.constleak.notification.internal.SmsTemplates;

/** 违规夹具：只引用了通知模块的编译期常量。 */
public final class RegistrationMessages {
    public String confirmed(String session) {
        return String.format(SmsTemplates.CONFIRMED, session);
    }
}
