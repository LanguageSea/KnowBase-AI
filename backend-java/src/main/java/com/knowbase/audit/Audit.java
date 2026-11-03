package com.knowbase.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a business mutation that must be written to {@code sys_operation_log}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audit {

    String action();

    String resourceType();

    /** Method parameter name containing the resource id, when available. */
    String resourceIdParam() default "";
}
