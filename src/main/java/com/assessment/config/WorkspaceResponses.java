package com.assessment.config;

import com.assessment.model.Workspace;
import com.assessment.service.AccessScope;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice
public class WorkspaceResponses implements ResponseBodyAdvice<Workspace> {
    @Override
    public boolean supports(MethodParameter type, Class<? extends HttpMessageConverter<?>> converter) {
        return Workspace.class.equals(type.getParameterType());
    }

    @Override
    public Workspace beforeBodyWrite(Workspace body, MethodParameter type, MediaType media,
        Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        response.getHeaders().setCacheControl(CacheControl.noStore());
        var visible = AccessScope.view(body);
        visible.jobs = java.util.List.of();
        return visible;
    }
}
