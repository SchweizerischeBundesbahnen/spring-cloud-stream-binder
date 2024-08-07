package com.solace.spring.cloud.stream.binder.test.util;

import java.io.Serializable;
import java.util.Objects;

public class SerializableFoo implements Serializable {
    private final String someVar0;
    private final String someVar1;

    public SerializableFoo(String someVar0, String someVar1) {
        this.someVar0 = someVar0;
        this.someVar1 = someVar1;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SerializableFoo that = (SerializableFoo) o;
        return Objects.equals(someVar0, that.someVar0) &&
                Objects.equals(someVar1, that.someVar1);
    }

    @Override
    public int hashCode() {
        return Objects.hash(someVar0, someVar1);
    }
}
