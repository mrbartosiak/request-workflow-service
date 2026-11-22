package com.company.requests.bootstrap;

import com.company.requests.adapter.http.SyntheticActorResolver;
import com.company.requests.application.OperationSecrets;
import com.company.requests.application.SyntheticScopePolicy;
import com.company.requests.application.WriterEpoch;
import com.company.requests.domain.OrganizationId;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestActionMvcSmokeTest {
    @Test
    void effectiveProfileAuthenticatesExistingRequestActionsBeforeValidationOrStorage() throws Exception {
        var organization = new OrganizationId("synthetic-context-smoke");
        var policy = new SyntheticScopePolicy(organization, 1, List.of());
        var actors = new SyntheticActorResolver("synthetic", "requests", List.of());
        var signing = new byte[32];
        var intent = new byte[32];
        intent[0] = 1;
        var create = new SyntheticCreateConfiguration.Settings(unused(), unused(), policy,
                new WriterEpoch(UUID.randomUUID(), 1), actors, () -> Instant.EPOCH,
                new OperationSecrets(1, Map.of(1, signing), 1, Map.of(1, intent)));
        var settings = new SyntheticEffectiveAccessConfiguration.Settings(
                create, unused(), unused(), unused(), unused(), Map.of());
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.addBeanFactoryPostProcessor(factory -> factory.registerSingleton("syntheticSettings", settings));
            context.register(SyntheticEffectiveAccessConfiguration.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            var requests = List.of(
                    MockMvcRequestBuilders.get("/requests/example?unexpected=value"),
                    MockMvcRequestBuilders.get("/requests/example/history?unexpected=value"),
                    MockMvcRequestBuilders.patch("/requests/example/body?unexpected=value"),
                    MockMvcRequestBuilders.post("/requests/example/actions/verify?unexpected=value"),
                    MockMvcRequestBuilders.post("/requests/example/actions/accept?unexpected=value"),
                    MockMvcRequestBuilders.post("/requests/example/actions/delete?unexpected=value"),
                    MockMvcRequestBuilders.post("/requests/example/actions/reject?unexpected=value"),
                    MockMvcRequestBuilders.post("/requests/example/actions/publish?unexpected=value"));
            org.junit.jupiter.api.Assertions.assertAll(requests.stream().map(input -> () -> {
                var response = mvc.perform(input).andReturn().getResponse();
                assertEquals(401, response.getStatus());
                assertEquals("Bearer", response.getHeader("WWW-Authenticate"));
            }));
        }
    }

    private static DataSource unused() {
        return new AbstractDataSource() {
            @Override
            public Connection getConnection() {
                throw new AssertionError("Unauthenticated request action accessed storage");
            }

            @Override
            public Connection getConnection(String user, String password) {
                return getConnection();
            }
        };
    }
}
