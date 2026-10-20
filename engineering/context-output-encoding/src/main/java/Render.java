import java.nio.file.*;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.unbescape.html.HtmlEscape;
public class Render {
    public static void main(String[] args) throws Exception {
        String value = "A \"quote\" & <label> 'single'";
        TemplateEngine engine = new TemplateEngine();
        Context ctx = new Context();
        ctx.setVariable("value", value);
        ctx.setVariable("url", "javascript:void(0)"); // Only inspect the attribute; never navigate it.
        String template = """
            <!doctype html><meta charset="UTF-8">
            <p id="text" th:text="${value}"></p>
            <p id="attribute" th:title="${value}"></p>
            <a id="url" th:href="${url}">unused link</a>
            <script th:inline="javascript">window.safeValue = [[${value}]];</script>
            <script>window.htmlOnly = '[[${value}]]';</script>
            """;
        String result = engine.process(template, ctx);
        // A deliberately incomplete HTML builder, exercised only with plain words.
        result += "<p id=unquoted title=" + HtmlEscape.escapeHtml5("alpha beta") + "></p>";
        Files.createDirectories(Path.of("build/site"));
        Files.writeString(Path.of("build/site/template.html"), result);
        Files.writeString(Path.of("build/site/value.txt"), value);
        System.out.println("thymeleaf=" + org.thymeleaf.Thymeleaf.getVersion() + " java=" + Runtime.version());
    }
}
