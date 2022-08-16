package com.austinv11.servicer;


import java.lang.annotation.Annotation;

public interface ServicerRegistration<S> {

    Class<S> serviceType();

    Class<? extends S> implementationType();

}
