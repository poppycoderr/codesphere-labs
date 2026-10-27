package labs.mvc;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 几个用来观察各层行为的端点。 */
@RestController
public class Endpoints {
    private final Journal journal;

    Endpoints(Journal journal) {
        this.journal = journal;
    }

    @GetMapping("/json")
    Map<String, String> json() {
        journal.steps.add("handler");
        return Map.of("status", "ok");
    }

    @GetMapping("/entity")
    ResponseEntity<String> entity() {
        journal.steps.add("handler");
        return ResponseEntity.ok("ok");
    }

    @GetMapping("/fail")
    String fail() {
        journal.steps.add("handler");
        throw new IllegalStateException("库存不足");
    }

    @GetMapping("/blocked")
    String blocked() {
        journal.steps.add("handler");
        return "不应该执行";
    }

    @GetMapping("/filter-fail")
    String filterFail() {
        journal.steps.add("handler");
        return "不应该执行";
    }
}
