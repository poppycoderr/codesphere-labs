package labs.ddd.registration;

import java.util.Optional;
import labs.ddd.registration.Ids.AttendeeId;
import labs.ddd.registration.Ids.SessionId;

/**
 * 事件风暴墙上的紫色便利贴（策略）：「每当有人拿到收费场次的名额，就为他生成一笔应收」。
 * 策略只把报名上下文的事实翻译成计费上下文的命令，不在这里计算金额以外的计费规则。
 */
public final class ChargeOnSeatTaken {

    public record CreateCharge(
            SessionId sessionId,
            AttendeeId payer,
            long amountInCents) {
    }

    private final long feeInCents;

    public ChargeOnSeatTaken(long feeInCents) {
        this.feeInCents = feeInCents;
    }

    public Optional<CreateCharge> on(RegistrationEvent event) {
        if (feeInCents == 0) {
            return Optional.empty();
        }
        return switch (event) {
            case RegistrationEvent.RegistrationConfirmed e -> Optional.of(new CreateCharge(e.sessionId(), e.attendeeId(), feeInCents));
            case RegistrationEvent.CandidatePromoted e -> Optional.of(new CreateCharge(e.sessionId(), e.attendeeId(), feeInCents));
            default -> Optional.empty();
        };
    }
}
