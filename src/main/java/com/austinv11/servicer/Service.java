package com.austinv11.servicer;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Simple marker annotation denoting that this interface is loaded via a {@link java.util.ServiceLoader} so it should
 * so implementations should be registered appropriately.
 *
 * @see com.austinv11.servicer.WireService
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
public @interface Service {
}