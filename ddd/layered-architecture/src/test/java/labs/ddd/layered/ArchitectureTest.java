package labs.ddd.layered;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 主代码满足规则；三个违规夹具各自被规则拦下，失败信息写入证据。 */
class ArchitectureTest {

    static final List<ArchRule> RULES = List.of(
            ArchitectureRules.LAYERS, ArchitectureRules.DOMAIN_FREE_OF_FRAMEWORKS, ArchitectureRules.DOMAIN_FREE_OF_OUTER_LAYERS);

    @Test
    void mainCodeFollowsTheRules() {
        JavaClasses main = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPackages("labs.ddd.layered");
        RULES.forEach(r -> r.check(main));
        Facts.record("arch.main", "导入 " + main.size() + " 个类，3 条规则全部通过");
    }

    @Test
    void domainHoldingAPersistenceObjectIsRejected() {
        report("domainleak");
    }

    @Test
    void domainAnnotatedWithSpringIsRejected() {
        report("framework");
    }

    @Test
    void adapterBypassingApplicationIsRejected() {
        report("bypass");
    }

    private static void report(String fixture) {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPackages("labs.ddd.violations." + fixture);
        List<String> failed = RULES.stream()
                .filter(r -> !r.evaluate(classes).getFailureReport().isEmpty())
                .map(r -> r == ArchitectureRules.LAYERS ? "LAYERS" : r == ArchitectureRules.DOMAIN_FREE_OF_FRAMEWORKS ? "DOMAIN_FREE_OF_FRAMEWORKS" : "DOMAIN_FREE_OF_OUTER_LAYERS")
                .toList();
        if (failed.isEmpty()) {
            throw new AssertionError(fixture + " 没有被任何规则拦下");
        }
        String first = RULES.stream()
                .map(r -> r.evaluate(classes).getFailureReport().getDetails())
                .filter(d -> !d.isEmpty())
                .findFirst().orElseThrow().getFirst();
        Facts.record("arch.violation." + fixture, "被 " + String.join("、", failed) + " 拦下；" + first.replaceAll(" in \\(.*\\)$", ""));
        assertThrows(AssertionError.class, () -> RULES.stream().filter(r -> !r.evaluate(classes).getFailureReport().isEmpty()).findFirst().orElseThrow().check(classes));
    }

    static String join(List<String> lines) {
        return lines.stream().collect(Collectors.joining(" | "));
    }
}
