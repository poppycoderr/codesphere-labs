package labs.discovery.consumer;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 调用方：经 Spring Cloud LoadBalancer 按服务名调用。Eureka 客户端自己也会从容器里取 RestClient.Builder，
 * 所以另外提供一个不带负载均衡的 @Primary 构建器给它，否则它访问注册中心的请求也会被当成服务名去解析。
 */
@SpringBootApplication
public class ConsumerApp {

    @Bean
    @Primary
    RestClient.Builder plainRestClient() {
        return RestClient.builder();
    }

    @Bean
    @LoadBalanced
    RestClient.Builder lbRestClient() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(300);
        f.setReadTimeout(1000);
        return RestClient.builder().requestFactory(f);
    }
}
