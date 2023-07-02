/*
 * Copyright (C) 2018-2023 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.spi;

/**
 * Dependency injection container.
 */
public interface Injector {
    public <T> T instanceOf(Class<T> cls);
}
