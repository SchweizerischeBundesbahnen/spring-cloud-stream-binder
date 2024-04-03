package com.solace.spring.cloud.stream.binder.util;

import com.solacesystems.jcsmp.BytesXMLMessage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.stream.Collectors;

public class NonPersistedSubscriptionLoadBalancer {
    private final BlockingQueue<BytesXMLMessage> blockingQueue = new SynchronousQueue<>();
    private final Set<String[]> topicFilter;

    public NonPersistedSubscriptionLoadBalancer(Set<String> topicFilter) {
        this.topicFilter = topicFilter.stream()
                .map(topic -> topic.split("/"))
                .collect(Collectors.toSet());
    }

    public void process(BytesXMLMessage msg) throws InterruptedException {
        if (isMatchingTopicFilter(msg.getDestination().getName())) {
            blockingQueue.put(msg);
        }
    }

    public boolean isMatchingTopicFilter(String topic) {
        String[] topicParts = topic.split("/");
        List<String[]> filterCandidates = new ArrayList<>(topicFilter);


        for (int partId = 0; partId < topicParts.length; partId++) {
            String topicPart = topicParts[partId];

            Iterator<String[]> candidateIterator = filterCandidates.iterator();
            while (candidateIterator.hasNext()) {
                String[] filter = candidateIterator.next();
                if ((filter.length - 1) < partId) {
                    candidateIterator.remove();
                    continue;
                }

                if (">".equals(filter[partId])) {
                    return true;
                }

                boolean isOneLevelWildcard = "*".equals(filter[partId]);
                boolean isFullFieldMatch = topicPart.equals(filter[partId]);
                boolean isHalfWildcardMatch = filter[partId].endsWith("*") && topicPart.startsWith(filter[partId].substring(0, filter[partId].length() - 1));

                if (!isOneLevelWildcard && !isFullFieldMatch && !isHalfWildcardMatch) {
                    candidateIterator.remove();
                    continue;
                }

                if (partId == (topicParts.length - 1) && topicParts.length == filter.length) {
                    return true;
                }
            }
        }

        return false;
    }

    public BlockingQueue<BytesXMLMessage> getBlockingQueue() {
        return blockingQueue;
    }
}
