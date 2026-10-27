package com.globalnewsflash.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
    private static final JavaClasses CODE = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.globalnewsflash");

    @Test
    void domainDependsOnlyOnDomainAndJdk() {
        classes().that().resideInAPackage("..domain..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..", "com.globalnewsflash..domain..", "com.globalnewsflash.shared")
                .check(CODE);
    }

    @Test
    void applicationUsesPortsInsteadOfAdapters() {
        noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..infrastructure..", "..integrations..", "org.apache.ibatis..", "tools.jackson..")
                .check(CODE);
    }

    @Test
    void portsDoNotDependOnApplicationOrAdapters() {
        noClasses().that().resideInAPackage("..port..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..application..", "..infrastructure..", "..integrations..", "org.springframework..",
                        "org.apache.ibatis..", "tools.jackson..")
                .check(CODE);
    }

    @Test
    void entryPointsAndIntegrationsUseInboundPorts() {
        noClasses().that().resideInAnyPackage("..interfaces..", "..integrations..", "..infrastructure..")
                .should().dependOnClassesThat().resideInAPackage("..application..")
                .check(CODE);
    }

    @Test
    void coreInterfacesAreDeclaredAsPorts() {
        noClasses().that().resideInAnyPackage("..domain..", "..application..")
                .should().beInterfaces()
                .check(CODE);
    }

    @Test
    void applicationServicesCollaborateThroughPorts() {
        classes().that().resideInAPackage("..application..")
                .should(new ArchCondition<JavaClass>("collaborate through ports rather than concrete application services") {
                    @Override
                    public void check(JavaClass origin, ConditionEvents events) {
                        String owner = origin.getName().split("\\$", 2)[0];
                        for (var dependency : origin.getDirectDependenciesFromSelf()) {
                            var target = dependency.getTargetClass();
                            if (target.getPackageName().endsWith(".application")
                                    && !target.getName().split("\\$", 2)[0].equals(owner)) {
                                events.add(SimpleConditionEvent.violated(origin, dependency.getDescription()));
                            }
                        }
                    }
                }).check(CODE);
    }
}
