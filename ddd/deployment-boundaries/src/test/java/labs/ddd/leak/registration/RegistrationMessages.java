package labs.ddd.leak.registration;

import labs.ddd.leak.notification.internal.SmsTemplates;

/** 违规夹具：报名模块直接调用通知模块内部的模板，「反正在一个工程里」。 */
public final class RegistrationMessages {
    public String confirmed(String session) {
        return SmsTemplates.confirmed(session);
    }
}
