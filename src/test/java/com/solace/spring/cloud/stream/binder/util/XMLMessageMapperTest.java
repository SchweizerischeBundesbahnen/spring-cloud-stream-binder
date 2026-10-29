package com.solace.spring.cloud.stream.binder.util;

import com.solace.spring.cloud.stream.binder.messaging.*;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solace.spring.cloud.stream.binder.test.util.SerializableFoo;
import com.solacesystems.jcsmp.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.assertj.core.api.MapAssert;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.function.ThrowingSupplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junitpioneer.jupiter.cartesian.CartesianArgumentsSource;
import org.junitpioneer.jupiter.cartesian.CartesianParameterArgumentsProvider;
import org.junitpioneer.jupiter.cartesian.CartesianTest;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.stream.binder.BinderHeaders;
import org.springframework.integration.IntegrationMessageHeaderAccessor;
import org.springframework.integration.StaticMessageHeaderAccessor;
import org.springframework.integration.acks.AcknowledgmentCallback;
import org.springframework.integration.support.DefaultMessageBuilderFactory;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.SerializationUtils;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@ExtendWith(MockitoExtension.class)
public class XMLMessageMapperTest {
    private static final Log logger = LogFactory.getLog(XMLMessageMapperTest.class);
    private static final Set<String> JMS_INVALID_HEADER_NAMES = new HashSet<>(Arrays.asList("~ab;c", "NULL",
            "TRUE", "FALSE", "NOT", "AND", "OR", "BETWEEN", "LIKE", "IN", "IS", "ESCAPE", "JMSX_abc", "JMS_abc"));

    static {
        assertTrue(JMS_INVALID_HEADER_NAMES.stream().anyMatch(h -> !Character.isJavaIdentifierStart(h.charAt(0))));
        assertTrue(JMS_INVALID_HEADER_NAMES.stream().map(CharSequence::chars)
                .anyMatch(c -> c.skip(1).anyMatch(c1 -> !Character.isJavaIdentifierPart(c1))));
        assertTrue(JMS_INVALID_HEADER_NAMES.stream().anyMatch(h -> h.startsWith("JMSX")));
        assertTrue(JMS_INVALID_HEADER_NAMES.stream().anyMatch(h -> h.startsWith("JMS_")));
    }

    @Spy
    private final XMLMessageMapper xmlMessageMapper = new XMLMessageMapper();

    private static Stream<Arguments> springPayloadTypeProviders() {
        return Stream.of(
                Arguments.of(Named.of("BYTE_ARRAY", new SpringMessageTypeProvider<>(
                        BytesMessage.class,
                        MimeTypeUtils.APPLICATION_OCTET_STREAM,
                        () -> "testPayload".getBytes(StandardCharsets.UTF_8),
                        BytesMessage::getData))),
                Arguments.of(Named.of("STRING", new SpringMessageTypeProvider<>(
                        TextMessage.class,
                        MimeTypeUtils.TEXT_PLAIN,
                        () -> "testPayload",
                        TextMessage::getText))),
                Arguments.of(Named.of("SDT_STREAM", new SpringMessageTypeProvider<>(
                        StreamMessage.class,
                        new MimeType("application", "x-java-serialized-object"),
                        () -> {
                            SDTStream sdtStream = JCSMPFactory.onlyInstance().createStream();
                            sdtStream.writeBoolean(true);
                            sdtStream.writeCharacter('s');
                            sdtStream.writeMap(JCSMPFactory.onlyInstance().createMap());
                            sdtStream.writeStream(JCSMPFactory.onlyInstance().createStream());
                            return sdtStream;
                        },
                        StreamMessage::getStream))),
                Arguments.of(Named.of("SDT_MAP", new SpringMessageTypeProvider<>(
                        MapMessage.class,
                        new MimeType("application", "x-java-serialized-object"),
                        () -> {
                            SDTMap sdtMap = JCSMPFactory.onlyInstance().createMap();
                            sdtMap.putBoolean("a", true);
                            sdtMap.putCharacter("b", 's');
                            sdtMap.putMap("c", JCSMPFactory.onlyInstance().createMap());
                            sdtMap.putStream("d", JCSMPFactory.onlyInstance().createStream());
                            return sdtMap;
                        },
                        MapMessage::getMap)))
        );
    }

    private static Stream<Arguments> xmlMessageTypeProviders() {
        return Stream.of(
                Arguments.of(Named.of("BYTE_ARRAY", new XmlMessageTypeProvider<>(
                        BytesMessage.class,
                        MimeTypeUtils.APPLICATION_OCTET_STREAM,
                        () -> RandomStringUtils.randomAlphabetic(10).getBytes(StandardCharsets.UTF_8),
                        BytesMessage::setData))),
                Arguments.of(Named.of("STRING", new XmlMessageTypeProvider<>(
                        TextMessage.class,
                        MimeTypeUtils.TEXT_PLAIN,
                        () -> RandomStringUtils.randomAlphabetic(10),
                        TextMessage::setText))),
                Arguments.of(Named.of("SDT_MAP", new XmlMessageTypeProvider<>(
                        MapMessage.class,
                        new MimeType("application", "x-java-serialized-object"),
                        () -> {
                            SDTMap expectedPayload = JCSMPFactory.onlyInstance().createMap();
                            expectedPayload.putBoolean("a", true);
                            expectedPayload.putCharacter("b", 's');
                            expectedPayload.putMap("c", JCSMPFactory.onlyInstance().createMap());
                            expectedPayload.putStream("d", JCSMPFactory.onlyInstance().createStream());
                            return expectedPayload;
                        },
                        MapMessage::setMap))),
                Arguments.of(Named.of("SDT_STREAM", new XmlMessageTypeProvider<>(
                        StreamMessage.class,
                        new MimeType("application", "x-java-serialized-object"),
                        () -> {
                            SDTStream expectedPayload = JCSMPFactory.onlyInstance().createStream();
                            expectedPayload.writeBoolean(true);
                            expectedPayload.writeCharacter('s');
                            expectedPayload.writeMap(JCSMPFactory.onlyInstance().createMap());
                            expectedPayload.writeStream(JCSMPFactory.onlyInstance().createStream());
                            return expectedPayload;
                        },
                        StreamMessage::setStream))),
                Arguments.of(Named.of("XML_CONTENT", new XmlMessageTypeProvider<>(
                        XMLContentMessage.class,
                        MimeTypeUtils.TEXT_XML,
                        () -> "<a><b>testPayload</b><c>testPayload2</c></a>",
                        XMLContentMessage::setXMLContent))));
    }

    @ParameterizedTest
    @MethodSource("springPayloadTypeProviders")
    <T, MT extends XMLMessage> void testMapSpringMessageToXMLMessage(
            SpringMessageTypeProvider<T, MT> springMessageTypeProvider) {
        Message<?> testSpringMessage = new DefaultMessageBuilderFactory()
                .withPayload(springMessageTypeProvider.createPayload())
                .setHeader("test-header-1", "test-header-val-1")
                .setHeader("test-header-2", "test-header-val-2")
                .setHeader(MessageHeaders.CONTENT_TYPE, springMessageTypeProvider.mimeType().toString())
                .build();

        Assertions.assertThat(xmlMessageMapper.map(testSpringMessage, null, false, DeliveryMode.PERSISTENT))
                .asInstanceOf(InstanceOfAssertFactories.type(springMessageTypeProvider.expectedXmlMessageType()))
                .satisfies(
                        m -> Assertions.assertThat(springMessageTypeProvider.extractSmfPayload(m))
                                .isEqualTo(testSpringMessage.getPayload()),
                        m -> validateXMLProperties(m, testSpringMessage)
                );
    }

    @Test
    void testMapSpringMessageToXMLMessage_WriteSolaceProperties() throws Exception {
        MessageBuilder<?> messageBuilder = new DefaultMessageBuilderFactory()
                .withPayload("")
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE);

        Set<Map.Entry<String, ? extends HeaderMeta<?>>> writeableHeaders = Stream.of(
                        SolaceHeaderMeta.META.entrySet().stream(),
                        SolaceBinderHeaderMeta.META.entrySet().stream())
                .flatMap(h -> h)
                .filter(h -> h.getValue().isWritable())
                .collect(Collectors.toSet());
        assertNotEquals(0, writeableHeaders.size(), "Test header set was empty");
        long sharedMessageLifetime = ThreadLocalRandom.current().nextLong(10000);

        for (Map.Entry<String, ? extends HeaderMeta<?>> header : writeableHeaders) {
            messageBuilder.setHeader(header.getKey(), Objects.requireNonNull(switch (header.getKey()) {
                case SolaceHeaders.APPLICATION_MESSAGE_ID,
                     SolaceHeaders.APPLICATION_MESSAGE_TYPE,
                     SolaceHeaders.CORRELATION_ID,
                     SolaceHeaders.HTTP_CONTENT_ENCODING,
                     SolaceHeaders.SENDER_ID,
                     SolaceBinderHeaders.TARGET_DESTINATION_TYPE -> RandomStringUtils.randomAlphanumeric(10);
                case SolaceHeaders.DMQ_ELIGIBLE -> !(Boolean) ((SolaceHeaderMeta<?>) header.getValue()).getDefaultValueOverride();
                case SolaceHeaders.IS_REPLY -> true; //The opposite of what a Solace message defaults to
                case SolaceHeaders.EXPIRATION,
                     SolaceHeaders.SENDER_TIMESTAMP,
                     SolaceHeaders.SEQUENCE_NUMBER,
                     SolaceHeaders.TIME_TO_LIVE -> switch (header.getKey()) {
                    case SolaceHeaders.EXPIRATION, SolaceHeaders.TIME_TO_LIVE -> sharedMessageLifetime;
                    default -> (long) ThreadLocalRandom.current().nextInt(10000);
                };
                case SolaceHeaders.PRIORITY -> ThreadLocalRandom.current().nextInt(255);
                case SolaceHeaders.REPLY_TO -> JCSMPFactory.onlyInstance().createQueue(RandomStringUtils.randomAlphanumeric(10));
                case SolaceHeaders.USER_DATA -> RandomStringUtils.randomAlphanumeric(10).getBytes();
                case SolaceBinderHeaders.CONFIRM_CORRELATION -> new CorrelationData();
                case SolaceBinderHeaders.PARTITION_KEY -> {
                    // This value is overwritten by binder-defined partition key header
                    messageBuilder.setHeader(XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY,
                            RandomStringUtils.randomAlphanumeric(10));
                    yield RandomStringUtils.randomAlphanumeric(10);
                }
                case SolaceBinderHeaders.LARGE_MESSAGE_SUPPORT -> true;
                case SolaceBinderHeaders.CHUNK_ID -> 123L;
                case SolaceBinderHeaders.CHUNK_COUNT -> 2;
                case SolaceBinderHeaders.CHUNK_INDEX -> 0;
                default -> {
                    fail(String.format("no test for header %s", header.getKey()));
                    yield null;
                }
            }));
        }

        Message<?> testSpringMessage = messageBuilder.build();
        XMLMessage xmlMessage = xmlMessageMapper.map(testSpringMessage, null, false, DeliveryMode.PERSISTENT);

        for (Map.Entry<String, ? extends HeaderMeta<?>> header : writeableHeaders) {
            Object expectedValue = testSpringMessage.getHeaders().get(header.getKey());
            switch (header.getKey()) {
                case SolaceHeaders.APPLICATION_MESSAGE_ID -> assertEquals(expectedValue, xmlMessage.getApplicationMessageId());
                case SolaceHeaders.APPLICATION_MESSAGE_TYPE -> assertEquals(expectedValue, xmlMessage.getApplicationMessageType());
                case SolaceHeaders.CORRELATION_ID -> assertEquals(expectedValue, xmlMessage.getCorrelationId());
                case SolaceHeaders.DMQ_ELIGIBLE -> assertEquals(expectedValue, xmlMessage.isDMQEligible());
                case SolaceHeaders.EXPIRATION -> assertEquals(expectedValue, xmlMessage.getExpiration());
                case SolaceHeaders.IS_REPLY -> assertEquals(expectedValue, xmlMessage.isReplyMessage());
                case SolaceHeaders.HTTP_CONTENT_ENCODING -> assertEquals(expectedValue, xmlMessage.getHTTPContentEncoding());
                case SolaceHeaders.PRIORITY -> assertEquals(expectedValue, xmlMessage.getPriority());
                case SolaceHeaders.REPLY_TO -> assertEquals(expectedValue, xmlMessage.getReplyTo());
                case SolaceHeaders.SENDER_ID -> assertEquals(expectedValue, xmlMessage.getSenderId());
                case SolaceHeaders.SENDER_TIMESTAMP -> assertEquals(expectedValue, xmlMessage.getSenderTimestamp());
                case SolaceHeaders.SEQUENCE_NUMBER -> assertEquals(expectedValue, xmlMessage.getSequenceNumber());
                case SolaceHeaders.TIME_TO_LIVE -> assertEquals(expectedValue, xmlMessage.getTimeToLive());
                case SolaceHeaders.USER_DATA -> assertEquals(expectedValue, xmlMessage.getUserData());
                case SolaceBinderHeaders.PARTITION_KEY -> assertEquals(expectedValue, xmlMessage.getProperties()
                        .getString(XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY));
                case SolaceBinderHeaders.CONFIRM_CORRELATION,
                     SolaceBinderHeaders.TARGET_DESTINATION_TYPE ->
                    // These Spring headers aren't ever reflected in the SMF message
                        assertNull(xmlMessage.getProperties().get(header.getKey()));
                case SolaceBinderHeaders.LARGE_MESSAGE_SUPPORT -> assertNull(xmlMessage.getProperties().get(header.getKey()));
                case SolaceBinderHeaders.CHUNK_ID -> assertEquals(123L, xmlMessage.getProperties().get(header.getKey()));
                case SolaceBinderHeaders.CHUNK_COUNT -> assertEquals(2, xmlMessage.getProperties().get(header.getKey()));
                case SolaceBinderHeaders.CHUNK_INDEX -> assertEquals(0, xmlMessage.getProperties().get(header.getKey()));
                default -> fail(String.format("no test for header %s", header.getKey()));
            }
        }

        validateXMLProperties(xmlMessage, testSpringMessage);
    }

    @Test
    void testMapSpringMessageToXMLMessage_NonWriteableSolaceProperties() throws Exception {
        MessageBuilder<?> messageBuilder = new DefaultMessageBuilderFactory()
                .withPayload("")
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE);

        Set<Map.Entry<String, ? extends HeaderMeta<?>>> nonWriteableHeaders = Stream.of(
                        SolaceHeaderMeta.META.entrySet().stream(),
                        SolaceBinderHeaderMeta.META.entrySet().stream())
                .flatMap(h -> h)
                .filter(h -> !h.getValue().isWritable())
                .collect(Collectors.toSet());
        assertNotEquals(0, nonWriteableHeaders.size(), "Test header set was empty");

        for (Map.Entry<String, ? extends HeaderMeta<?>> header : nonWriteableHeaders) {
            // Doesn't matter what we set the values to
            messageBuilder.setHeader(header.getKey(), new Object());
        }

        Message<?> testSpringMessage = messageBuilder.build();
        XMLMessage xmlMessage = xmlMessageMapper.map(testSpringMessage, null, false, DeliveryMode.PERSISTENT);

        for (Map.Entry<String, ? extends HeaderMeta<?>> header : nonWriteableHeaders) {
            switch (header.getKey()) {
                case SolaceHeaders.REPLICATION_GROUP_MESSAGE_ID:
                    assertNull(xmlMessage.getReplicationGroupMessageId());
                    break;
                case SolaceHeaders.DELIVERY_COUNT:
                    assertThrows(UnsupportedOperationException.class, xmlMessage::getDeliveryCount);
                    break;
                case SolaceHeaders.DESTINATION:
                    assertNull(xmlMessage.getDestination());
                    break;
                case SolaceHeaders.DISCARD_INDICATION:
                    assertFalse(xmlMessage.getDiscardIndication());
                    break;
                case SolaceHeaders.RECEIVE_TIMESTAMP:
                    assertEquals(0, xmlMessage.getReceiveTimestamp());
                    break;
                case SolaceHeaders.REDELIVERED:
                    assertFalse(xmlMessage.getRedelivered());
                    break;
                case SolaceBinderHeaders.MESSAGE_VERSION:
                    assertEquals(Integer.valueOf(XMLMessageMapper.MESSAGE_VERSION),
                            xmlMessage.getProperties().getInteger(header.getKey()));
                    break;
                case SolaceBinderHeaders.SERIALIZED_HEADERS:
                case SolaceBinderHeaders.SERIALIZED_HEADERS_ENCODING:
                case SolaceBinderHeaders.SERIALIZED_PAYLOAD:
                case SolaceBinderHeaders.CONFIRM_CORRELATION:
                case SolaceBinderHeaders.NULL_PAYLOAD:
                case SolaceBinderHeaders.TARGET_DESTINATION_TYPE:
                case SolaceBinderHeaders.CHUNK_ID:
                case SolaceBinderHeaders.CHUNK_COUNT:
                case SolaceBinderHeaders.CHUNK_INDEX:
                    assertNull(xmlMessage.getProperties().get(header.getKey()));
                    break;
                default:
                    fail(String.format("no test for header %s", header.getKey()));
            }
        }

        validateXMLProperties(xmlMessage, testSpringMessage.getPayload(), testSpringMessage.getHeaders(),
                testSpringMessage.getHeaders()
                        .entrySet()
                        .stream()
                        .filter(h -> nonWriteableHeaders
                                .stream()
                                .map(Map.Entry::getKey)
                                .noneMatch(nonWriteableHeader -> h.getKey().equals(nonWriteableHeader))
                        )
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
        );
    }

    @Test
    void testMapSpringMessageToXMLMessage_WriteUndefinedSolaceHeader() throws Exception {
        String undefinedSolaceHeader = "abc1234";
        Message<?> testSpringMessage = new DefaultMessageBuilderFactory()
                .withPayload("")
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                .setHeader("solace_foo1", undefinedSolaceHeader)
                .build();

        XMLMessage xmlMessage = xmlMessageMapper.map(testSpringMessage, null, false, DeliveryMode.PERSISTENT);

        assertEquals(undefinedSolaceHeader, xmlMessage.getProperties().getString("solace_foo1"));
        validateXMLProperties(xmlMessage, testSpringMessage);
    }

    @Test
    void sendingASerializablePayloadIsRejectedInsteadOfJavaSerialized() {
        Message<?> testSpringMessage = new DefaultMessageBuilderFactory()
                .withPayload(new SerializableFoo("abc", "123"))
                .build();

        SolaceMessageConversionException exception = assertThrows(SolaceMessageConversionException.class,
                () -> xmlMessageMapper.map(testSpringMessage, null, false, DeliveryMode.PERSISTENT));

        Assertions.assertThat(exception).hasMessage(String.format(
                "Invalid payload received. Expected byte[], String, SDTStream, SDTMap. Received: %s",
                SerializableFoo.class.getName()));
    }

    @Test
    void sendingAMimeTypeContentTypeWritesItsStringForm() throws Exception {
        Message<?> testSpringMessage = new DefaultMessageBuilderFactory()
                .withPayload("test")
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON)
                .build();

        XMLMessage xmlMessage = xmlMessageMapper.map(testSpringMessage, null, false, DeliveryMode.PERSISTENT);

        assertEquals(MimeTypeUtils.APPLICATION_JSON_VALUE,
                xmlMessage.getProperties().getString(MessageHeaders.CONTENT_TYPE));
    }

    @Test
    void sendingDoesNotForwardTheDeliveryAttemptOfTheInboundMessage() {
        Message<?> testSpringMessage = new DefaultMessageBuilderFactory()
                .withPayload("test")
                .setHeader(IntegrationMessageHeaderAccessor.DELIVERY_ATTEMPT, new AtomicInteger(3))
                .build();

        XMLMessage xmlMessage = xmlMessageMapper.map(testSpringMessage, null, false, DeliveryMode.PERSISTENT);

        assertFalse(xmlMessage.getProperties().containsKey(IntegrationMessageHeaderAccessor.DELIVERY_ATTEMPT));
    }

    @Test
    void testMapSpringMessageToXMLMessage_OverrideDefaultSolaceProperties() throws Exception {
        Set<Map.Entry<String, ? extends SolaceHeaderMeta<?>>> overriddenWriteableHeaders = SolaceHeaderMeta.META
                .entrySet()
                .stream()
                .filter(h -> h.getValue().isWritable())
                .filter(h -> h.getValue().hasOverriddenDefaultValue())
                .collect(Collectors.toSet());
        assertNotEquals(0, overriddenWriteableHeaders.size(), "Test header set was empty");

        Message<?> testSpringMessage = new DefaultMessageBuilderFactory()
                .withPayload("")
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                .build();
        XMLMessage xmlMessage = xmlMessageMapper.map(testSpringMessage, null, false, DeliveryMode.PERSISTENT);

        for (Map.Entry<String, ? extends HeaderMeta<?>> header : overriddenWriteableHeaders) {
            switch (header.getKey()) {
                case SolaceHeaders.DMQ_ELIGIBLE:
                    assertTrue(xmlMessage.isDMQEligible());
                    break;
                default:
                    fail(String.format("no test for header %s", header.getKey()));
            }
        }

        validateXMLProperties(xmlMessage, testSpringMessage);
    }

    @ParameterizedTest
    @ValueSource(classes = {String.class, MimeType.class})
    void testMapSpringMessageToXMLMessage_contentTypeHeader(
            Class<?> contentTypeClass) {
        Message<?> testSpringMessage = new DefaultMessageBuilderFactory()
                .withPayload("test")
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeType.class.isAssignableFrom(contentTypeClass) ?
                        MimeTypeUtils.TEXT_PLAIN : MimeTypeUtils.TEXT_PLAIN_VALUE)
                .build();
        Assertions.assertThat(xmlMessageMapper.map(testSpringMessage, null, false, DeliveryMode.PERSISTENT))
                .extracting(XMLMessage::getHTTPContentType)
                .isEqualTo(MimeTypeUtils.TEXT_PLAIN_VALUE);
    }

    @Test
    void testFailMapSpringMessageToXMLMessage_InvalidPayload() {
        Message<?> testSpringMessage = new DefaultMessageBuilderFactory().withPayload(new Object()).build();
        assertThrows(SolaceMessageConversionException.class, () -> xmlMessageMapper.map(testSpringMessage,
                null, false, DeliveryMode.PERSISTENT));
    }

    @Test
    void testFailMapSpringMessageToXMLMessage_InvalidHeaderType() {
        Set<Map.Entry<String, ? extends HeaderMeta<?>>> writeableHeaders = Stream.of(
                        SolaceHeaderMeta.META.entrySet().stream(),
                        SolaceBinderHeaderMeta.META.entrySet().stream())
                .flatMap(h -> h)
                .filter(h -> h.getValue().isWritable())
                .filter(h -> h.getValue().getScope().equals(HeaderMeta.Scope.WIRE))
                .collect(Collectors.toSet());
        assertNotEquals(0, writeableHeaders.size(), "Test header set was empty");

        for (Map.Entry<String, ? extends HeaderMeta<?>> header : writeableHeaders) {
            Message<?> testSpringMessage = new DefaultMessageBuilderFactory().withPayload("")
                    .setHeader(header.getKey(), new Object())
                    .build();
            try {
                xmlMessageMapper.map(testSpringMessage, null, false, DeliveryMode.PERSISTENT);
                fail(String.format("Expected message mapping to fail for header %s", header.getKey()));
            } catch (SolaceMessageConversionException e) {
                if (header.getValue() instanceof SolaceHeaderMeta<?>) {
                    assertEquals(e.getMessage(), String.format(
                            "Message %s has an invalid value type for header %s. Expected %s but received %s.",
                            testSpringMessage.getHeaders().getId(), header.getKey(), header.getValue().getType(),
                            Object.class));
                } else {
                    switch (header.getKey()) {
                        case SolaceBinderHeaders.PARTITION_KEY -> Assertions.assertThat(e).rootCause()
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContainingAll(header.getKey(), header.getValue().getType().toString());
                        default -> fail(String.format("no test for header %s", header.getKey()));
                    }
                }
            }
        }
    }

    @Test
    void testFailMapSpringMessageToXMLMessage_ExpirationAndTtlMutuallyExclusive() {
        Message<?> testSpringMessage = new DefaultMessageBuilderFactory()
                .withPayload("test")
                .setHeader(SolaceHeaders.EXPIRATION, 1L)
                .setHeader(SolaceHeaders.TIME_TO_LIVE, 2L)
                .build();

        SolaceMessageConversionException exception = assertThrows(SolaceMessageConversionException.class,
                () -> xmlMessageMapper.map(testSpringMessage, null, false, DeliveryMode.PERSISTENT));

        Assertions.assertThat(exception)
                .hasMessageContaining(SolaceHeaders.EXPIRATION)
                .hasMessageContaining(SolaceHeaders.TIME_TO_LIVE)
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflicting values");
    }

    @Test
    void testMapXMLMessageToErrorXMLMessage() throws Exception {
        SDTMap headers = JCSMPFactory.onlyInstance().createMap();
        headers.putInteger(SolaceBinderHeaders.MESSAGE_VERSION, 1);
        headers.putString("a", "abc");
        TextMessage inputMessage = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
        inputMessage.setText("test-payload");
        inputMessage.setProperties(headers);
        inputMessage.setApplicationMessageId("test");
        inputMessage.setDMQEligible(true);
        inputMessage.setTimeToLive(1L);
        inputMessage.setReadOnly();

        XMLMessage errorMessage = xmlMessageMapper.mapError(inputMessage, new SolaceConsumerProperties());
        assertThat(errorMessage, instanceOf(TextMessage.class));
        assertEquals(inputMessage.getText(), ((TextMessage) errorMessage).getText());
        assertEquals(inputMessage.getProperties(), errorMessage.getProperties());
        assertEquals(inputMessage.getApplicationMessageId(), errorMessage.getApplicationMessageId());
        assertEquals(inputMessage.isDMQEligible(), errorMessage.isDMQEligible());
        assertEquals(inputMessage.getTimeToLive(), errorMessage.getTimeToLive());
        assertFalse(errorMessage.isReadOnly());
    }

    @Test
    void testMapXMLMessageToErrorXMLMessage_WithProperties() {
        TextMessage inputMessage = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
        SolaceConsumerProperties consumerProperties = new SolaceConsumerProperties();
        consumerProperties.setErrorMsgDmqEligible(!inputMessage.isDMQEligible());
        consumerProperties.setErrorMsgTtl(100L);

        XMLMessage xmlMessage = xmlMessageMapper.mapError(inputMessage, consumerProperties);

        assertEquals(consumerProperties.getErrorMsgDmqEligible(), xmlMessage.isDMQEligible());
        assertEquals(consumerProperties.getErrorMsgTtl().longValue(), xmlMessage.getTimeToLive());
    }

    @Test
    void testMapProducerSpringMessageToXMLMessage_WithExcludedHeader() throws SDTException {
        String testPayload = "testPayload";
        List<String> excludedHeaders = Collections.singletonList("io.opentracing.contrib.spring.integration.messaging.OpenTracingChannelInterceptor.SCOPE");
        Message<?> testSpringMessage = new DefaultMessageBuilderFactory().withPayload(testPayload)
                .setHeader(
                        "io.opentracing.contrib.spring.integration.messaging.OpenTracingChannelInterceptor.SCOPE",
                        "any")
                .build();

        XMLMessage xmlMessage = xmlMessageMapper.map(testSpringMessage, excludedHeaders, false, DeliveryMode.PERSISTENT);
        Mockito.verify(xmlMessageMapper).map(testSpringMessage, excludedHeaders, false, DeliveryMode.PERSISTENT);

        assertNull(xmlMessage.getProperties()
                .getMap("io.opentracing.contrib.spring.integration.messaging.OpenTracingChannelInterceptor.SCOPE"));
    }

    @Test
    void testMapProducerSpringMessageToXMLMessage_WithExcludedHeader_ShouldNotMatchPartially() throws SDTException {
        String testPayload = "testPayload";
        List<String> excludedHeaders = Collections.singletonList("io.opentracing.contrib.spring.integration.messaging.OpenTracingChannelInterceptor.SCOPE");
        Message<?> testSpringMessage = new DefaultMessageBuilderFactory().withPayload(testPayload)
                .setHeader(
                        "io.opentracing.contrib.spring.integration.messaging.OpenTracingChannelInterceptor",
                        "any")
                .build();

        XMLMessage xmlMessage = xmlMessageMapper.map(testSpringMessage, excludedHeaders, false, DeliveryMode.PERSISTENT);
        Mockito.verify(xmlMessageMapper).map(testSpringMessage, excludedHeaders, false, DeliveryMode.PERSISTENT);
        assertEquals("any", xmlMessage.getProperties()
                .get("io.opentracing.contrib.spring.integration.messaging.OpenTracingChannelInterceptor"));
    }

    @Test
    void testMapProducerSpringMessageToXMLMessage_WithExcludedHeader_ShouldNotFilterSolaceHeader() throws SDTException {
        MessageBuilder<?> messageBuilder = new DefaultMessageBuilderFactory()
                .withPayload("test")
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                .setHeader(SolaceBinderHeaders.LARGE_MESSAGE_SUPPORT, true)
                .setHeader(SolaceBinderHeaders.CHUNK_ID, 123L)
                .setHeader(SolaceBinderHeaders.CHUNK_COUNT, 2)
                .setHeader(SolaceBinderHeaders.CHUNK_INDEX, 0)
                .setHeader("test-header", "test");

        Set<Map.Entry<String, ? extends HeaderMeta<?>>> writeableHeaders = Stream.of(
                        SolaceHeaderMeta.META.entrySet().stream(),
                        SolaceBinderHeaderMeta.META.entrySet().stream())
                .flatMap(h -> h)
                .filter(h -> h.getValue().isWritable())
                .filter(h -> h.getValue().getScope().equals(HeaderMeta.Scope.WIRE))
                .collect(Collectors.toSet());
        assertNotEquals(0, writeableHeaders.size(), "Test header set was empty");
        long sharedMessageLifetime = ThreadLocalRandom.current().nextLong(10000);

        for (Map.Entry<String, ? extends HeaderMeta<?>> header : writeableHeaders) {
            messageBuilder.setHeader(header.getKey(), Objects.requireNonNull(switch (header.getKey()) {
                case SolaceHeaders.APPLICATION_MESSAGE_ID,
                     SolaceHeaders.APPLICATION_MESSAGE_TYPE,
                     SolaceHeaders.CORRELATION_ID,
                     SolaceHeaders.HTTP_CONTENT_ENCODING,
                     SolaceHeaders.SENDER_ID,
                     SolaceBinderHeaders.PARTITION_KEY -> RandomStringUtils.randomAlphanumeric(10);
                case SolaceBinderHeaders.LARGE_MESSAGE_SUPPORT -> true;
                case SolaceBinderHeaders.CHUNK_ID -> 123L;
                case SolaceBinderHeaders.CHUNK_COUNT -> 2;
                case SolaceBinderHeaders.CHUNK_INDEX -> 0;
                case SolaceHeaders.DMQ_ELIGIBLE -> !(Boolean) ((SolaceHeaderMeta<?>) header.getValue()).getDefaultValueOverride();
                case SolaceHeaders.IS_REPLY -> true; //The opposite of what a Solace message defaults to
                case SolaceHeaders.EXPIRATION,
                     SolaceHeaders.SENDER_TIMESTAMP,
                     SolaceHeaders.SEQUENCE_NUMBER,
                     SolaceHeaders.TIME_TO_LIVE -> switch (header.getKey()) {
                    case SolaceHeaders.EXPIRATION, SolaceHeaders.TIME_TO_LIVE -> sharedMessageLifetime;
                    default -> ThreadLocalRandom.current().nextLong(10000);
                };
                case SolaceHeaders.PRIORITY -> ThreadLocalRandom.current().nextInt(255);
                case SolaceHeaders.REPLY_TO -> JCSMPFactory.onlyInstance().createQueue(RandomStringUtils.randomAlphanumeric(10));
                case SolaceHeaders.USER_DATA -> RandomStringUtils.randomAlphanumeric(10).getBytes();
                default -> {
                    fail(String.format("no test for header %s", header.getKey()));
                    yield null;
                }
            }));
        }

        List<String> excludedHeaders = writeableHeaders.stream()
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        Message<?> testSpringMessage = messageBuilder.build();
        XMLMessage xmlMessage = xmlMessageMapper.map(testSpringMessage, excludedHeaders, false, DeliveryMode.PERSISTENT);

        for (Map.Entry<String, ? extends HeaderMeta<?>> header : writeableHeaders) {
            Object expectedValue = testSpringMessage.getHeaders().get(header.getKey());
            switch (header.getKey()) {
                case SolaceHeaders.APPLICATION_MESSAGE_ID -> assertEquals(expectedValue, xmlMessage.getApplicationMessageId());
                case SolaceHeaders.APPLICATION_MESSAGE_TYPE -> assertEquals(expectedValue, xmlMessage.getApplicationMessageType());
                case SolaceHeaders.CORRELATION_ID -> assertEquals(expectedValue, xmlMessage.getCorrelationId());
                case SolaceHeaders.DMQ_ELIGIBLE -> assertEquals(expectedValue, xmlMessage.isDMQEligible());
                case SolaceHeaders.EXPIRATION -> assertEquals(expectedValue, xmlMessage.getExpiration());
                case SolaceHeaders.HTTP_CONTENT_ENCODING -> assertEquals(expectedValue, xmlMessage.getHTTPContentEncoding());
                case SolaceHeaders.IS_REPLY -> assertEquals(expectedValue, xmlMessage.isReplyMessage());
                case SolaceHeaders.PRIORITY -> assertEquals(expectedValue, xmlMessage.getPriority());
                case SolaceHeaders.REPLY_TO -> assertEquals(expectedValue, xmlMessage.getReplyTo());
                case SolaceHeaders.SENDER_ID -> assertEquals(expectedValue, xmlMessage.getSenderId());
                case SolaceHeaders.SENDER_TIMESTAMP -> assertEquals(expectedValue, xmlMessage.getSenderTimestamp());
                case SolaceHeaders.SEQUENCE_NUMBER -> assertEquals(expectedValue, xmlMessage.getSequenceNumber());
                case SolaceHeaders.TIME_TO_LIVE -> assertEquals(expectedValue, xmlMessage.getTimeToLive());
                case SolaceHeaders.USER_DATA -> assertEquals(expectedValue, xmlMessage.getUserData());
                case SolaceBinderHeaders.PARTITION_KEY -> assertEquals(expectedValue, xmlMessage.getProperties()
                        .getString(XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY));
                case SolaceBinderHeaders.CHUNK_ID -> assertEquals(expectedValue, xmlMessage.getProperties().getBoolean(SolaceBinderHeaders.CHUNK_ID));
                case SolaceBinderHeaders.CHUNK_COUNT -> assertEquals(expectedValue, xmlMessage.getProperties().getBoolean(SolaceBinderHeaders.CHUNK_COUNT));
                case SolaceBinderHeaders.CHUNK_INDEX -> assertEquals(expectedValue, xmlMessage.getProperties().getBoolean(SolaceBinderHeaders.CHUNK_INDEX));
                default -> fail(String.format("no test for header %s", header.getKey()));
            }
        }

        assertEquals((Integer) XMLMessageMapper.MESSAGE_VERSION,
                xmlMessage.getProperties().getInteger(SolaceBinderHeaders.MESSAGE_VERSION));

        Mockito.verify(xmlMessageMapper).map(testSpringMessage, excludedHeaders, false, DeliveryMode.PERSISTENT);
    }

    @ParameterizedTest
    @MethodSource("xmlMessageTypeProviders")
    <T, MT extends XMLMessage> void testMapXMLMessageToSpringMessage(
            XmlMessageTypeProvider<T, MT> xmlMessageTypeProvider) throws Throwable {
        MT xmlMessage = JCSMPFactory.onlyInstance().createMessage(xmlMessageTypeProvider.getXmlMessageType());
        T expectedPayload = xmlMessageTypeProvider.createPayload();
        xmlMessageTypeProvider.setXMLMessagePayload(xmlMessage, expectedPayload);

        SDTMap metadata = JCSMPFactory.onlyInstance().createMap();
        metadata.putString(MessageHeaders.CONTENT_TYPE, xmlMessageTypeProvider.getMimeType().toString());
        metadata.putString("test-header-1", "test-header-val-1");
        metadata.putString("test-header-2", "test-header-val-2");
        xmlMessage.setProperties(metadata);

        AcknowledgmentCallback acknowledgmentCallback = Mockito.mock(AcknowledgmentCallback.class);
        SolaceConsumerProperties consumerProperties = new SolaceConsumerProperties();
        Message<?> springMessage = xmlMessageMapper.map(xmlMessage, acknowledgmentCallback, consumerProperties);
        Mockito.verify(xmlMessageMapper).map(xmlMessage, acknowledgmentCallback, false, consumerProperties);

        validateSpringPayload(springMessage.getPayload(), expectedPayload);
        validateSpringHeaders(springMessage.getHeaders(), xmlMessage);
        assertNull(StaticMessageHeaderAccessor.getSourceData(springMessage));
    }

    @ParameterizedTest
    @MethodSource("xmlMessageTypeProviders")
    <T, MT extends XMLMessage> void testMapXMLMessageToSpringMessage_WithRawMessageHeader(
            XmlMessageTypeProvider<T, MT> xmlMessageTypeProvider) throws Throwable {
        MT xmlMessage = JCSMPFactory.onlyInstance().createMessage(xmlMessageTypeProvider.getXmlMessageType());
        T expectedPayload = xmlMessageTypeProvider.createPayload();
        xmlMessageTypeProvider.setXMLMessagePayload(xmlMessage, expectedPayload);

        SDTMap metadata = JCSMPFactory.onlyInstance().createMap();
        metadata.putString(MessageHeaders.CONTENT_TYPE, xmlMessageTypeProvider.getMimeType().toString());
        metadata.putString("test-header-1", "test-header-val-1");
        metadata.putString("test-header-2", "test-header-val-2");
        xmlMessage.setProperties(metadata);

        AcknowledgmentCallback acknowledgmentCallback = Mockito.mock(AcknowledgmentCallback.class);
        SolaceConsumerProperties consumerProperties = new SolaceConsumerProperties();
        Message<?> springMessage = xmlMessageMapper.map(xmlMessage, acknowledgmentCallback, true, consumerProperties);

        validateSpringPayload(springMessage.getPayload(), expectedPayload);
        validateSpringHeaders(springMessage.getHeaders(), xmlMessage);
        assertEquals(xmlMessage, StaticMessageHeaderAccessor.getSourceData(springMessage));
    }

    @CartesianTest(name = "[{index}] {0}")
    <T, MT extends XMLMessage> void testMapXMLMessageToSpringMessage_WithContentTypeHeaderAndHTTPContentType(
            @CartesianArgumentsSource(XmlMessageTypeCartesianProvider.class)
            Named<XmlMessageTypeProvider<T, MT>> namedXmlMessageTypeProvider) throws Throwable {
        XmlMessageTypeProvider<T, MT> xmlMessageTypeProvider = namedXmlMessageTypeProvider.getPayload();
        MT xmlMessage = JCSMPFactory.onlyInstance().createMessage(xmlMessageTypeProvider.getXmlMessageType());
        T expectedPayload = xmlMessageTypeProvider.createPayload();
        xmlMessageTypeProvider.setXMLMessagePayload(xmlMessage, expectedPayload);

        SDTMap metadata = JCSMPFactory.onlyInstance().createMap();
        metadata.putString(MessageHeaders.CONTENT_TYPE, xmlMessageTypeProvider.getMimeType().toString());
        xmlMessage.setProperties(metadata);
        xmlMessage.setHTTPContentType(MimeTypeUtils.TEXT_HTML_VALUE);

        AcknowledgmentCallback acknowledgmentCallback = Mockito.mock(AcknowledgmentCallback.class);
        SolaceConsumerProperties consumerProperties = new SolaceConsumerProperties();
        Message<?> springMessage;
        MessageHeaders springMessageHeaders;
        springMessage = xmlMessageMapper.map(xmlMessage, acknowledgmentCallback, consumerProperties);
        Mockito.verify(xmlMessageMapper).map(xmlMessage, acknowledgmentCallback, false, consumerProperties);
        springMessageHeaders = springMessage.getHeaders();

        assertEquals(metadata.getString(MessageHeaders.CONTENT_TYPE),
                springMessageHeaders.get(MessageHeaders.CONTENT_TYPE));

        validateSpringHeaders(springMessage.getHeaders(), xmlMessage);
    }

    @Test
    void testMapXMLMessageToSpringMessage_ReadSolaceProperties() throws Exception {
        Set<Map.Entry<String, ? extends HeaderMeta<?>>> readableHeaders = Stream.of(
                        SolaceHeaderMeta.META.entrySet().stream(),
                        SolaceBinderHeaderMeta.META.entrySet().stream())
                .flatMap(h -> h)
                .filter(h -> h.getValue().isReadable())
                .collect(Collectors.toSet());
        assertNotEquals(0, readableHeaders.size(), "Test header set was empty");

        XMLMessage defaultXmlMessage = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
        TextMessage xmlMessage = Mockito.mock(TextMessage.class); // Some properties are read-only. Need to mock
        SDTMap metadata = JCSMPFactory.onlyInstance().createMap();

        for (Map.Entry<String, ? extends HeaderMeta<?>> header : readableHeaders) {
            if (!HeaderMeta.Scope.WIRE.equals(header.getValue().getScope())) continue;
            switch (header.getKey()) {
                case SolaceHeaders.APPLICATION_MESSAGE_ID:
                    Mockito.when(xmlMessage.getApplicationMessageId()).thenReturn(header.getKey());
                    break;
                case SolaceHeaders.APPLICATION_MESSAGE_TYPE:
                    Mockito.when(xmlMessage.getApplicationMessageType()).thenReturn(header.getKey());
                    break;
                case SolaceHeaders.CORRELATION_ID:
                    Mockito.when(xmlMessage.getCorrelationId()).thenReturn(header.getKey());
                    break;
                case SolaceHeaders.DELIVERY_COUNT:
                    Mockito.when(xmlMessage.getDeliveryCount()).thenThrow(new UnsupportedOperationException("Feature is disabled"));
                    break;
                case SolaceHeaders.DESTINATION:
                    Mockito.when(xmlMessage.getDestination())
                            .thenReturn(JCSMPFactory.onlyInstance().createQueue(header.getKey()));
                    break;
                case SolaceHeaders.DISCARD_INDICATION:
                    Mockito.when(xmlMessage.getDiscardIndication())
                            .thenReturn(!defaultXmlMessage.getDiscardIndication());
                    break;
                case SolaceHeaders.DMQ_ELIGIBLE:
                    Mockito.when(xmlMessage.isDMQEligible()).thenReturn(!defaultXmlMessage.isDMQEligible());
                    break;
                case SolaceHeaders.EXPIRATION:
                    Mockito.when(xmlMessage.getExpiration()).thenReturn(ThreadLocalRandom.current().nextLong());
                    break;
                case SolaceHeaders.IS_REPLY:
                    Mockito.when(xmlMessage.isReplyMessage()).thenReturn(!defaultXmlMessage.isReplyMessage());
                    break;
                case SolaceHeaders.HTTP_CONTENT_ENCODING:
                    Mockito.when(xmlMessage.getHTTPContentEncoding()).thenReturn(header.getKey());
                    break;
                case SolaceHeaders.PRIORITY:
                    Mockito.when(xmlMessage.getPriority()).thenReturn(ThreadLocalRandom.current().nextInt());
                    break;
                case SolaceHeaders.RECEIVE_TIMESTAMP:
                    Mockito.when(xmlMessage.getReceiveTimestamp()).thenReturn(ThreadLocalRandom.current().nextLong());
                    break;
                case SolaceHeaders.REDELIVERED:
                    Mockito.when(xmlMessage.getRedelivered()).thenReturn(!defaultXmlMessage.getRedelivered());
                    break;
                case SolaceHeaders.REPLICATION_GROUP_MESSAGE_ID:
                    Mockito.when(xmlMessage.getReplicationGroupMessageId()).thenReturn(Mockito.mock(ReplicationGroupMessageId.class));
                    break;
                case SolaceHeaders.REPLY_TO:
                    Mockito.when(xmlMessage.getReplyTo())
                            .thenReturn(JCSMPFactory.onlyInstance().createQueue(header.getKey()));
                    break;
                case SolaceHeaders.SENDER_ID:
                    Mockito.when(xmlMessage.getSenderId()).thenReturn(header.getKey());
                    break;
                case SolaceHeaders.SENDER_TIMESTAMP:
                    Mockito.when(xmlMessage.getSenderTimestamp()).thenReturn(ThreadLocalRandom.current().nextLong());
                    break;
                case SolaceHeaders.SEQUENCE_NUMBER:
                    Mockito.when(xmlMessage.getSequenceNumber()).thenReturn(ThreadLocalRandom.current().nextLong());
                    break;
                case SolaceHeaders.TIME_TO_LIVE:
                    Mockito.when(xmlMessage.getTimeToLive()).thenReturn(ThreadLocalRandom.current().nextLong());
                    break;
                case SolaceHeaders.USER_DATA:
                    Mockito.when(xmlMessage.getUserData()).thenReturn(header.getKey().getBytes());
                    break;
                case SolaceBinderHeaders.MESSAGE_VERSION:
                    metadata.putInteger(header.getKey(), ThreadLocalRandom.current().nextInt());
                    break;
                case SolaceBinderHeaders.PARTITION_KEY:
                    // The partition key is read back from the Solace queue-partition-key (JMSXGroupID) property.
                    // Use a representative, arbitrary partition-key value (not the header name) so the round-trip
                    // assertion verifies the value is propagated rather than coincidentally matching the header name.
                    metadata.putString(XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY, RandomStringUtils.randomAlphanumeric(10));
                    break;
                default:
                    fail(String.format("no test for header %s", header.getKey()));
            }
        }

        Mockito.when(xmlMessage.getProperties()).thenReturn(metadata);
        Mockito.when(xmlMessage.getText()).thenReturn("testPayload");
        metadata.putString(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE);

        AcknowledgmentCallback acknowledgmentCallback = Mockito.mock(AcknowledgmentCallback.class);
        SolaceConsumerProperties consumerProperties = new SolaceConsumerProperties();
        Message<?> springMessage;
        MessageHeaders springMessageHeaders;

        springMessage = xmlMessageMapper.map(xmlMessage, acknowledgmentCallback, consumerProperties);
        Mockito.verify(xmlMessageMapper).map(xmlMessage, acknowledgmentCallback, false, consumerProperties);
        springMessageHeaders = springMessage.getHeaders();

        for (Map.Entry<String, ? extends HeaderMeta<?>> header : readableHeaders) {
            Object actualValue = springMessageHeaders.get(header.getKey());
            switch (header.getKey()) {
                case SolaceHeaders.APPLICATION_MESSAGE_ID:
                    assertEquals(xmlMessage.getApplicationMessageId(), actualValue);
                    break;
                case SolaceHeaders.APPLICATION_MESSAGE_TYPE:
                    assertEquals(xmlMessage.getApplicationMessageType(), actualValue);
                    break;
                case SolaceHeaders.CORRELATION_ID:
                    assertEquals(xmlMessage.getCorrelationId(), actualValue);
                    break;
                case SolaceHeaders.DELIVERY_COUNT:
                    //For this test, the delivery count feature is disabled
                    assertNull(actualValue);
                    break;
                case SolaceHeaders.DESTINATION:
                    assertEquals(xmlMessage.getDestination(), actualValue);
                    break;
                case SolaceHeaders.DISCARD_INDICATION:
                    assertEquals(xmlMessage.getDiscardIndication(), actualValue);
                    break;
                case SolaceHeaders.DMQ_ELIGIBLE:
                    assertEquals(xmlMessage.isDMQEligible(), actualValue);
                    break;
                case SolaceHeaders.EXPIRATION:
                    assertEquals(xmlMessage.getExpiration(), actualValue);
                    break;
                case SolaceHeaders.HTTP_CONTENT_ENCODING:
                    assertEquals(xmlMessage.getHTTPContentEncoding(), actualValue);
                    break;
                case SolaceHeaders.IS_REPLY:
                    assertEquals(xmlMessage.isReplyMessage(), actualValue);
                    break;
                case SolaceHeaders.PRIORITY:
                    assertEquals(xmlMessage.getPriority(), actualValue);
                    break;
                case SolaceHeaders.RECEIVE_TIMESTAMP:
                    assertEquals(xmlMessage.getReceiveTimestamp(), actualValue);
                    break;
                case SolaceHeaders.REDELIVERED:
                    assertEquals(xmlMessage.getRedelivered(), actualValue);
                    break;
                case SolaceHeaders.REPLICATION_GROUP_MESSAGE_ID:
                    assertEquals(xmlMessage.getReplicationGroupMessageId(), actualValue);
                    break;
                case SolaceHeaders.REPLY_TO:
                    assertEquals(xmlMessage.getReplyTo(), actualValue);
                    break;
                case SolaceHeaders.SENDER_ID:
                    assertEquals(xmlMessage.getSenderId(), actualValue);
                    break;
                case SolaceHeaders.SENDER_TIMESTAMP:
                    assertEquals(xmlMessage.getSenderTimestamp(), actualValue);
                    break;
                case SolaceHeaders.SEQUENCE_NUMBER:
                    assertEquals(xmlMessage.getSequenceNumber(), actualValue);
                    break;
                case SolaceHeaders.TIME_TO_LIVE:
                    assertEquals(xmlMessage.getTimeToLive(), actualValue);
                    break;
                case SolaceHeaders.USER_DATA:
                    assertEquals(xmlMessage.getUserData(), actualValue);
                    break;
                case SolaceBinderHeaders.MESSAGE_VERSION:
                    assertEquals(xmlMessage.getProperties().get(header.getKey()), actualValue);
                    break;
                case SolaceBinderHeaders.PARTITION_KEY:
                    assertEquals(xmlMessage.getProperties().getString(XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY), actualValue);
                    break;
                default:
                    if (HeaderMeta.Scope.WIRE.equals(header.getValue().getScope())) {
                        fail(String.format("no test for header %s", header.getKey()));
                    } else {
                        assertNull(actualValue, "Only wire-scoped headers can map to the Spring message");
                    }
            }
        }

        validateSpringHeaders(springMessage.getHeaders(), xmlMessage);
    }

    @Test
    void testMapXMLMessageToSpringMessage_NonReadableSolaceProperties() throws Exception {
        Set<Map.Entry<String, ? extends HeaderMeta<?>>> nonReadableHeaders = Stream.of(
                        SolaceHeaderMeta.META.entrySet().stream(),
                        SolaceBinderHeaderMeta.META.entrySet().stream())
                .flatMap(h -> h)
                .filter(h -> !h.getValue().isReadable())
                .collect(Collectors.toSet());
        assertNotEquals(0, nonReadableHeaders.size(), "Test header set was empty");

        TextMessage xmlMessage = Mockito.mock(TextMessage.class);
        SDTMap metadata = JCSMPFactory.onlyInstance().createMap();

        for (Map.Entry<String, ? extends HeaderMeta<?>> header : nonReadableHeaders) {
            switch (header.getKey()) {
                case SolaceBinderHeaders.SERIALIZED_HEADERS:
                    metadata.putString(header.getKey(), "[]");
                    break;
                case SolaceBinderHeaders.SERIALIZED_HEADERS_ENCODING:
                    metadata.putString(header.getKey(), "base64");
                    break;
                case SolaceBinderHeaders.SERIALIZED_PAYLOAD:
                    metadata.putBoolean(header.getKey(), false);
                    break;
                case SolaceBinderHeaders.CONFIRM_CORRELATION:
                    metadata.putString(header.getKey(), "random_string");
                    break;
                case SolaceBinderHeaders.TARGET_DESTINATION_TYPE:
                    metadata.putString(header.getKey(), "topic");
                    break;
                case SolaceBinderHeaders.LARGE_MESSAGE_SUPPORT:
                    metadata.putBoolean(header.getKey(), true);
                    break;
                case SolaceBinderHeaders.CHUNK_ID:
                    metadata.putLong(header.getKey(), 123L);
                    break;
                case SolaceBinderHeaders.CHUNK_COUNT:
                    metadata.putInteger(header.getKey(), 2);
                    break;
                case SolaceBinderHeaders.CHUNK_INDEX:
                    metadata.putInteger(header.getKey(), 0);
                    break;
                default:
                    fail(String.format("no test for header %s", header.getKey()));
            }
        }

        Mockito.when(xmlMessage.getProperties()).thenReturn(metadata);
        Mockito.when(xmlMessage.getText()).thenReturn("testPayload");
        Mockito.when(xmlMessage.getDeliveryCount()).thenThrow(new UnsupportedOperationException("Feature is disabled"));
        metadata.putString(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE);

        AcknowledgmentCallback acknowledgmentCallback = Mockito.mock(AcknowledgmentCallback.class);
        SolaceConsumerProperties consumerProperties = new SolaceConsumerProperties();

        Message<?> springMessage;
        MessageHeaders springMessageHeaders;
        springMessage = xmlMessageMapper.map(xmlMessage, acknowledgmentCallback, consumerProperties);
        Mockito.verify(xmlMessageMapper).map(xmlMessage, acknowledgmentCallback, false, consumerProperties);
        springMessageHeaders = springMessage.getHeaders();

        for (Map.Entry<String, ? extends HeaderMeta<?>> header : nonReadableHeaders) {
            assertThat(springMessageHeaders, not(hasKey(header)));
        }

        SDTMap filteredMetadata = JCSMPFactory.onlyInstance().createMap();
        for (String metadataKey : metadata.keySet()) {
            if (nonReadableHeaders.stream().map(Map.Entry::getKey).noneMatch(metadataKey::equals)) {
                filteredMetadata.putObject(metadataKey, metadata.get(metadataKey));
            }
        }

        validateSpringHeaders(springMessage.getHeaders(), xmlMessage, filteredMetadata);
    }

    @Test
    void testMapXMLMessageToSpringMessage_ReadLocalSolaceProperties() throws Exception {
        Set<Map.Entry<String, ? extends HeaderMeta<?>>> readableLocalHeaders = Stream.of(
                        SolaceHeaderMeta.META.entrySet().stream(),
                        SolaceBinderHeaderMeta.META.entrySet().stream())
                .flatMap(h -> h)
                .filter(h -> h.getValue().isReadable())
                .filter(h -> HeaderMeta.Scope.LOCAL.equals(h.getValue().getScope()))
                .collect(Collectors.toSet());
        assertNotEquals(0, readableLocalHeaders.size(), "Test header set was empty");

        TextMessage xmlMessage = Mockito.mock(TextMessage.class);
        SDTMap metadata = JCSMPFactory.onlyInstance().createMap();

        for (Map.Entry<String, ? extends HeaderMeta<?>> header : readableLocalHeaders) {
            // Since these properties are local-scoped, their wire-values should be ignored
            metadata.putString(header.getKey(), "test");
        }

        Mockito.when(xmlMessage.getProperties()).thenReturn(metadata);
        Mockito.when(xmlMessage.getText()).thenReturn("testPayload");
        Mockito.when(xmlMessage.getDeliveryCount()).thenThrow(new UnsupportedOperationException("Feature is disabled"));
        metadata.putString(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE);

        AcknowledgmentCallback acknowledgmentCallback = Mockito.mock(AcknowledgmentCallback.class);
        SolaceConsumerProperties consumerProperties = new SolaceConsumerProperties();

        Message<?> springMessage;
        MessageHeaders springMessageHeaders;
        springMessage = xmlMessageMapper.map(xmlMessage, acknowledgmentCallback, consumerProperties);
        Mockito.verify(xmlMessageMapper).map(xmlMessage, acknowledgmentCallback, false, consumerProperties);
        springMessageHeaders = springMessage.getHeaders();

        for (Map.Entry<String, ? extends HeaderMeta<?>> header : readableLocalHeaders) {
            Object actualValue = springMessageHeaders.get(header.getKey());
            if (header.getKey().equals(SolaceBinderHeaders.NULL_PAYLOAD)) {
                assertNull(actualValue);
            } else {
                fail(String.format("no test for header %s", header.getKey()));
            }
        }

        SDTMap filteredMetadata = JCSMPFactory.onlyInstance().createMap();
        for (String metadataKey : metadata.keySet()) {
            if (readableLocalHeaders.stream().map(Map.Entry::getKey).noneMatch(metadataKey::equals)) {
                filteredMetadata.putObject(metadataKey, metadata.get(metadataKey));
            }
        }

        validateSpringHeaders(springMessage.getHeaders(), xmlMessage, filteredMetadata);
    }

    @Test
    void testMapXMLMessageToSpringMessage_deliveryCountFeatureEnabled() {
        int deliveryCount = 42;
        TextMessage xmlMessage = Mockito.mock(TextMessage.class);
        Mockito.when(xmlMessage.getText()).thenReturn("testPayload");
        Mockito.when(xmlMessage.getDeliveryCount()).thenReturn(deliveryCount);

        AcknowledgmentCallback acknowledgmentCallback = Mockito.mock(AcknowledgmentCallback.class);
        SolaceConsumerProperties consumerProperties = new SolaceConsumerProperties();
        MapAssert<String, Object> headersAssert;
        headersAssert = Assertions.assertThat(xmlMessageMapper.map(xmlMessage, acknowledgmentCallback, consumerProperties)
                .getHeaders());
        headersAssert.extractingByKey(SolaceHeaders.DELIVERY_COUNT).isEqualTo(deliveryCount);
    }

    @Test
    void testMapXMLMessageToSpringMessage_ReadUndefinedSolaceHeader() throws Exception {
        String undefinedSolaceHeader = "abc124";
        TextMessage xmlMessage = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
        xmlMessage.setText("test");
        SDTMap metadata = JCSMPFactory.onlyInstance().createMap();
        metadata.putString("solace_foo1", undefinedSolaceHeader);
        metadata.putString(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE);
        xmlMessage.setProperties(metadata);

        AcknowledgmentCallback acknowledgmentCallback = Mockito.mock(AcknowledgmentCallback.class);
        Message<?> springMessage = xmlMessageMapper.map(xmlMessage, acknowledgmentCallback, new SolaceConsumerProperties());

        assertEquals(undefinedSolaceHeader, springMessage.getHeaders().get("solace_foo1", String.class));
        validateSpringHeaders(springMessage.getHeaders(), xmlMessage);
    }

    @Test
    void receivingAJavaSerializedPayloadOfAnOlderBinderDeserializesIt() throws Exception {
        SerializableFoo payload = new SerializableFoo("abc123", "HOOPLA!");
        BytesMessage xmlMessage = JCSMPFactory.onlyInstance().createMessage(BytesMessage.class);
        xmlMessage.setData(SerializationUtils.serialize(payload));
        SDTMap metadata = JCSMPFactory.onlyInstance().createMap();
        metadata.putString(MessageHeaders.CONTENT_TYPE, "application/x-java-serialized-object");
        metadata.putBoolean(SolaceBinderHeaders.SERIALIZED_PAYLOAD, true);
        xmlMessage.setProperties(metadata);

        Message<?> springMessage = xmlMessageMapper.map(xmlMessage, Mockito.mock(AcknowledgmentCallback.class),
                new SolaceConsumerProperties());

        assertEquals(payload, springMessage.getPayload());
    }

    @Test
    void receivingTheJavaSerializedContentTypeOfAnOlderBinderRestoresItsMimeType() throws Exception {
        TextMessage xmlMessage = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
        xmlMessage.setText("test");
        xmlMessage.setProperties(sdtMapWithBase64SerializedHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON));

        Message<?> springMessage = xmlMessageMapper.map(xmlMessage, Mockito.mock(AcknowledgmentCallback.class),
                new SolaceConsumerProperties());

        assertEquals(MimeTypeUtils.APPLICATION_JSON, springMessage.getHeaders().get(MessageHeaders.CONTENT_TYPE));
    }

    @Test
    void receivingTheJavaSerializedDeliveryAttemptOfAnOlderBinderRestoresItsCounter() throws Exception {
        TextMessage xmlMessage = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
        xmlMessage.setText("test");
        xmlMessage.setProperties(sdtMapWithBase64SerializedHeader(IntegrationMessageHeaderAccessor.DELIVERY_ATTEMPT,
                new AtomicInteger(3)));

        Message<?> springMessage = xmlMessageMapper.map(xmlMessage, Mockito.mock(AcknowledgmentCallback.class),
                new SolaceConsumerProperties());

        assertEquals(3, StaticMessageHeaderAccessor.getDeliveryAttempt(springMessage).get());
    }

    @Test
    void testMapXMLMessageToSpringMessage_WithNullPayload() {
        BytesMessage xmlMessage = JCSMPFactory.onlyInstance().createMessage(BytesMessage.class);
        AcknowledgmentCallback acknowledgmentCallback = Mockito.mock(AcknowledgmentCallback.class);
        SolaceConsumerProperties consumerProperties = new SolaceConsumerProperties();

        Message<?> springMessage;
        MessageHeaders springMessageHeaders;
        springMessage = xmlMessageMapper.map(xmlMessage, acknowledgmentCallback, consumerProperties);
        springMessageHeaders = springMessage.getHeaders();

        assertEquals(Boolean.TRUE, springMessageHeaders.get(SolaceBinderHeaders.NULL_PAYLOAD, Boolean.class));
    }

    @Test
    void testMapXMLMessageToSpringMessage_WithXmlContentInsteadOfBinaryAttachment() {
        String xmlContent = "<order id=\"" + RandomStringUtils.randomAlphanumeric(10) + "\"/>";
        BytesMessage xmlMessage = JCSMPFactory.onlyInstance().createMessage(BytesMessage.class);
        xmlMessage.writeBytes(xmlContent.getBytes(StandardCharsets.UTF_8));
        AcknowledgmentCallback acknowledgmentCallback = Mockito.mock(AcknowledgmentCallback.class);
        SolaceConsumerProperties consumerProperties = new SolaceConsumerProperties();

        Message<?> springMessage = xmlMessageMapper.map(xmlMessage, acknowledgmentCallback, consumerProperties);

        assertEquals(xmlContent, new String((byte[]) springMessage.getPayload(), StandardCharsets.UTF_8));
        assertNull(springMessage.getHeaders().get(SolaceBinderHeaders.NULL_PAYLOAD));
    }

    @Test
    void testMapMessageHeadersToSDTMap_JMSXGroupID() throws Exception {
        String jmsxGroupID = "partition-key-value";
        SDTMap sdtMap = xmlMessageMapper.map(
                new MessageHeaders(Collections.singletonMap(
                        XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY, jmsxGroupID)),
                Collections.emptyList(), false);
        assertThat(sdtMap.keySet(), hasItem(XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY));
        assertEquals(jmsxGroupID, sdtMap.getString(XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY));
    }

    @Test
    void testMapXMLMessageToSpringMessage_exposesPartitionKeyHeader() throws Exception {
        String partitionKey = "partition-key-value";
        TextMessage xmlMessage = Mockito.mock(TextMessage.class);
        Mockito.when(xmlMessage.getText()).thenReturn("payload");
        SDTMap metadata = JCSMPFactory.onlyInstance().createMap();
        metadata.putString(XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY, partitionKey);
        Mockito.when(xmlMessage.getProperties()).thenReturn(metadata);

        AcknowledgmentCallback acknowledgmentCallback = Mockito.mock(AcknowledgmentCallback.class);
        Message<?> springMessage = xmlMessageMapper.map(xmlMessage, acknowledgmentCallback, new SolaceConsumerProperties());

        // Variant 1: the Solace queue-partition-key is exposed under the documented binder header...
        assertEquals(partitionKey, springMessage.getHeaders().get(SolaceBinderHeaders.PARTITION_KEY));
        // ...while the raw JMSXGroupID property is still passed through for backward compatibility.
        assertEquals(partitionKey,
                springMessage.getHeaders().get(XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY));
    }

    @Test
    void testMapXMLMessageToSpringMessage_partitionKeyHeaderIsExcludable() throws Exception {
        String partitionKey = "partition-key-value";
        TextMessage xmlMessage = Mockito.mock(TextMessage.class);
        Mockito.when(xmlMessage.getText()).thenReturn("payload");
        SDTMap metadata = JCSMPFactory.onlyInstance().createMap();
        metadata.putString(XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY, partitionKey);
        Mockito.when(xmlMessage.getProperties()).thenReturn(metadata);

        SolaceConsumerProperties consumerProperties = new SolaceConsumerProperties();
        consumerProperties.setHeaderExclusions(List.of(SolaceBinderHeaders.PARTITION_KEY));
        AcknowledgmentCallback acknowledgmentCallback = Mockito.mock(AcknowledgmentCallback.class);
        Message<?> springMessage = xmlMessageMapper.map(xmlMessage, acknowledgmentCallback, consumerProperties);

        assertNull(springMessage.getHeaders().get(SolaceBinderHeaders.PARTITION_KEY));
    }

    @Test
    void testMapXMLMessageToSpringMessage_noPartitionKeyHeaderWhenAbsent() throws Exception {
        TextMessage xmlMessage = Mockito.mock(TextMessage.class);
        Mockito.when(xmlMessage.getText()).thenReturn("payload");
        Mockito.when(xmlMessage.getProperties()).thenReturn(JCSMPFactory.onlyInstance().createMap());

        AcknowledgmentCallback acknowledgmentCallback = Mockito.mock(AcknowledgmentCallback.class);
        Message<?> springMessage = xmlMessageMapper.map(xmlMessage, acknowledgmentCallback, new SolaceConsumerProperties());

        assertNull(springMessage.getHeaders().get(SolaceBinderHeaders.PARTITION_KEY));
    }

    @Test
    void mappingASerializableHeaderIsRejectedInsteadOfJavaSerialized() {
        Map<String, Object> headers = Collections.singletonMap("a", new SerializableFoo("abc123", "HOOPLA!"));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> xmlMessageMapper.map(new MessageHeaders(headers), Collections.emptyList(), false));

        assertThat(thrown.getMessage(), containsString("Invalid type as value - " + SerializableFoo.class.getSimpleName()));
    }

    @Test
    void mappingASerializableHeaderWithStringConversionWritesItsToString() throws Exception {
        SerializableFoo value = new SerializableFoo("abc123", "HOOPLA!");

        SDTMap sdtMap = xmlMessageMapper.map(new MessageHeaders(Collections.singletonMap("a", value)),
                Collections.emptyList(), true);

        assertEquals(value.toString(), sdtMap.getString("a"));
        assertThat(sdtMap.keySet(), not(hasItem(SolaceBinderHeaders.SERIALIZED_HEADERS)));
    }

    @Test
    void testMapMessageHeadersToSDTMap_NonSerializable() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> xmlMessageMapper.map(new MessageHeaders(Collections.singletonMap("a", new Object())),
                        Collections.emptyList(), false));
        assertThat(thrown, instanceOf(IllegalArgumentException.class));
        assertThat(thrown.getMessage(), containsString("Invalid type as value - Object"));
    }

    @Test
    void testMapMessageHeadersToSDTMap_NonSerializableToString() throws Exception {
        String key = "a";
        Object value = new Object();
        SDTMap sdtMap = xmlMessageMapper.map(new MessageHeaders(Collections.singletonMap(key, value)),
                Collections.emptyList(), true);
        assertEquals(value.toString(), sdtMap.get(key));
    }

    @Test
    void testMapMessageHeadersToSDTMap_Null() throws Exception {
        String key = "a";
        Map<String, Object> headers = Collections.singletonMap(key, null);
        SDTMap sdtMap = xmlMessageMapper.map(new MessageHeaders(headers), Collections.emptyList(), false);
        assertThat(sdtMap.keySet(), hasItem(key));
        assertNull(sdtMap.get(key));
    }

    @Test
    void testMapMessageHeadersToSDTMap_NonJmsCompatible() throws Exception {
        byte[] value = "test".getBytes(); // byte[] values are not supported by JMS
        Map<String, Object> headers = new HashMap<>();
        JMS_INVALID_HEADER_NAMES.forEach(h -> headers.put(h, value));

        SDTMap sdtMap = xmlMessageMapper.map(new MessageHeaders(headers), Collections.emptyList(), false);

        for (String header : JMS_INVALID_HEADER_NAMES) {
            assertThat(sdtMap.keySet(), hasItem(header));
            assertEquals(value, sdtMap.getBytes(header));
        }
    }

    @Test
    void receivingBase64JavaSerializedHeadersOfAnOlderBinderDeserializesThem() throws Exception {
        SerializableFoo value = new SerializableFoo("abc123", "HOOPLA!");

        MessageHeaders messageHeaders = xmlMessageMapper.map(sdtMapWithBase64SerializedHeader("a", value), List.of());

        assertEquals(value, messageHeaders.get("a"));
    }

    @Test
    void receivingUnencodedJavaSerializedHeadersOfAnOlderBinderDeserializesThem() throws Exception {
        SerializableFoo value = new SerializableFoo("abc123", "HOOPLA!");
        SDTMap sdtMap = JCSMPFactory.onlyInstance().createMap();
        sdtMap.putBytes("a", SerializationUtils.serialize(value));
        sdtMap.putString(SolaceBinderHeaders.SERIALIZED_HEADERS, "[\"a\"]");

        MessageHeaders messageHeaders = xmlMessageMapper.map(sdtMap, List.of());

        assertEquals(value, messageHeaders.get("a"));
    }

    @Test
    void receivingANullJavaSerializedHeaderOfAnOlderBinderKeepsItAsNull() throws Exception {
        SDTMap sdtMap = JCSMPFactory.onlyInstance().createMap();
        sdtMap.putObject("a", null);
        sdtMap.putString(SolaceBinderHeaders.SERIALIZED_HEADERS, "[\"a\"]");
        sdtMap.putString(SolaceBinderHeaders.SERIALIZED_HEADERS_ENCODING, "base64");

        MessageHeaders messageHeaders = xmlMessageMapper.map(sdtMap, List.of());

        Assertions.assertThat(messageHeaders).containsEntry("a", null);
    }

    @Test
    void receivingASerializedHeaderNameWithoutItsValueAddsNoHeader() throws Exception {
        SDTMap sdtMap = JCSMPFactory.onlyInstance().createMap();
        sdtMap.putString(SolaceBinderHeaders.SERIALIZED_HEADERS, "[\"a\"]");

        MessageHeaders messageHeaders = xmlMessageMapper.map(sdtMap, List.of());

        Assertions.assertThat(messageHeaders).doesNotContainKey("a");
    }

    @Test
    void receivingSerializedHeadersInAnUnsupportedEncodingIsRejected() throws Exception {
        SDTMap sdtMap = sdtMapWithBase64SerializedHeader("a", new SerializableFoo("abc123", "HOOPLA!"));
        sdtMap.putString(SolaceBinderHeaders.SERIALIZED_HEADERS_ENCODING, "abc");

        SolaceMessageConversionException exception = assertThrows(SolaceMessageConversionException.class,
                () -> xmlMessageMapper.map(sdtMap, List.of()));

        assertEquals("abc encoding is not supported", exception.getMessage());
    }

    private static SDTMap sdtMapWithBase64SerializedHeader(String name, Serializable value) throws SDTException {
        SDTMap sdtMap = JCSMPFactory.onlyInstance().createMap();
        sdtMap.putString(name, Base64.getEncoder().encodeToString(SerializationUtils.serialize(value)));
        sdtMap.putString(SolaceBinderHeaders.SERIALIZED_HEADERS, "[\"" + name + "\"]");
        sdtMap.putString(SolaceBinderHeaders.SERIALIZED_HEADERS_ENCODING, "base64");
        return sdtMap;
    }

    @Test
    void testMapSDTMapToMessageHeaders_Null() throws Exception {
        String key = "a";
        SDTMap sdtMap = JCSMPFactory.onlyInstance().createMap();
        sdtMap.putObject(key, null);

        MessageHeaders messageHeaders = xmlMessageMapper.map(sdtMap, List.of());

        assertThat(messageHeaders.keySet(), hasItem(key));
        assertNull(messageHeaders.get(key));
    }

    @Test
    void testMapSDTMapToMessageHeaders_WithNullExcludedHeader() throws Exception {
        SDTMap sdtMap = JCSMPFactory.onlyInstance().createMap();
        for (int i = 0; i < 10; i++) {
            sdtMap.putObject("headerKey" + i, UUID.randomUUID().toString());
        }

        MessageHeaders messageHeaders = xmlMessageMapper.map(sdtMap, null);

        for (int i = 0; i < 10; i++) {
            String key = "headerKey" + i;
            assertEquals(sdtMap.get(key), messageHeaders.get(key));
        }
    }

    @Test
    void testMapXMLMessageToSpringMessage_WithExcludedHeader()
            throws SDTException {
        List<String> excludedHeaders = List.of("headerKey1", "headerKey2", "headerKey5",
                "solace_expiration", "solace_discardIndication", "solace_redelivered",
                "solace_dmqEligible", "solace_priority");
        BytesMessage xmlMessage = Mockito.spy(JCSMPFactory.onlyInstance().createMessage(BytesMessage.class));
        AcknowledgmentCallback acknowledgmentCallback = Mockito.mock(AcknowledgmentCallback.class);
        SolaceConsumerProperties consumerProperties = new SolaceConsumerProperties();
        consumerProperties.setHeaderExclusions(excludedHeaders);

        SDTMap metadata = JCSMPFactory.onlyInstance().createMap();
        Mockito.when(xmlMessage.getProperties()).thenReturn(metadata);
        for (int i = 0; i < 10; i++) {
            metadata.putObject("headerKey" + i, UUID.randomUUID().toString());
        }

        Message<?> springMessage;
        MessageHeaders springMessageHeaders;
        springMessage = xmlMessageMapper.map(xmlMessage, acknowledgmentCallback, consumerProperties);
        springMessageHeaders = springMessage.getHeaders();

        for (int i = 0; i < 10; i++) {
            String key = "headerKey" + i;
            if (excludedHeaders.contains(key)) {
                assertFalse(springMessageHeaders.containsKey(key));
            } else {
                assertEquals(metadata.get(key), springMessageHeaders.get(key));
            }
        }
    }

    @Test
    void testMapSDTMapToMessageHeaders_WithExcludedHeader() throws Exception {
        SDTMap sdtMap = JCSMPFactory.onlyInstance().createMap();
        for (int i = 0; i < 10; i++) {
            sdtMap.putObject("headerKey" + i, UUID.randomUUID().toString());
        }

        List<String> excludedHeaders = List.of("headerKey1", "headerKey2", "headerKey5");
        MessageHeaders messageHeaders = xmlMessageMapper.map(sdtMap, excludedHeaders);

        for (int i = 0; i < 10; i++) {
            String key = "headerKey" + i;
            if (excludedHeaders.contains(key)) {
                assertFalse(messageHeaders.containsKey(key));
            } else {
                assertEquals(sdtMap.get(key), messageHeaders.get(key));
            }
        }
    }

    @Test
    void testMapSDTMapToMessageHeaders_WithExcludedHeader_canExcludeBinderHeaders() throws Exception {
        SDTMap sdtMap = JCSMPFactory.onlyInstance().createMap();
        sdtMap.putObject(SolaceBinderHeaders.MESSAGE_VERSION, 10);
        sdtMap.putObject(SolaceBinderHeaders.SERIALIZED_HEADERS, "header1, header2");
        sdtMap.putObject("retainedHeader", "test");

        List<String> excludedHeaders = List.of(SolaceBinderHeaders.MESSAGE_VERSION, SolaceBinderHeaders.SERIALIZED_HEADERS);
        MessageHeaders messageHeaders = xmlMessageMapper.map(sdtMap, excludedHeaders);

        sdtMap.keySet().forEach(key -> {
            if (excludedHeaders.contains(key)) {
                assertFalse(messageHeaders.containsKey(key));
            } else {
                try {
                    assertEquals(sdtMap.get(key), messageHeaders.get(key));
                } catch (SDTException e) {
                    fail(e);
                }
            }
        });
    }

    @Test
    void testMapSDTMapToMessageHeaders_NonJmsCompatible() throws Exception {
        byte[] value = "test".getBytes(); // byte[] values are not supported by JMS
        SDTMap sdtMap = JCSMPFactory.onlyInstance().createMap();
        for (String header : JMS_INVALID_HEADER_NAMES) {
            sdtMap.putBytes(header, value);
        }

        MessageHeaders messageHeaders = xmlMessageMapper.map(sdtMap, List.of());

        for (String header : JMS_INVALID_HEADER_NAMES) {
            assertThat(messageHeaders.keySet(), hasItem(header));
            assertEquals(value, messageHeaders.get(header, byte[].class));
        }
    }

    @Test
    void testMapLoop() throws Exception {
        Message<?> expectedSpringMessage = new DefaultMessageBuilderFactory()
                .withPayload("testPayload")
                .setHeader("test-header-1", "test-header-val-1")
                .setHeader("test-header-2", "test-header-val-2")
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE)
                .build();
        Map<String, Object> springHeaders = new HashMap<>(expectedSpringMessage.getHeaders());

        SDTMap metadata = JCSMPFactory.onlyInstance().createMap();
        metadata.putString("test-header-1", "test-header-val-1");
        metadata.putString("test-header-2", "test-header-val-2");
        metadata.putString(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.TEXT_PLAIN_VALUE);
        XMLMessage expectedXmlMessage = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
        expectedXmlMessage.setProperties(metadata);
        expectedXmlMessage.setHTTPContentType(MimeTypeUtils.TEXT_PLAIN_VALUE);

        SolaceConsumerProperties consumerProperties = new SolaceConsumerProperties();
        Message<?> springMessage = expectedSpringMessage;
        XMLMessage xmlMessage;
        int i = 0;
        do {
            log.info(String.format("Iteration %s - Message<?> to XMLMessage:\n%s", i, springMessage));
            xmlMessage = xmlMessageMapper.map(springMessage, null, false, DeliveryMode.PERSISTENT);
            springHeaders.remove("id");
            springHeaders.remove("timestamp");

            validateXMLProperties(xmlMessage, expectedSpringMessage.getPayload(), expectedSpringMessage.getHeaders(),
                    springHeaders);

            log.info(String.format("Iteration %s - XMLMessage to Message<?>:\n%s", i, xmlMessage));
            AcknowledgmentCallback acknowledgmentCallback = Mockito.mock(AcknowledgmentCallback.class);
            springMessage = xmlMessageMapper.map(xmlMessage, acknowledgmentCallback, consumerProperties);
            validateSpringHeaders(springMessage.getHeaders(), expectedXmlMessage);

            i++;
        } while (i < 3);
    }

    private void validateXMLProperties(XMLMessage xmlMessage, Message<?> springMessage)
            throws Exception {
        validateXMLProperties(xmlMessage, springMessage.getPayload(), new HashMap<>(springMessage.getHeaders()));
    }

    private void validateXMLProperties(XMLMessage xmlMessage,
                                       Object springMessagePayload,
                                       Map<String, Object> springMessageHeaders)
            throws Exception {
        validateXMLProperties(xmlMessage, springMessagePayload, springMessageHeaders, springMessageHeaders);
    }

    private void validateXMLProperties(
            XMLMessage xmlMessage,
            Object springMessagePayload,
            Map<String, Object> springMessageHeaders,
            Map<String, Object> expectedHeaders) throws Exception {
        expectedHeaders.remove("id");
        expectedHeaders.remove("timestamp");

        assertEquals(DeliveryMode.PERSISTENT, xmlMessage.getDeliveryMode());
        Assertions.assertThat(new GenericMessage<>(springMessagePayload, springMessageHeaders))
                .extracting(StaticMessageHeaderAccessor::getContentType)
                .hasToString(xmlMessage.getHTTPContentType());

        SDTMap metadata = xmlMessage.getProperties();

        assertEquals((Integer) XMLMessageMapper.MESSAGE_VERSION, metadata.getInteger(SolaceBinderHeaders.MESSAGE_VERSION));

        Map<String, SolaceHeaderMeta<?>> readWriteableSolaceHeaders = SolaceHeaderMeta.META
                .entrySet()
                .stream()
                .filter(h -> h.getValue().isWritable())
                .filter(h -> h.getValue().isReadable())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        Assertions.assertThat(expectedHeaders)
                .allSatisfy((headerKey, headerValue) -> {
                    if (readWriteableSolaceHeaders.containsKey(headerKey)) {
                        Object value = readWriteableSolaceHeaders.get(headerKey).getReadAction().apply(xmlMessage);
                        assertEquals(headerValue, value);
                    } else {
                        switch (headerKey) {
                            case SolaceBinderHeaders.PARTITION_KEY -> headerKey = XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY;
                            case XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY -> headerValue = expectedHeaders.getOrDefault(SolaceBinderHeaders.PARTITION_KEY, headerValue);
                            case SolaceBinderHeaders.LARGE_MESSAGE_SUPPORT,
                                 SolaceBinderHeaders.CONFIRM_CORRELATION,
                                 SolaceBinderHeaders.TARGET_DESTINATION_TYPE -> {
                                Assertions.assertThat(metadata.keySet()).doesNotContain(headerKey);
                                return;
                            }
                        }
                        Assertions.assertThat(metadata.keySet()).contains(headerKey);
                        try {
                            Assertions.assertThat(metadata.get(headerKey)).isEqualTo(headerValue);
                        } catch (SDTException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
    }

    private void validateSpringHeaders(MessageHeaders messageHeaders, XMLMessage xmlMessage)
            throws SDTException {
        validateSpringHeaders(messageHeaders, xmlMessage, xmlMessage.getProperties());
    }

    private void validateSpringHeaders(MessageHeaders messageHeaders, XMLMessage xmlMessage,
                                       SDTMap expectedHeaders)
            throws SDTException {
        List<String> nonReadableBinderHeaderMeta = SolaceBinderHeaderMeta.META
                .entrySet()
                .stream()
                .filter(h -> !h.getValue().isReadable())
                .map(Map.Entry::getKey)
                .toList();

        Assertions.assertThat(nonReadableBinderHeaderMeta)
                .allSatisfy(customHeaderName ->
                        Assertions.assertThat(messageHeaders).doesNotContainKey(customHeaderName));

        Assertions.assertThat(expectedHeaders.keySet())
                .filteredOn(h -> !nonReadableBinderHeaderMeta.contains(h))
                .allSatisfy(h -> assertEquals(expectedHeaders.get(h), messageHeaders.get(h)));

        Assertions.assertThat(messageHeaders)
                .hasEntrySatisfying(IntegrationMessageHeaderAccessor.ACKNOWLEDGMENT_CALLBACK, v ->
                        Assertions.assertThat(v)
                                .asInstanceOf(InstanceOfAssertFactories.type(AcknowledgmentCallback.class))
                                .isNotNull())
                .hasEntrySatisfying(IntegrationMessageHeaderAccessor.DELIVERY_ATTEMPT, v ->
                        Assertions.assertThat(v)
                                .asInstanceOf(InstanceOfAssertFactories.ATOMIC_INTEGER)
                                .hasValue(0));

        Object contentType = messageHeaders.get(MessageHeaders.CONTENT_TYPE);
        assertNotNull(contentType);
        if (!expectedHeaders.containsKey(MessageHeaders.CONTENT_TYPE)) {
            assertEquals(xmlMessage.getHTTPContentType(), (contentType instanceof MimeType ?
                    (MimeType) contentType : MimeType.valueOf(contentType.toString())).toString());
        }

        //DeliveryCount feature is assumed disabled
        Assertions.assertThat(messageHeaders).doesNotContainKey(SolaceHeaders.DELIVERY_COUNT);
    }

    private <T> void validateSpringPayload(Object payload, T expectedPayload) {
        Assertions.assertThat(payload).isInstanceOf(expectedPayload.getClass());

        @SuppressWarnings("unchecked")
        T springMessagePayload = (T) expectedPayload.getClass().cast(payload);

        if (expectedPayload.getClass().isArray()) {
            if (expectedPayload.getClass().getComponentType().isPrimitive()) {
                assertEquals(Array.getLength(expectedPayload), Array.getLength(springMessagePayload));
                for (int i = 0; i < Array.getLength(springMessagePayload); i++) {
                    assertEquals(Array.get(expectedPayload, i), Array.get(springMessagePayload, i));
                }
            } else {
                assertArrayEquals((Object[]) expectedPayload, (Object[]) springMessagePayload);
            }
        } else {
            assertEquals(expectedPayload, springMessagePayload);
        }
    }

    private record SpringMessageTypeProvider<T, MT extends XMLMessage>(
            Class<MT> expectedXmlMessageType,
            MimeType mimeType,
            ThrowingSupplier<T> createPayloadSupplier,
            Function<MT, T> xmlMessagePayloadGetter) {

        public T createPayload() {
            try {
                return createPayloadSupplier.get();
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }

        public T extractSmfPayload(MT message) {
            return xmlMessagePayloadGetter.apply(message);
        }
    }

    private static class XmlMessageTypeCartesianProvider implements CartesianParameterArgumentsProvider<
            Named<XmlMessageTypeProvider<?, ?>>> {

        @SuppressWarnings("unchecked")
        @Override
        public Stream<Named<XmlMessageTypeProvider<?, ?>>> provideArguments(ExtensionContext context, Parameter parameter) {
            return xmlMessageTypeProviders()
                    .map(arguments -> arguments.get()[0])
                    .map(arg -> (Named<XmlMessageTypeProvider<?, ?>>) arg);
        }
    }

    private static class XmlMessageTypeProvider<T, MT extends XMLMessage> {
        private final Class<MT> xmlMessageType;
        private final MimeType mimeType;
        private final ThrowingSupplier<T> createPayloadSupplier;
        private final BiConsumer<MT, T> setXmlMessagePayloadFnc;

        private XmlMessageTypeProvider(Class<MT> xmlMessageType,
                                       MimeType mimeType,
                                       ThrowingSupplier<T> createPayloadSupplier,
                                       BiConsumer<MT, T> setXmlMessagePayloadFnc) {
            this.xmlMessageType = xmlMessageType;
            this.mimeType = mimeType;
            this.createPayloadSupplier = createPayloadSupplier;
            this.setXmlMessagePayloadFnc = setXmlMessagePayloadFnc;
        }

        public Class<MT> getXmlMessageType() {
            return xmlMessageType;
        }

        public void setXMLMessagePayload(MT message, T payload) {
            setXmlMessagePayloadFnc.accept(message, payload);
        }

        public MimeType getMimeType() {
            return mimeType;
        }

        public T createPayload() {
            try {
                return createPayloadSupplier.get();
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
    }
}
