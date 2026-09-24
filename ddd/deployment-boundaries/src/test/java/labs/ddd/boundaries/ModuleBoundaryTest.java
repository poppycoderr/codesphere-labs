package labs.ddd.boundaries;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

/** 模块化单体里的上下文边界：报名模块只能使用通知模块的 api 包，也不能知道自己被怎样部署。 */
class ModuleBoundaryTest {

    static final ArchRule ONLY_PUBLISHED_LANGUAGE = noClasses()
            .that().resideInAPackage("..registration..")
            .should().dependOnClassesThat().resideInAnyPackage("..notification.internal..", "..deployment..");

    @Test
    void registrationUsesOnlyTheNotificationApi() {
        JavaClasses main = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPackages("labs.ddd.boundaries");
        ONLY_PUBLISHED_LANGUAGE.check(main);
        JavaClasses leak = new ClassFileImporter().importPackages("labs.ddd.leak");
        EvaluationResult result = ONLY_PUBLISHED_LANGUAGE.evaluate(leak);
        assertFalse(result.getFailureReport().isEmpty());
        Facts.record("module.main", "主代码 " + main.size() + " 个类：报名模块只依赖通知 api");
        Facts.record("module.leak", result.getFailureReport().getDetails().getFirst().replaceAll(" in \\(.*\\)$", ""));
        EvaluationResult constant = ONLY_PUBLISHED_LANGUAGE.evaluate(new ClassFileImporter().importPackages("labs.ddd.constleak"));
        Facts.record("module.constant_leak", "只引用对方的 static final String 常量：违规 " + constant.getFailureReport().getDetails().size()
                + " 条（常量已被编译器内联进调用方）");
    }
}
