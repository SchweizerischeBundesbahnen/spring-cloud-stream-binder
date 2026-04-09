package com.solace.spring.cloud.stream.binder.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SharedResourceManagerTest {

    @Test
    void testFirstGetCreatesResource() throws Exception {
        TestSharedResourceManager manager = new TestSharedResourceManager();

        String resource = manager.get("key1");

        assertThat(resource).isEqualTo("resource");
        assertThat(manager.createCount).isEqualTo(1);
    }

    @Test
    void testSecondGetReusesResource() throws Exception {
        TestSharedResourceManager manager = new TestSharedResourceManager();

        String resource1 = manager.get("key1");
        String resource2 = manager.get("key2");

        assertThat(resource1).isSameAs(resource2);
        assertThat(manager.createCount).isEqualTo(1);
    }

    @Test
    void testSameKeyGetReusesResource() throws Exception {
        TestSharedResourceManager manager = new TestSharedResourceManager();

        manager.get("key1");
        manager.get("key1");

        assertThat(manager.createCount).isEqualTo(1);
    }

    @Test
    void testReleaseLastKeyClosesResource() throws Exception {
        TestSharedResourceManager manager = new TestSharedResourceManager();

        manager.get("key1");
        manager.release("key1");

        assertThat(manager.closeCount).isEqualTo(1);
        assertThat(manager.sharedResource).isNull();
    }

    @Test
    void testReleaseNonLastKeyDoesNotCloseResource() throws Exception {
        TestSharedResourceManager manager = new TestSharedResourceManager();

        manager.get("key1");
        manager.get("key2");
        manager.release("key1");

        assertThat(manager.closeCount).isZero();
        assertThat(manager.sharedResource).isNotNull();
    }

    @Test
    void testReleaseAllKeysClosesResource() throws Exception {
        TestSharedResourceManager manager = new TestSharedResourceManager();

        manager.get("key1");
        manager.get("key2");
        manager.release("key1");
        manager.release("key2");

        assertThat(manager.closeCount).isEqualTo(1);
        assertThat(manager.sharedResource).isNull();
    }

    @Test
    void testReleaseUnknownKeyIsNoOp() throws Exception {
        TestSharedResourceManager manager = new TestSharedResourceManager();
        manager.get("key1");

        manager.release("unknown");

        assertThat(manager.closeCount).isZero();
        assertThat(manager.sharedResource).isNotNull();
    }

    @Test
    void testReleaseWithNoRegisteredKeysIsNoOp() {
        TestSharedResourceManager manager = new TestSharedResourceManager();
        manager.release("key1");
        assertThat(manager.closeCount).isZero();
    }

    @Test
    void testGetAfterFullReleaseCreatesNewResource() throws Exception {
        TestSharedResourceManager manager = new TestSharedResourceManager();

        manager.get("key1");
        manager.release("key1");

        String resource = manager.get("key2");

        assertThat(resource).isEqualTo("resource");
        assertThat(manager.createCount).isEqualTo(2);
    }

    @Test
    void testCreateExceptionPropagates() {
        SharedResourceManager<String> manager = new SharedResourceManager<>("test") {
            @Override
            String create() throws Exception {
                throw new RuntimeException("create failed");
            }

            @Override
            void close() {
            }
        };

        assertThatThrownBy(() -> manager.get("key1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("create failed");
    }

    private static class TestSharedResourceManager extends SharedResourceManager<String> {
        int createCount = 0;
        int closeCount = 0;

        TestSharedResourceManager() {
            super("test-resource");
        }

        @Override
        String create() {
            createCount++;
            return "resource";
        }

        @Override
        void close() {
            closeCount++;
        }
    }
}
