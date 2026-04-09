package com.solace.spring.cloud.stream.binder.provisioning;

import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.properties.SolaceProducerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;

import static org.assertj.core.api.Assertions.assertThat;

class ExpressionContextRootTest {

    @Test
    void testConsumerExpressionContext() {
        SolaceConsumerProperties solaceProps = new SolaceConsumerProperties();
        ExtendedConsumerProperties<SolaceConsumerProperties> extProps = new ExtendedConsumerProperties<>(solaceProps);

        ExpressionContextRoot root = new ExpressionContextRoot("my-group", "my/topic", false, extProps);

        assertThat(root.getGroup()).isEqualTo("my-group");
        assertThat(root.getDestination()).isEqualTo("my/topic");
        assertThat(root.isAnonymous()).isFalse();
        assertThat(root.getProperties()).isNotNull();
    }

    @Test
    void testConsumerExpressionContextAnonymous() {
        SolaceConsumerProperties solaceProps = new SolaceConsumerProperties();
        ExtendedConsumerProperties<SolaceConsumerProperties> extProps = new ExtendedConsumerProperties<>(solaceProps);

        ExpressionContextRoot root = new ExpressionContextRoot("anon-uuid", "my/topic", true, extProps);

        assertThat(root.isAnonymous()).isTrue();
    }

    @Test
    void testProducerExpressionContext() {
        SolaceProducerProperties solaceProps = new SolaceProducerProperties();
        ExtendedProducerProperties<SolaceProducerProperties> extProps = new ExtendedProducerProperties<>(solaceProps);

        ExpressionContextRoot root = new ExpressionContextRoot("group1", "my/topic", extProps);

        assertThat(root.getGroup()).isEqualTo("group1");
        assertThat(root.getDestination()).isEqualTo("my/topic");
        assertThat(root.isAnonymous()).isFalse();
        assertThat(root.getProperties()).isNotNull();
    }
}
