package labs.ddd.layered.adapter;

import labs.ddd.layered.application.RegisterCommand;
import labs.ddd.layered.application.RegisterForSession;
import labs.ddd.layered.application.RegistrationResult;
import labs.ddd.layered.application.UseCaseFailure;
import labs.ddd.layered.observability.Trace;

/** 协议适配：请求 → 命令，结果与异常 → HTTP 状态码。不做业务判断。 */
public final class RegistrationController {

    private final RegisterForSession useCase;

    public RegistrationController(RegisterForSession useCase) {
        this.useCase = useCase;
    }

    public HttpResponse post(RegisterRequest request) {
        Trace.step("adapter", "收到 POST /sessions/" + request.sessionId() + "/registrations");
        try {
            RegistrationResult result = useCase.handle(new RegisterCommand(request.sessionId(), request.attendeeId(), request.phone()));
            return respond(new HttpResponse(201, result.outcome() + (result.waitlistPosition() > 0 ? " #" + result.waitlistPosition() : "")));
        } catch (UseCaseFailure.InvalidInput e) {
            return respond(new HttpResponse(400, e.getMessage()));
        } catch (UseCaseFailure.Rejected e) {
            return respond(new HttpResponse(422, e.getMessage()));
        } catch (UseCaseFailure.Conflict e) {
            return respond(new HttpResponse(409, e.getMessage()));
        }
    }

    private static HttpResponse respond(HttpResponse response) {
        Trace.step("adapter", "返回 " + response.status() + " " + response.body());
        return response;
    }
}
