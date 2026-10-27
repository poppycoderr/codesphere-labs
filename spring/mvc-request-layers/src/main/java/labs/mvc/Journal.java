package labs.mvc;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/** 记录一次请求经过了哪些层。 */
@Component
public class Journal {
    public final List<String> steps = new CopyOnWriteArrayList<>();
}
