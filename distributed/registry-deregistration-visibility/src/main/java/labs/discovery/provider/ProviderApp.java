package labs.discovery.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 服务提供者：返回自己的实例名，调用方据此知道请求落在了哪个实例。 */
@SpringBootApplication
@RestController
public class ProviderApp {

    @Value("${eureka.instance.instance-id}")
    private String name;

    @GetMapping("/who")
    public String who() {
        return name;
    }
}
