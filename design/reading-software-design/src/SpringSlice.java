import org.springframework.beans.factory.config.*;
import org.springframework.beans.factory.support.*;
import org.springframework.context.annotation.*;
import org.springframework.context.support.GenericApplicationContext;

public class SpringSlice {
    static class Repo { Repo() { System.out.println("    → 创建 Repo"); } }
    static class Service { final Repo repo; Service(Repo repo) { this.repo = repo; System.out.println("    → 创建 Service，注入 " + repo.getClass().getSimpleName()); } }
    static class Report { Report() { System.out.println("    → 创建 Report（懒加载）"); } }

    public static void main(String[] a) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(Service.class);                                  // 先登记 Service：它依赖 Repo
        ctx.registerBean(Repo.class);
        ctx.registerBean(Report.class, bd -> bd.setLazyInit(true));
        ctx.addBeanFactoryPostProcessor(bf -> {
            System.out.println("  BeanFactoryPostProcessor：已有 " + bf.getBeanDefinitionCount() + " 个 Bean 定义，其中业务 Bean：");
            for (String n : bf.getBeanDefinitionNames()) {
                {
                    BeanDefinition d = bf.getBeanDefinition(n);
                    System.out.printf("    %-26s 作用域=%s 懒加载=%s 已实例化=%s%n", n,
                            d.getScope().isEmpty() ? "singleton" : d.getScope(), d.isLazyInit(), bf.containsSingleton(n));
                }
            }
            // 修改定义，而不是修改对象：这是容器提供的扩展点
            bf.getBeanDefinition(bf.getBeanNamesForType(Repo.class)[0]).setDescription("由后置处理器修改过");
        });
        System.out.println("refresh() 之前：还没有任何 Bean 实例");
        ctx.refresh();
        System.out.println("refresh() 之后：Report 已实例化？" + ctx.getBeanFactory().containsSingleton(ctx.getBeanNamesForType(Report.class)[0]));
        ctx.getBean(Report.class);
        System.out.println("Repo 定义的描述：" + ctx.getBeanFactory().getBeanDefinition(ctx.getBeanNamesForType(Repo.class)[0]).getDescription());
        ctx.close();
    }
}
