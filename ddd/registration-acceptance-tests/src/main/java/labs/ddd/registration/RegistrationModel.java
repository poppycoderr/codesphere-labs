package labs.ddd.registration;

import java.time.Instant;
import java.util.List;
import labs.ddd.registration.Ids.AttendeeId;

/** 场次对外的命令入口。验收测试只依赖这个接口，以便同一组测试先后检查初稿模型和修订后的模型。 */
public interface RegistrationModel {

    List<RegistrationEvent> register(AttendeeId attendee, Instant now);

    List<RegistrationEvent> cancel(AttendeeId attendee, Instant now);

    void closeRegistration();
}
