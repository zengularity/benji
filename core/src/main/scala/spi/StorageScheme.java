package com.zengularity.benji.spi;

/**
 * Each implementation must be registered in a 
 * `META-INF/services/com.zengularity.benji.spi.StorageScheme` resource.
 */
public interface StorageScheme {
    /** 
     * The storage scheme (e.g. `s3`)
     */
    public String scheme();

    /**
     * The class of the provider that can be resolved 
     * using a dependency aware context.
     */
    public Class<? extends StorageFactory> factoryClass();
}
