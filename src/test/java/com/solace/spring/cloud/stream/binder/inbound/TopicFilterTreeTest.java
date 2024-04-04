package com.solace.spring.cloud.stream.binder.inbound;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TopicFilterTreeTest {

    private TopicFilterTree<String> topicFilterTree;

    @BeforeEach
    void setUp() {
        topicFilterTree = new TopicFilterTree<>();
    }

    @Test
    void shouldMatchExactTopic() {
        topicFilterTree.addTopic("foo/bar", "value");

        Set<String> matchingValues = topicFilterTree.getMatching("foo/bar");

        assertThat(matchingValues).containsExactly("value");
    }

    @Test
    void shouldMatchSingleLevelWildcard() {
        topicFilterTree.addTopic("foo/*", "value");

        Set<String> matchingValues = topicFilterTree.getMatching("foo/bar");

        assertThat(matchingValues).containsExactly("value");
    }

    @Test
    void shouldMatchPrefixedLevelWildcard() {
        topicFilterTree.addTopic("foo/ba*/bar", "value");
        Set<String> matchingValues = topicFilterTree.getMatching("foo/baz/bar");
        assertThat(matchingValues).containsExactly("value");
    }

    @Test
    void shouldMatchMultiLevelWildcard() {
        topicFilterTree.addTopic("foo/>", "value");
        Set<String> matchingValues = topicFilterTree.getMatching("foo/bar/baz");
        assertThat(matchingValues).containsExactly("value");
    }

    @Test
    void shouldMatchMultipleTopics() {
        topicFilterTree.addTopic("foo/bar", "value1");
        topicFilterTree.addTopic("foo/*", "value2");
        topicFilterTree.addTopic("foo/*/bar", "value3");
        topicFilterTree.addTopic("foo/>", "value4");
        Set<String> matchingValues = topicFilterTree.getMatching("foo/bar/bar");
        assertThat(matchingValues).containsExactlyInAnyOrder("value3", "value4");
    }

    @Test
    void shouldNotMatchUnrelatedTopic() {
        topicFilterTree.addTopic("foo/bar", "value");
        Set<String> matchingValues = topicFilterTree.getMatching("foo/ba");
        assertThat(matchingValues).isEmpty();
    }

    @Test
    void shouldClearAllTopics() {
        topicFilterTree.addTopic("foo/bar", "value");
        topicFilterTree.clear();
        Set<String> matchingValues = topicFilterTree.getMatching("foo/bar");
        assertThat(matchingValues).isEmpty();
    }

    @Test
    void shouldHandleEmptyTopic() {
        topicFilterTree.addTopic("", "value");
        Set<String> matchingValues = topicFilterTree.getMatching("");
        assertThat(matchingValues).containsExactly("value");
    }

    @Test
    void shouldHandleTopicWithTrailingSlash() {
        topicFilterTree.addTopic("foo/bar/", "value");
        Set<String> matchingValues = topicFilterTree.getMatching("foo/bar/");
        assertThat(matchingValues).containsExactly("value");
    }

    @Test
    void shouldHandleTopicWithMultipleWildcards() {
        topicFilterTree.addTopic("foo/*/bar/*", "value");
        Set<String> matchingValues = topicFilterTree.getMatching("foo/baz/bar/qux");
        assertThat(matchingValues).containsExactly("value");
    }

    @Test
    void shouldHandleTopicWithMultipleLevelsOfWildcards() {
        topicFilterTree.addTopic("foo/*/bar/*/*", "value");
        Set<String> matchingValues = topicFilterTree.getMatching("foo/baz/bar/qux/fiz");
        assertThat(matchingValues).containsExactly("value");
    }

    @Test
    void shouldHandleTopicWithMultipleWildcardsAndValues() {
        topicFilterTree.addTopic("foo/*/bar/*", "value1");
        topicFilterTree.addTopic("foo/baz/bar/*", "value2");
        topicFilterTree.addTopic("foo/baz/bar/qux", "value3");

        Set<String> matchingValues = topicFilterTree.getMatching("foo/baz/bar/qux");

        assertThat(matchingValues).containsExactlyInAnyOrder("value1", "value2", "value3");
    }

    @Test
    void shouldHandleTopicWithMultipleWildcardsAndValuesAndEmptyTopic() {
        topicFilterTree.addTopic("foo/*/bar/*", "value1");
        topicFilterTree.addTopic("foo/baz/bar/*", "value2");
        topicFilterTree.addTopic("foo/baz/bar/qux", "value3");
        topicFilterTree.addTopic("", "value4");

        Set<String> matchingValues = topicFilterTree.getMatching("foo/baz/bar/qux");

        assertThat(matchingValues).containsExactlyInAnyOrder("value1", "value2", "value3");
    }

    @Test
    void shouldHandleTopicWithMultipleWildcardsAndValuesAndTrailingSlash() {
        topicFilterTree.addTopic("foo/*/bar/*", "value1");
        topicFilterTree.addTopic("foo/baz/bar/*", "value2");
        topicFilterTree.addTopic("foo/baz/bar/qux", "value3");
        topicFilterTree.addTopic("foo/bar/", "value4");

        Set<String> matchingValues = topicFilterTree.getMatching("foo/bar/");

        assertThat(matchingValues).containsExactlyInAnyOrder("value4");
    }

    @Test
    void shouldHandleTopicWithMultipleWildcardsAndValuesAndMultipleLevelsOfWildcards() {
        topicFilterTree.addTopic("foo/*/bar/*/*", "value1");
        topicFilterTree.addTopic("foo/baz/bar/*", "value2");
        topicFilterTree.addTopic("foo/baz/bar/qux", "value3");
        topicFilterTree.addTopic("foo/*/bar/*/*/*", "value4");

        Set<String> matchingValues = topicFilterTree.getMatching("foo/baz/bar/qux/fiz");

        assertThat(matchingValues).containsExactlyInAnyOrder("value1");
    }

    @Test
    void shouldHandleTopicWithMultipleWildcardsAndWildcardPrefix() {
        topicFilterTree.addTopic("foo/*/bar/*/*", "value1");
        topicFilterTree.addTopic("foo/b*/bar/*/>", "value2");
        topicFilterTree.addTopic("foo/ba*/bar/*/>", "value3");
        topicFilterTree.addTopic("foo/bar*/bar/*/>", "value3.1");
        topicFilterTree.addTopic("*/*/*/*/*", "value4");
        topicFilterTree.addTopic(">", "value5");
        topicFilterTree.addTopic("*/*/*/*/*/>", "value6");

        Set<String> matchingValues = topicFilterTree.getMatching("foo/baz/bar/qux/fiz");

        assertThat(matchingValues).containsExactlyInAnyOrder("value1", "value2", "value3", "value4", "value5");
    }

    @Test
    void shouldHandleWildcardsAsLiteralsInbetween() {
        topicFilterTree.addTopic("foo/b*r", "value1");
        topicFilterTree.addTopic("foo/*r", "value2");
        topicFilterTree.addTopic("foo/*bar", "value3");

        assertThat(topicFilterTree.getMatching("foo/bar")).isEmpty();
        assertThat(topicFilterTree.getMatching("foo/r")).isEmpty();
        assertThat(topicFilterTree.getMatching("foo/bbar")).isEmpty();
        assertThat(topicFilterTree.getMatching("foo/br")).isEmpty();
        assertThat(topicFilterTree.getMatching("foo/b*r")).containsExactlyInAnyOrder("value1");
        assertThat(topicFilterTree.getMatching("foo/*r")).containsExactlyInAnyOrder("value2");
        assertThat(topicFilterTree.getMatching("foo/*bar")).containsExactlyInAnyOrder("value3");
    }

    @Test
    void isMatchingTopicFilter_matchingTopicsA() {
        topicFilterTree.addTopic("vehicle/car/fiat", "value1");
        topicFilterTree.addTopic("animals/domestic/*", "value2");
        topicFilterTree.addTopic("animals/wild/wulf/wearwulf", "value3");

        assertThat(topicFilterTree.getMatching("vehicle/car/fiat")).containsExactlyInAnyOrder("value1");
        assertThat(topicFilterTree.getMatching("animals/domestic/cat")).containsExactlyInAnyOrder("value2");
        assertThat(topicFilterTree.getMatching("animals/wild/wulf/wearwulf")).containsExactlyInAnyOrder("value3");
    }

    @Test
    void isMatchingTopicFilter_matchingTopicsB() {
        topicFilterTree.addTopic("animals/*/cat", "value1");
        assertThat(topicFilterTree.getMatching("animals/domestic/cat")).containsExactlyInAnyOrder("value1");
    }

    @Test
    void isMatchingTopicFilter_matchingTopicsC() {
        topicFilterTree.addTopic("animals/domestic/dog*", "value1");
        assertThat(topicFilterTree.getMatching("animals/domestic/dog")).containsExactlyInAnyOrder("value1");
        assertThat(topicFilterTree.getMatching("animals/domestic/doggy")).containsExactlyInAnyOrder("value1");
    }

    @Test
    void isMatchingTopicFilter_matchingTopicsD() {
        topicFilterTree.addTopic("animals/>", "value1");
        topicFilterTree.addTopic("vehicle/*/fiat/>", "value2");
        assertThat(topicFilterTree.getMatching("animals/domestic/cat")).containsExactlyInAnyOrder("value1");
        assertThat(topicFilterTree.getMatching("vehicle/car/fiat/500")).containsExactlyInAnyOrder("value2");
    }

    @Test
    void isMatchingTopicFilter_notMatchingTopicsA() {
        topicFilterTree.addTopic("animals/*", "value1");
        assertThat(topicFilterTree.getMatching("animals/domestic/cat")).isEmpty();
    }

    @Test
    void isMatchingTopicFilter_notMatchingTopicsB() {
        topicFilterTree.addTopic("animals/domestic", "value1");
        assertThat(topicFilterTree.getMatching("animals/domestic/cat")).isEmpty();
    }

    @Test
    void isMatchingTopicFilter_notMatchingTopicsC() {
        topicFilterTree.addTopic("animals/domestic/cat", "value1");
        assertThat(topicFilterTree.getMatching("animals/domestic")).isEmpty();
    }

    @Test
    void isMatchingTopicFilter_notMatchingTopicsD() {
        topicFilterTree.addTopic("animals/domestic/*", "value1");
        assertThat(topicFilterTree.getMatching("animals/domestic")).isEmpty();
    }

    @Test
    void perf() {
        topicFilterTree.addTopic("tms/outpost/apionlytraffic/i/v2/e2e/ch5/latency-roundtrip-request", "value1");
        topicFilterTree.addTopic("tms/outpost/apionlytraffic/i/v2/e2e/ch5/latency-roundtrip-reply/sink/halon-vrcs", "value2");
        topicFilterTree.addTopic("tms/outpost/apionlytraffic/i/v2/e2e/ch5/latency-roundtrip-reply/sink/halon-fooo", "value3");
        topicFilterTree.addTopic("tms/outpost/apionlytraffic/i/v2/e2e/ch5/latency-roundtrip-reply/sink/halon-baaa", "value4");
        topicFilterTree.addTopic("tms/outpost/apionlytraffic/i/v2/e2e/ch5/latency-roundtrip-reply/sink/halon-grrr", "value5");
        topicFilterTree.addTopic("tms/outpost/apionlytraffic/i/v2/e2e/ch5/latency-roundtrip-reply/sink/halon-daaa", "value6");
        topicFilterTree.addTopic("tms/outpost/apionlytraffic/i/v2/e2e/ch5/latency-roundtrip-reply/sink/halon-tzz", "value7");
        topicFilterTree.addTopic("tms/outpost/apionlytraffic/i/v2/e2e/ch5/latency-roundtrip-reply/sink/halon-dsfsd", "value8");
        topicFilterTree.addTopic("tms/monitoring/monalesy/i/v1/metric/*/tms/outpost/testing/>", "value9");

        //preheat
        long iterations = 10_000_000L;
        for (int i = 0; i < iterations; i++) {
            topicFilterTree.getMatching("tms/outpost/apionlytraffic/i/v2/e2e/ch5/latency-roundtrip-request/" + i);
        }
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            topicFilterTree.getMatching("tms/outpost/apionlytraffic/i/v2/e2e/ch5/latency-roundtrip-request/" + i);
        }
        long end = System.nanoTime();
        long timeMs = (end - start) / (1000 * 1000);
        System.out.println("Runtime: " + timeMs + "ms");
        System.out.println("Runtime per item: " + ((double) (timeMs) / iterations) + "ms");
    }

    @Disabled("takes to long, for manual performance testing")
    @Test
    void perf2() {
        for (int i = 0; i < 4; i++) {
            topicFilterTree.addTopic("l1/l2/l3/env/v2/e2e/ch5/latency-roundtrip-request", "value1_" + i);
            topicFilterTree.addTopic("l1/l2/l3/*/v2/e2e/ch5/latency-roundtrip-request", "value2_" + i);
            topicFilterTree.addTopic("l1/l2/l3/*/v2/e2e/ch5/latency-roundtrip-request/>", "value2_" + i);
            topicFilterTree.addTopic("l1/l2/l3/en*/v2/e2e/ch5/>", "value2_" + i);
        }

        //preheat
        long iterations = 1_000_000L;
        for (int i = 0; i < iterations; i++) {
            Set<String> matching = topicFilterTree.getMatching("l1/l2/l3/env/v2/e2e/ch5/latency-roundtrip-request/" + i);
            assertThat(matching).isNotEmpty();
        }
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            Set<String> matching = topicFilterTree.getMatching("l1/l2/l3/env/v2/e2e/ch5/latency-roundtrip-request/" + i);
            assertThat(matching).isNotEmpty();
        }
        long end = System.nanoTime();
        long timeMs = (end - start) / (1000 * 1000);
        System.out.println("Runtime: " + timeMs + "ms");
        System.out.println("Runtime per item: " + ((double) (timeMs) / iterations) + "ms");
    }
}