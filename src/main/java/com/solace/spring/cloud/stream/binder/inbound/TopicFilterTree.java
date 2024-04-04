package com.solace.spring.cloud.stream.binder.inbound;

import lombok.Data;

import java.util.*;

/**
 * <a href="https://docs.solace.com/Messaging/Wildcard-Charaters-Topic-Subs.htm">Topic Wildcards Logic</a>
 */
public class TopicFilterTree<T> {
    private final TopicNode rootNode = new TopicNode("");

    public void clear() {
        rootNode.clear();
    }

    public Set<T> getMatching(String topic) {
        return new HashSet<>(getMatching(rootNode, split(topic)));
    }

    private List<T> getMatching(TopicNode parent, LinkedList<String> topic) {
        if (topic.isEmpty()) {
            return parent.values;
        }
        String topicPart = topic.pop();
        TopicNode perfectMatch = parent.children.get(topicPart);
        if (perfectMatch != null && parent.childrenWithWildcards.isEmpty()) {
            return getMatching(perfectMatch, topic);
        }
        List<T> result = new ArrayList<>();
        if (perfectMatch != null) {
            result.addAll(getMatching(perfectMatch, new LinkedList<>(topic)));
        }
        for (TopicNode node : parent.childrenWithWildcards) {
            if (node.isMultiLevelWildcard()) {
                result.addAll(node.values);
            }
            if (node.isSingleLevelWildcard()) {
                result.addAll(getMatching(node, new LinkedList<>(topic)));
            }
            if (node.isPrefixedLevelWildcard() && topicPart.startsWith(node.nameWithoutAstrix)) {
                result.addAll(getMatching(node, new LinkedList<>(topic)));
            }
        }
        return result;
    }

    public void addTopic(String topic, T value) {
        LinkedList<String> topicParts = split(topic);
        addTopic(rootNode, topicParts, value);
    }

    private void addTopic(TopicNode parent, LinkedList<String> topicParts, T value) {
        if (topicParts.isEmpty()) {
            parent.getValues().add(value);
            return;
        }
        String topicPart = topicParts.pop();
        TopicNode topicNode = parent.children.computeIfAbsent(topicPart, TopicNode::new);
        if (topicNode.multiLevelWildcard || topicNode.prefixedLevelWildcard || topicNode.singleLevelWildcard) {
            parent.childrenWithWildcards.add(topicNode);
        }
        addTopic(topicNode, topicParts, value);
    }

    private LinkedList<String> split(String topic) {
        char[] c = topic.toCharArray();
        LinkedList<String> result = new LinkedList<>();
        int index = 0;
        for (int i = 0; i < c.length; i++) {
            if (c[i] == '/') {
                result.add(topic.substring(index, i));
                index = i + 1;
            }
        }
        if (index < c.length) {
            result.add(topic.substring(index));
        }
        return result;
    }


    @Data
    private class TopicNode {
        private final String name;
        private final String nameWithoutAstrix;
        private final boolean singleLevelWildcard;
        private final boolean prefixedLevelWildcard;
        private final boolean multiLevelWildcard;

        private TopicNode(String name) {
            this.name = name;
            this.singleLevelWildcard = "*".equals(name);
            this.prefixedLevelWildcard = name.endsWith("*");
            this.multiLevelWildcard = ">".equals(name);
            this.nameWithoutAstrix = name.replaceAll("\\*", "");
        }

        private final Map<String, TopicNode> children = new HashMap<>();
        private final Set<TopicNode> childrenWithWildcards = new HashSet<>();
        private final List<T> values = new ArrayList<>();

        private void clear() {
            this.children.clear();
            this.childrenWithWildcards.clear();
            this.values.clear();
        }
    }
}
