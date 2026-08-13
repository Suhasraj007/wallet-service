package com.wallet.auth;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Injects the authenticated user id (set by AuthFilter) into controller
 * parameters annotated with @Caller.
 */
public class CallerArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(Caller.class)
                && String.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        Object userId = webRequest.getAttribute(AuthFilter.USER_ATTR, RequestAttributes.SCOPE_REQUEST);
        if (userId == null) {
            throw new IllegalStateException(
                    "@Caller used on a route that is not behind AuthFilter");
        }
        return userId;
    }
}
