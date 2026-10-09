"""生成实验用的 Maven 工程：两个版本的 util、依赖它们的 lib-a 与 lib-b、多一层的 mid、两个 BOM，以及若干个只有依赖声明不同的应用。
用法：gen.py <输出目录>。所有工程的 groupId 都是 labs.mediation。"""
import os
import sys

G = "labs.mediation"
PLUGINS = """
  <properties>
    <maven.compiler.release>25</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  <build>
    <pluginManagement>
      <plugins>
        <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.16.0</version></plugin>
        <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.5.0</version></plugin>
        <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-jar-plugin</artifactId><version>3.5.1</version></plugin>
        <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-install-plugin</artifactId><version>3.2.0</version></plugin>
        <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-dependency-plugin</artifactId><version>3.11.0</version></plugin>
        <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-enforcer-plugin</artifactId><version>3.6.3</version></plugin>
      </plugins>
    </pluginManagement>%s
  </build>
"""


def dep(artifact, version=None):
    v = "<version>%s</version>" % version if version else ""
    return "    <dependency><groupId>%s</groupId><artifactId>%s</artifactId>%s</dependency>\n" % (G, artifact, v)


def pom(artifact, version, deps="", management="", packaging="jar", build_extra=""):
    mgmt = "  <dependencyManagement>\n    <dependencies>\n%s    </dependencies>\n  </dependencyManagement>\n" % management if management else ""
    body = "  <dependencies>\n%s  </dependencies>\n" % deps if deps else ""
    return ('<?xml version="1.0" encoding="UTF-8"?>\n<project xmlns="http://maven.apache.org/POM/4.0.0">\n  <modelVersion>4.0.0</modelVersion>\n'
            "  <groupId>%s</groupId>\n  <artifactId>%s</artifactId>\n  <version>%s</version>\n  <packaging>%s</packaging>\n%s%s%s</project>\n"
            % (G, artifact, version, packaging, PLUGINS % build_extra, mgmt, body))


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)


def bom_import(artifact):
    return ("      <dependency><groupId>%s</groupId><artifactId>%s</artifactId><version>1.0</version><type>pom</type><scope>import</scope></dependency>\n"
            % (G, artifact))


def main(root):
    lib = os.path.join(root, "libs")
    # util 1.0 只有 upper；util 2.0 多了 title
    write(lib + "/util-1.0/pom.xml", pom("util", "1.0"))
    write(lib + "/util-1.0/src/main/java/labs/util/Text.java",
          "package labs.util;\n\npublic final class Text {\n    public static String upper(String s) { return s.toUpperCase(); }\n}\n")
    write(lib + "/util-2.0/pom.xml", pom("util", "2.0"))
    write(lib + "/util-2.0/src/main/java/labs/util/Text.java",
          "package labs.util;\n\npublic final class Text {\n    public static String upper(String s) { return s.toUpperCase(); }\n"
          "    public static String title(String s) { return s.substring(0, 1).toUpperCase() + s.substring(1); }\n}\n")
    write(lib + "/lib-a/pom.xml", pom("lib-a", "1.0", dep("util", "1.0")))
    write(lib + "/lib-a/src/main/java/labs/a/LibA.java",
          "package labs.a;\n\npublic final class LibA {\n    public static String shout(String s) { return labs.util.Text.upper(s); }\n}\n")
    write(lib + "/lib-b/pom.xml", pom("lib-b", "1.0", dep("util", "2.0")))
    write(lib + "/lib-b/src/main/java/labs/b/LibB.java",
          "package labs.b;\n\npublic final class LibB {\n    public static String headline(String s) { return labs.util.Text.title(s); }\n}\n")
    write(lib + "/mid/pom.xml", pom("mid", "1.0", dep("lib-b", "1.0")))
    write(lib + "/mid/src/main/java/labs/mid/Mid.java", "package labs.mid;\n\npublic final class Mid {\n}\n")
    write(lib + "/bom-x/pom.xml", pom("bom-x", "1.0", management="      " + dep("util", "1.0").strip() + "\n", packaging="pom"))
    write(lib + "/bom-y/pom.xml", pom("bom-y", "1.0", management="      " + dep("util", "2.0").strip() + "\n", packaging="pom"))

    main_java = ("package labs.app;\n\npublic class Main {\n    public static void main(String[] args) {\n"
                 "        String a, b;\n"
                 "        try { a = labs.a.LibA.shout(\"hi\"); } catch (LinkageError e) { a = e.getClass().getSimpleName() + \": \" + e.getMessage(); }\n"
                 "        try { b = labs.b.LibB.headline(\"hello\"); } catch (LinkageError e) { b = e.getClass().getSimpleName() + \": \" + e.getMessage(); }\n"
                 "        System.out.println(\"LibA.shout -> \" + a + \"；LibB.headline -> \" + b);\n    }\n}\n")
    apps = {
        # 名字: (依赖声明, 依赖管理)
        "nearest": (dep("lib-a", "1.0") + dep("mid", "1.0"), ""),
        "order-a-first": (dep("lib-a", "1.0") + dep("lib-b", "1.0"), ""),
        "order-b-first": (dep("lib-b", "1.0") + dep("lib-a", "1.0"), ""),
        "managed": (dep("lib-a", "1.0") + dep("lib-b", "1.0"), "      " + dep("util", "2.0").strip() + "\n"),
        "direct": (dep("lib-a", "1.0") + dep("mid", "1.0") + dep("util", "2.0"), ""),
        "bom-x-then-y": (dep("lib-a", "1.0") + dep("lib-b", "1.0"), bom_import("bom-x") + bom_import("bom-y")),
        "bom-y-then-x": (dep("lib-a", "1.0") + dep("lib-b", "1.0"), bom_import("bom-y") + bom_import("bom-x")),
        "own-before-bom": (dep("lib-a", "1.0") + dep("lib-b", "1.0"), "      " + dep("util", "2.0").strip() + "\n" + bom_import("bom-x")),
        "explicit-over-managed": (dep("lib-a", "1.0") + dep("lib-b", "1.0") + dep("util", "1.0"), "      " + dep("util", "2.0").strip() + "\n"),
    }
    for name, (deps, mgmt) in apps.items():
        write("%s/apps/%s/pom.xml" % (root, name), pom("app-" + name, "1.0", deps, mgmt))
        write("%s/apps/%s/src/main/java/labs/app/Main.java" % (root, name), main_java)
    # 只有依赖管理、没有任何依赖声明
    write(root + "/apps/managed-only/pom.xml", pom("app-managed-only", "1.0", "", "      " + dep("util", "2.0").strip() + "\n"))
    write(root + "/apps/managed-only/src/main/java/labs/app/Main.java", "package labs.app;\n\npublic class Main {\n    public static void main(String[] args) { }\n}\n")
    # 在 nearest 的基础上打开 Enforcer 的两条规则
    for rule in ("dependencyConvergence", "requireUpperBoundDeps"):
        extra = ("\n    <plugins>\n      <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-enforcer-plugin</artifactId>\n"
                 "        <executions><execution><goals><goal>enforce</goal></goals><configuration><rules><%s/></rules></configuration></execution></executions>\n"
                 "      </plugin>\n    </plugins>" % rule)
        deps, mgmt = apps["nearest"]
        write("%s/apps/enforce-%s/pom.xml" % (root, rule), pom("app-enforce-" + rule, "1.0", deps, mgmt, build_extra=extra))
        write("%s/apps/enforce-%s/src/main/java/labs/app/Main.java" % (root, rule), main_java)


if __name__ == "__main__":
    main(sys.argv[1])
