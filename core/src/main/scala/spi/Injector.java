package com.zengularity.benji.spi;

/**
 * Dependency injection container.
 */
public interface Injector {
    public <T> T instanceOf(Class<T> cls);
}
