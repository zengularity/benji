package com.zengularity.benji.spi;

import java.net.URI;

import com.zengularity.benji.ObjectStorage;

public interface StorageFactory
    extends java.util.function.Function<URI, ObjectStorage> {

    /**
     * Returns an `ObjectStorage` instance configured appropriately.
     *
     * @param configurationUri the configuration URI
     * @throws IllegalArgumentException if URI is not supported by the factory (e.g. the scheme of the URI is not supported)
     */
    public ObjectStorage apply(URI configurationUri)
        throws IllegalArgumentException;
}
