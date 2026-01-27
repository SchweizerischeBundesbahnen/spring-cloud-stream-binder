package com.solace.spring.cloud.stream.binder.util;

import com.solace.spring.cloud.stream.binder.messaging.SolaceBinderHeaderMeta;
import com.solace.spring.cloud.stream.binder.messaging.SolaceBinderHeaders;
import com.solace.spring.cloud.stream.binder.messaging.SolaceHeaderMeta;
import com.solace.spring.cloud.stream.binder.properties.SolaceConsumerProperties;
import com.solacesystems.common.util.ByteArray;
import com.solacesystems.jcsmp.*;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.binder.BinderHeaders;
import org.springframework.integration.IntegrationMessageHeaderAccessor;
import org.springframework.integration.acks.AcknowledgmentCallback;
import org.springframework.integration.support.AbstractIntegrationMessageBuilder;
import org.springframework.integration.support.DefaultMessageBuilderFactory;
import org.springframework.integration.support.MessageBuilderFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.util.MimeType;
import org.springframework.util.SerializationUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectWriter;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

@Slf4j
public class XMLMessageMapper {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final MessageBuilderFactory MESSAGE_BUILDER_FACTORY = new DefaultMessageBuilderFactory();
    private static final String KAFKA_TOPIC = "k_topic";
    static final int MESSAGE_VERSION = 1;
    static final Encoder DEFAULT_ENCODING = Encoder.BASE64;

    private final ObjectWriter stringSetWriter = OBJECT_MAPPER.writerFor(new TypeReference<Set<String>>() {
    });
    private final ObjectReader stringSetReader = OBJECT_MAPPER.readerFor(new TypeReference<Set<String>>() {
    });
    private final Set<String> ignoredHeaderProperties = ConcurrentHashMap.newKeySet();

    public BytesXMLMessage mapError(BytesXMLMessage inputMessage, SolaceConsumerProperties consumerProperties) {
        BytesXMLMessage errorMessage = JCSMPFactory.onlyInstance().createMessage(inputMessage);
        if (consumerProperties.getErrorMsgDmqEligible() != null) {
            errorMessage.setDMQEligible(consumerProperties.getErrorMsgDmqEligible());
        }
        if (consumerProperties.getErrorMsgTtl() != null) {
            errorMessage.setTimeToLive(consumerProperties.getErrorMsgTtl());
        }
        if (DeliveryMode.DIRECT.equals(errorMessage.getDeliveryMode())) {
            errorMessage.setDeliveryMode(DeliveryMode.PERSISTENT);
        }
        return errorMessage;
    }

    public XMLMessage map(Message<?> message, Collection<String> excludedHeaders, boolean convertNonSerializableHeadersToString, DeliveryMode deliveryMode) {
        return map(message.getPayload(), message.getHeaders(), message.getHeaders().getId(), excludedHeaders, convertNonSerializableHeadersToString, deliveryMode);
    }

    // exposed for testing
    @SneakyThrows
    XMLMessage map(Object payload, Map<String, Object> headers, UUID messageId, Collection<String> excludedHeaders, boolean convertNonSerializableHeadersToString, DeliveryMode deliveryMode) {
        XMLMessage xmlMessage;
        SDTMap metadata = map(headers, excludedHeaders, convertNonSerializableHeadersToString);
        metadata.putInteger(SolaceBinderHeaders.MESSAGE_VERSION, MESSAGE_VERSION);
        if (payload instanceof byte[]) {
            BytesMessage bytesMessage = JCSMPFactory.onlyInstance().createMessage(BytesMessage.class);
            bytesMessage.setData((byte[]) payload);
            xmlMessage = bytesMessage;
        } else if (payload instanceof String) {
            TextMessage textMessage = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
            textMessage.setText((String) payload);
            xmlMessage = textMessage;
        } else if (payload instanceof SDTStream) {
            StreamMessage streamMessage = JCSMPFactory.onlyInstance().createMessage(StreamMessage.class);
            streamMessage.setStream((SDTStream) payload);
            xmlMessage = streamMessage;
        } else if (payload instanceof SDTMap) {
            MapMessage mapMessage = JCSMPFactory.onlyInstance().createMessage(MapMessage.class);
            mapMessage.setMap((SDTMap) payload);
            xmlMessage = mapMessage;
        } else if (payload instanceof Serializable) {
            BytesMessage bytesMessage = JCSMPFactory.onlyInstance().createMessage(BytesMessage.class);
            bytesMessage.setData(SerializationUtils.serialize(payload));
            metadata.putBoolean(SolaceBinderHeaders.SERIALIZED_PAYLOAD, true);
            xmlMessage = bytesMessage;
        } else {
            String msg = String.format("Invalid payload received. Expected %s. Received: %s", String.join(", ", byte[].class.getSimpleName(), String.class.getSimpleName(), SDTStream.class.getSimpleName(), SDTMap.class.getSimpleName(), Serializable.class.getSimpleName()), payload.getClass().getName());
            SolaceMessageConversionException exception = new SolaceMessageConversionException(msg);
            log.warn(msg, exception);
            throw exception;
        }

        Object contentType = headers.get(MessageHeaders.CONTENT_TYPE);
        if (contentType != null) {
            // derived from StaticMessageHeaderAccessor.getContentType(Message<?>)
            xmlMessage.setHTTPContentType(contentType instanceof MimeType ? contentType.toString() : MimeType.valueOf(contentType.toString()).toString());
        }

        // Copy Solace properties from Spring Message to JCSMP XMLMessage
        for (Map.Entry<String, SolaceHeaderMeta<?>> header : SolaceHeaderMeta.META.entrySet()) {
            if (!header.getValue().isWritable()) {
                continue;
            }

            Object value = headers.get(header.getKey());
            if (value != null) {
                if (!header.getValue().getType().isInstance(value)) {
                    String msg = String.format("Message %s has an invalid value type for header %s. Expected %s but received %s.", messageId, header.getKey(), header.getValue().getType(), value.getClass());
                    SolaceMessageConversionException exception = new SolaceMessageConversionException(msg);
                    log.warn(msg, exception);
                    throw exception;
                }
            } else if (header.getValue().hasOverriddenDefaultValue()) {
                value = header.getValue().getDefaultValueOverride();
            } else {
                continue;
            }

            try {
                header.getValue().getWriteAction().accept(xmlMessage, value);
            } catch (Exception e) {
                String msg = String.format("Could not set %s property from header %s of message %s", XMLMessage.class.getSimpleName(), header.getKey(), messageId);
                SolaceMessageConversionException exception = new SolaceMessageConversionException(msg, e);
                log.warn(msg, exception);
                throw exception;
            }
        }

        xmlMessage.setProperties(metadata);
        xmlMessage.setDeliveryMode(deliveryMode);

        return xmlMessage;
    }

    public Message<?> map(XMLMessage xmlMessage, AcknowledgmentCallback acknowledgmentCallback, SolaceConsumerProperties solaceConsumerProperties) {
        return map(xmlMessage, acknowledgmentCallback, false, solaceConsumerProperties);
    }

    public Message<?> map(XMLMessage xmlMessage, AcknowledgmentCallback acknowledgmentCallback, boolean setRawMessageHeader, SolaceConsumerProperties solaceConsumerProperties) {
        try {
            return injectRootMessageHeaders(mapInternal(xmlMessage, solaceConsumerProperties), acknowledgmentCallback, setRawMessageHeader ? xmlMessage : null).build();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @SneakyThrows
    private AbstractIntegrationMessageBuilder<?> mapInternal(XMLMessage xmlMessage, SolaceConsumerProperties solaceConsumerProperties) {
        SDTMap metadata = xmlMessage.getProperties();
        List<String> excludedHeaders = solaceConsumerProperties.getHeaderExclusions();

        Object payload;
        if (xmlMessage instanceof BytesMessage) {
            payload = ((BytesMessage) xmlMessage).getData();
            if (metadata != null && metadata.containsKey(SolaceBinderHeaders.SERIALIZED_PAYLOAD)) {
                if (metadata.getBoolean(SolaceBinderHeaders.SERIALIZED_PAYLOAD)) {
                    payload = SerializationUtils.deserialize((byte[]) payload);
                }
            }
        } else if (xmlMessage instanceof TextMessage) {
            payload = ((TextMessage) xmlMessage).getText();
        } else if (xmlMessage instanceof MapMessage) {
            payload = ((MapMessage) xmlMessage).getMap();
        } else if (xmlMessage instanceof StreamMessage) {
            payload = ((StreamMessage) xmlMessage).getStream();
        } else if (xmlMessage instanceof XMLContentMessage) {
            payload = ((XMLContentMessage) xmlMessage).getXMLContent();
        } else {
            String msg = String.format("Invalid message format received. Expected %s. Received: %s", String.join(", ", BytesMessage.class.getSimpleName(), TextMessage.class.getSimpleName(), MapMessage.class.getSimpleName(), StreamMessage.class.getSimpleName(), XMLContentMessage.class.getSimpleName()), xmlMessage.getClass());
            SolaceMessageConversionException exception = new SolaceMessageConversionException(msg);
            log.warn(msg, exception);
            throw exception;
        }

        boolean isNullPayload = payload == null;
        if (isNullPayload) {
            //Set empty payload equivalent to null
            if (xmlMessage instanceof BytesMessage) {
                payload = new byte[0];
            } else if (xmlMessage instanceof TextMessage || xmlMessage instanceof XMLContentMessage) {
                payload = "";
            } else if (xmlMessage instanceof MapMessage) {
                payload = JCSMPFactory.onlyInstance().createMap();
            } else if (xmlMessage instanceof StreamMessage) {
                payload = JCSMPFactory.onlyInstance().createStream();
            }
        }

        AbstractIntegrationMessageBuilder<?> builder = MESSAGE_BUILDER_FACTORY.withPayload(payload).copyHeaders(map(metadata, excludedHeaders)).setHeaderIfAbsent(MessageHeaders.CONTENT_TYPE, xmlMessage.getHTTPContentType());

        if (isNullPayload) {
            if (log.isDebugEnabled()) {
                log.debug("Null payload detected, setting Spring header " + SolaceBinderHeaders.NULL_PAYLOAD);
            }
            builder.setHeader(SolaceBinderHeaders.NULL_PAYLOAD, isNullPayload);
        }

        for (Map.Entry<String, SolaceHeaderMeta<?>> header : SolaceHeaderMeta.META.entrySet()) {
            if (!header.getValue().isReadable()) {
                continue;
            }
            if (excludedHeaders != null && excludedHeaders.contains(header.getKey())) {
                continue;
            }
            if (ignoredHeaderProperties.contains(header.getKey())) {
                continue;
            }
            try {
                builder.setHeaderIfAbsent(header.getKey(), header.getValue().getReadAction().apply(xmlMessage));
            } catch (UnsupportedOperationException e) {
                if (log.isDebugEnabled()) {
                    log.debug(String.format("Ignoring Solace header %s. Error: %s", header.getKey(), e.getMessage()));
                }
                ignoredHeaderProperties.add(header.getKey());
            }
        }

        return builder;
    }

    private <T> AbstractIntegrationMessageBuilder<T> injectRootMessageHeaders(AbstractIntegrationMessageBuilder<T> builder, AcknowledgmentCallback acknowledgmentCallback, Object sourceData) {
        return builder.setHeader(IntegrationMessageHeaderAccessor.ACKNOWLEDGMENT_CALLBACK, acknowledgmentCallback).setHeaderIfAbsent(IntegrationMessageHeaderAccessor.DELIVERY_ATTEMPT, new AtomicInteger(0)).setHeader(IntegrationMessageHeaderAccessor.SOURCE_DATA, sourceData);
    }

    @SneakyThrows
    SDTMap map(Map<String, Object> headers, Collection<String> excludedHeaders, boolean convertNonSerializableHeadersToString) {
        SDTMap metadata = JCSMPFactory.onlyInstance().createMap();
        Set<String> serializedHeaders = new HashSet<>();
        for (Map.Entry<String, Object> header : headers.entrySet()) {
            if (isJcsmpDefaultHeader(header) || isSpringPollutedHeader(header)) {
                continue;
            }
            if (excludedHeaders != null && excludedHeaders.contains(header.getKey())) {
                continue;
            }
            if (IntegrationMessageHeaderAccessor.SOURCE_DATA.equals(header.getKey()) ||
                    IntegrationMessageHeaderAccessor.ACKNOWLEDGMENT_CALLBACK.equals(header.getKey())) {
                continue;
            }

            addSDTMapObject(metadata, serializedHeaders, header.getKey(), header.getValue(), convertNonSerializableHeadersToString);
        }

        if (headers.containsKey(SolaceBinderHeaders.PARTITION_KEY)) {
            Object partitionKeyObj = headers.get(SolaceBinderHeaders.PARTITION_KEY);
            if (partitionKeyObj instanceof String partitionKey) {
                metadata.putString(XMLMessage.MessageUserPropertyConstants.QUEUE_PARTITION_KEY, partitionKey);
            } else {
                String msg = String.format("Incorrect type specified for header '%s'. Expected [%s] but actual type is [%s]", SolaceBinderHeaders.PARTITION_KEY, String.class, partitionKeyObj.getClass());
                SolaceMessageConversionException exception = new SolaceMessageConversionException(new IllegalArgumentException(msg));
                log.warn(msg, exception);
                throw exception;
            }
        }

        if (!serializedHeaders.isEmpty()) {
            String var1 = stringSetWriter.writeValueAsString(serializedHeaders);
            metadata.putString(SolaceBinderHeaders.SERIALIZED_HEADERS, var1);
            metadata.putString(SolaceBinderHeaders.SERIALIZED_HEADERS_ENCODING, DEFAULT_ENCODING.getName());
        }
        return metadata;
    }

    private static boolean isSpringPollutedHeader(Map.Entry<String, Object> header) {
        return header.getKey().equals("target-protocol") || // A not true header: https://github.com/spring-cloud/spring-cloud-stream/issues/2222
                (header.getKey().equals("id") && header.getValue() instanceof UUID) || // Message header pollution from: https://github.com/spring-projects/spring-framework/blob/6.2.x/spring-messaging/src/main/java/org/springframework/messaging/MessageHeaders.java#L137
                (header.getKey().equals("timestamp"));// JCSMP lib supports better supported header via config flags: GENERATE_RCV_TIMESTAMPS and GENERATE_SEND_TIMESTAMPS don't use the polluted version from: https://github.com/spring-projects/spring-framework/blob/6.2.x/spring-messaging/src/main/java/org/springframework/messaging/MessageHeaders.java#L151
    }

    private static boolean isJcsmpDefaultHeader(Map.Entry<String, Object> header) {
        return header.getKey().equalsIgnoreCase(IntegrationMessageHeaderAccessor.ACKNOWLEDGMENT_CALLBACK) ||
                header.getKey().equalsIgnoreCase(BinderHeaders.TARGET_DESTINATION) ||
                header.getKey().equalsIgnoreCase(SolaceBinderHeaders.CONFIRM_CORRELATION) ||
                SolaceHeaderMeta.META.containsKey(header.getKey()) ||
                SolaceBinderHeaderMeta.META.containsKey(header.getKey());
    }

    @SneakyThrows
    MessageHeaders map(SDTMap metadata, Collection<String> excludedHeaders) {
        if (metadata == null) {
            return new MessageHeaders(Collections.emptyMap());
        }

        final Collection<String> exclusionList = excludedHeaders != null ? excludedHeaders : Collections.emptyList();

        Map<String, Object> headers = new HashMap<>();

        // Deserialize headers
        if (!exclusionList.contains(SolaceBinderHeaders.SERIALIZED_HEADERS) && metadata.containsKey(SolaceBinderHeaders.SERIALIZED_HEADERS)) {
            Encoder encoder = null;
            if (metadata.containsKey(SolaceBinderHeaders.SERIALIZED_HEADERS_ENCODING)) {
                String encoding = metadata.getString(SolaceBinderHeaders.SERIALIZED_HEADERS_ENCODING);
                encoder = Encoder.getByName(encoding);
                if (encoder == null) {
                    String msg = String.format("%s encoding is not supported", encoding);
                    SolaceMessageConversionException exception = new SolaceMessageConversionException(msg);
                    log.warn(msg, exception);
                    throw exception;
                }
            }

            Set<String> serializedHeaders = stringSetReader.readValue(metadata.getString(SolaceBinderHeaders.SERIALIZED_HEADERS));

            for (String headerName : serializedHeaders) {
                if (metadata.containsKey(headerName)) {
                    byte[] serializedValue;
                    if (encoder != null) {
                        serializedValue = encoder.decode(metadata.getString(headerName));
                    } else {
                        serializedValue = metadata.getBytes(headerName);
                    }
                    Object value = SerializationUtils.deserialize(serializedValue);
                    if (value instanceof ByteArray) { // Just in case...
                        value = ((ByteArray) value).asBytes();
                    }
                    headers.put(headerName, value);
                }
            }
        }

        metadata.keySet().stream().filter(h -> !exclusionList.contains(h)).filter(h -> !headers.containsKey(h)).filter(h -> !SolaceBinderHeaderMeta.META.containsKey(h)).filter(h -> !SolaceHeaderMeta.META.containsKey(h)).forEach(h -> {
            Object value = null;
            try {
                value = metadata.get(h);
            } catch (SDTException e) {
                throw new RuntimeException(e);
            }
            if (value instanceof ByteArray byteArray) {
                value = byteArray.asBytes();

                if (metadata.containsKey(KAFKA_TOPIC)) { // Was imported via kafka bridge.
                    try {
                        value = new String((byte[]) value, StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        log.warn(
                                "Header conversion failed for Kafka byte[] header {} to String",
                                headers.keySet(),
                                e
                        );
                    }
                }
            }
            headers.put(h, value);
        });

        if (!exclusionList.contains(SolaceBinderHeaders.MESSAGE_VERSION) && metadata.containsKey(SolaceBinderHeaders.MESSAGE_VERSION)) {
            int messageVersion = metadata.getInteger(SolaceBinderHeaders.MESSAGE_VERSION);
            headers.put(SolaceBinderHeaders.MESSAGE_VERSION, messageVersion);
        }
        return new MessageHeaders(headers);
    }

    /**
     * Wrapper function which converts Serializable objects to byte[] if they aren't naturally supported by the SDTMap
     */
    @SneakyThrows
    private void addSDTMapObject(SDTMap sdtMap, Set<String> serializedHeaders, String key, Object object, boolean convertNonSerializableHeadersToString) {
        try {
            sdtMap.putObject(key, object);
        } catch (IllegalArgumentException | SDTException e) {
            if (object instanceof Serializable) {
                String var1 = DEFAULT_ENCODING.encode(SerializationUtils.serialize(object));
                sdtMap.putString(key, var1);
                serializedHeaders.add(key);
            } else if (convertNonSerializableHeadersToString && object != null) {
                if (log.isDebugEnabled()) {
                    log.debug(String.format("Irreversibly converting header %s to String", key));
                }
                sdtMap.putString(key, object.toString());
            } else {
                throw e;
            }
        }
    }

    enum Encoder {
        BASE64("base64", Base64.getEncoder()::encodeToString, Base64.getDecoder()::decode);

        @Getter
        private final String name;
        private final Function<byte[], String> encodeFnc;
        private final Function<String, byte[]> decodeFnc;

        private static final Map<String, Encoder> nameMap = new HashMap<>();

        static {
            Arrays.stream(Encoder.values()).forEach(e -> nameMap.put(e.getName(), e));
        }

        Encoder(String name, Function<byte[], String> encodeFnc, Function<String, byte[]> decodeFnc) {
            this.name = name;
            this.encodeFnc = encodeFnc;
            this.decodeFnc = decodeFnc;
        }

        public String encode(byte[] input) {
            return input != null ? encodeFnc.apply(input) : null;
        }

        public byte[] decode(String input) {
            return input != null ? decodeFnc.apply(input) : null;
        }

        public static Encoder getByName(String name) {
            return nameMap.get(name);
        }
    }
}
