package labs.creation;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/** Spring 作用域：一个 singleton 的发送器，一个 prototype 的请求上下文；发送器分别以直接注入和 ObjectProvider 取得上下文。 */
@Configuration
public class ScopedBeans {

    /** 每次获取都新建：代表一次请求内的临时状态。 */
    public static final class RequestContext {
    }

    public static final class Sender {
        final RequestContext injected;
        final ObjectProvider<RequestContext> provider;

        Sender(RequestContext injected, ObjectProvider<RequestContext> provider) {
            this.injected = injected;
            this.provider = provider;
        }

        public RequestContext injectedContext() {
            return injected;
        }

        public RequestContext freshContext() {
            return provider.getObject();
        }
    }

    @Bean
    @Scope("prototype")
    RequestContext requestContext() {
        return new RequestContext();
    }

    @Bean
    Sender sender(RequestContext requestContext, ObjectProvider<RequestContext> provider) {
        return new Sender(requestContext, provider);
    }
}
