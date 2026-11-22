package com.company.requests.bootstrap;

import java.io.DataInputStream;
import java.util.Objects;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.core.SpringVersion;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockServletConfig;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestsMvcSmokeTest {

    @Test
    void usesJava25BytecodeAndRuntimeAndSpring7011() throws Exception {
        assertEquals(25, Runtime.version().feature(), "Java runtime feature version");
        try (var classFile = new DataInputStream(Objects.requireNonNull(
                RequestsConfiguration.class.getResourceAsStream("RequestsConfiguration.class")))) {
            assertEquals(0xcafebabe, classFile.readInt(), "Bootstrap class-file magic");
            assertEquals(0, classFile.readUnsignedShort(), "Bootstrap class-file minor version");
            assertEquals(69, classFile.readUnsignedShort(), "Java 25 class-file major version");
        }
        assertEquals("7.0.11", SpringVersion.getVersion(), "Resolved Spring Framework version");
    }

    @RepeatedTest(2)
    void refreshesAndInitializesTheWebContextInProcess() throws Exception {
        var servletContext = new MockServletContext();
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(servletContext);
            context.register(RequestsConfiguration.class);
            context.refresh();

            var dispatcher = context.getBean("requestsDispatcherServlet", DispatcherServlet.class);
            dispatcher.init(new MockServletConfig(servletContext, "requests"));
            try {
                var mapping = context.getBean(RequestMappingHandlerMapping.class);
                var adapter = context.getBean(RequestMappingHandlerAdapter.class);
                assertSame(context, dispatcher.getWebApplicationContext());
                assertTrue(dispatcher.getHandlerMappings().contains(mapping),
                        "The dispatcher uses the configured MVC handler mapping");
                assertTrue(adapter.getMessageConverters().stream()
                                .anyMatch(JacksonJsonHttpMessageConverter.class::isInstance),
                        "The MVC handler adapter has a Jackson 3 JSON message converter");
            } finally {
                dispatcher.destroy();
            }
        }
    }
}
