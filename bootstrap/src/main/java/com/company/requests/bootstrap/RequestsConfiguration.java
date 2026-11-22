package com.company.requests.bootstrap;

import com.company.requests.adapter.http.RequestsMvcConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

@Configuration(proxyBeanMethods = false)
@Import(RequestsMvcConfiguration.class)
public class RequestsConfiguration {

    @Bean
    DispatcherServlet requestsDispatcherServlet(WebApplicationContext context) {
        return new DispatcherServlet(context);
    }
}
