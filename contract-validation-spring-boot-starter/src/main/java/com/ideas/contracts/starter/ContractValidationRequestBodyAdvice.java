package com.ideas.contracts.starter;

import java.lang.reflect.Method;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

@ControllerAdvice
public class ContractValidationRequestBodyAdvice extends RequestBodyAdviceAdapter {
  private final ContractPayloadValidator payloadValidator;
  private final ContractValidationProperties properties;

  public ContractValidationRequestBodyAdvice(
      ContractPayloadValidator payloadValidator,
      ContractValidationProperties properties) {
    this.payloadValidator = payloadValidator;
    this.properties = properties;
  }

  @Override
  public boolean supports(
      MethodParameter methodParameter,
      java.lang.reflect.Type targetType,
      Class<? extends HttpMessageConverter<?>> converterType) {
    if (!properties.isEnabled()) {
      return false;
    }
    return resolveAnnotation(methodParameter) != null;
  }

  @Override
  public Object afterBodyRead(
      Object body,
      HttpInputMessage inputMessage,
      MethodParameter parameter,
      java.lang.reflect.Type targetType,
      Class<? extends HttpMessageConverter<?>> converterType) {
    ValidateContract annotation = resolveAnnotation(parameter);
    if (annotation != null) {
      payloadValidator.validate(annotation.contractId(), annotation.version(), body);
    }
    return body;
  }

  private ValidateContract resolveAnnotation(MethodParameter parameter) {
    Method method = parameter.getMethod();
    if (method != null) {
      ValidateContract onMethod = AnnotationUtils.findAnnotation(method, ValidateContract.class);
      if (onMethod != null) {
        return onMethod;
      }
      ValidateContract onClass = AnnotationUtils.findAnnotation(method.getDeclaringClass(), ValidateContract.class);
      if (onClass != null) {
        return onClass;
      }
    }
    return null;
  }
}
