/*
 * Copyright (C) 2018-2022 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.spi;

import java.net.URI;

import com.zengularity.benji.ObjectStorage;

/**
 * Functional interface as a factory to instantiate Object storage.
 */
public interface StorageFactory
    extends java.util.function.BiFunction<Injector, URI, ObjectStorage> {

    /**
     * Returns an `ObjectStorage` instance configured appropriately.
     * Throws IllegalArgumentException if URI is not supported by the factory (e.g. the scheme of the URI is not supported)
     *
     * @param injector the injector to be used to resolve the dependencies
     * @param configurationUri the configuration URI
     */
    public ObjectStorage apply(Injector injector, URI configurationUri)
        throws IllegalArgumentException;
}
