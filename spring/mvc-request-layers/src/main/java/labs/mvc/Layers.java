package labs.mvc;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 一个 Filter 与一个 HandlerInterceptor，都在调用前后各加一个响应头并记日志。 */
@Configuration
public class Layers implements WebMvcConfigurer {
    private final Journal journal;

    Layers(Journal journal) {
        this.journal = journal;
    }

    @Bean
    FilterRegistrationBean<Filter> headerFilter() {
        Filter f = (ServletRequest req, ServletResponse res, FilterChain chain) -> filter((HttpServletRequest) req, (HttpServletResponse) res, chain);
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>(f);
        reg.addUrlPatterns("/*");
        return reg;
    }

    private void filter(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (req.getDispatcherType() != jakarta.servlet.DispatcherType.REQUEST) {
            chain.doFilter(req, res);
            return;
        }
        journal.steps.add("filter-before");
        res.setHeader("X-Filter-Before", "1");
        if (req.getRequestURI().equals("/filter-fail")) {
            throw new IllegalStateException("过滤器里的检查失败");
        }
        chain.doFilter(req, res);
        res.setHeader("X-Filter-After", "1");
        journal.steps.add("filter-after(committed=" + res.isCommitted() + ")");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                journal.steps.add("preHandle");
                res.setHeader("X-Pre", "1");
                return !req.getRequestURI().equals("/blocked");
            }

            @Override
            public void postHandle(HttpServletRequest req, HttpServletResponse res, Object handler, ModelAndView mav) {
                res.setHeader("X-Post", "1");
                journal.steps.add("postHandle(committed=" + res.isCommitted() + ")");
            }

            @Override
            public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
                journal.steps.add("afterCompletion(ex=" + (ex == null ? "null" : ex.getClass().getSimpleName()) + ")");
            }
        });
    }
}
