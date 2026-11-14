package io.github.sunjirapath.ssg.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class XmlBeansBranchGapTest {

    @TempDir
    Path tmp;

    @Test
    void importsAcceptLiteralClasspathAndParentRelativeLocationsAndSkipUnsafePaths() {
        var repo = io.github.sunjirapath.ssg.TempRepo.at(tmp)
                .resource("spring/root.xml", """
                        <beans xmlns="http://www.springframework.org/schema/beans" xmlns:foreign="urn:foreign">
                          <bean class="example.Root$Nested"/>
                          <bean class="   "/>
                          <bean class="${missing}"/>
                          <foreign:bean class="example.Foreign"/>
                          <import resource="child.xml"/>
                          <import resource="/absolute.xml"/>
                          <import resource="classpath:/shared.xml"/>
                          <import resource="classpath*:/shared-star.xml"/>
                          <import resource="${missing}"/>
                          <import resource=""/>
                          <import resource="//network.xml"/>
                          <import resource="file:/outside.xml"/>
                          <import resource="glob*.xml"/>
                          <import resource="question?.xml"/>
                        </beans>
                        """)
                .resource("spring/child.xml", "<beans><bean class=\"example.Child\"/></beans>")
                .resource("absolute.xml", "<beans><bean class=\"example.Absolute\"/></beans>")
                .resource("shared.xml", "<beans><bean class=\"example.Shared\"/></beans>")
                .resource("shared-star.xml", "<beans><bean class=\"example.Star\"/></beans>");
        Path root = repo.root().resolve("src/main/resources");

        var classes = new XmlBeans(new Config(), List.of(root)).classes("classpath:/spring/root.xml");

        assertThat(classes).containsExactlyInAnyOrder(
                "example.Root.Nested", "example.Child", "example.Absolute", "example.Shared", "example.Star");
    }
}
