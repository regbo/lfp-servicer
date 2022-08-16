package com.austinv11.servicer;

import java.lang.annotation.*;

/**
 * Lightweight alternative to Google's AutoService. This allows for marking a class as a Java service implementation
 * easily.
 *
 * <b>Note:</b> This requires annotation processing to be active.
 *
 * @see java.util.ServiceLoader
 * @see com.austinv11.servicer.ServicerProcessor
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
@Repeatable(WireServices.class)
@Documented
public @interface WireService {

    Class<?>[] values() default {};
}

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
@Documented
@interface WireServices {
    WireService[] value();
}