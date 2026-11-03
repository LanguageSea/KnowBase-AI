package com.knowbase;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;

import com.knowbase.doc.DocumentService;
import com.knowbase.kb.AuthzService;
import com.knowbase.kb.KbMemberService;
import com.knowbase.kb.KbService;
import com.knowbase.qa.QaService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 轻量架构守卫，替代离线环境无法解析的 ArchUnit 依赖。
 * 规则与 Stage 2 T2.5 一致：知识域 Service 必须直接依赖 AuthzService。
 */
class AuthzArchitectureTest {

    @ParameterizedTest
    @ValueSource(classes = {DocumentService.class, QaService.class, KbService.class, KbMemberService.class})
    void knowledge_services_must_depend_on_authz_service(Class<?> serviceType) {
        boolean fieldDependency = Arrays.stream(serviceType.getDeclaredFields())
                .map(Field::getType)
                .anyMatch(AuthzService.class::equals);
        boolean constructorDependency = Arrays.stream(serviceType.getDeclaredConstructors())
                .map(Constructor::getParameterTypes)
                .flatMap(Arrays::stream)
                .anyMatch(AuthzService.class::equals);
        assertThat(fieldDependency || constructorDependency)
                .as(serviceType.getSimpleName() + " must call AuthzService")
                .isTrue();
    }
}
