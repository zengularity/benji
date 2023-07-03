/*
 * Copyright (C) 2018-2023 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package play.modules.benji;

import java.io.Serializable;
import java.lang.annotation.Annotation;

// See https://issues.scala-lang.org/browse/SI-8778 for why this is implemented in Java
public class NamedStorageImpl implements NamedStorage, Serializable {

    private final String value;

    public NamedStorageImpl(String value) {
        if (value == null) throw new IllegalArgumentException();

        this.value = value;
    }

    public String value() {
        return this.value;
    }

    public int hashCode() {
        // This is specified in java.lang.Annotation.
        return (127 * "value".hashCode()) ^ value.hashCode();
    }

    public boolean equals(Object o) {
        if (!(o instanceof NamedStorage)) {
            return false;
        }

        NamedStorage other = (NamedStorage) o;
        return value.equals(other.value());
    }

    public String toString() {
        return "@" + NamedStorage.class.getName() + "(value=" + value + ")";
    }

    public Class<? extends Annotation> annotationType() {
        return NamedStorage.class;
    }

    private static final long serialVersionUID = 0;
}
