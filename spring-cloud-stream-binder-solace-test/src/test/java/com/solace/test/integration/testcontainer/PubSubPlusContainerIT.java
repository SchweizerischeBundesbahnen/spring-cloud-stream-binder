package com.solace.test.integration.testcontainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(parallel = true)
public class PubSubPlusContainerIT {
	@Container
	private static final PubSubPlusContainer CONTAINER = new PubSubPlusContainer();
	private static final Logger LOG = LoggerFactory.getLogger(PubSubPlusContainerIT.class);

	@Test
	public void testStatus() {
		assertTrue(CONTAINER.isRunning());
	}

	@ParameterizedTest
	@EnumSource(PubSubPlusContainer.Port.class)
	public void testGetOrigin(PubSubPlusContainer.Port port) {
		if (PubSubPlusContainer.Port.SSH.equals(port)) {
			IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
					CONTAINER.getOrigin(port));
			assertThat(exception.getMessage(), containsString("Getting origin of port SSH is not supported"));
		} else {
			assertNotNull(CONTAINER.getOrigin(port));
		}
	}

	@Test
	public void testGetSshPort() {
		assertNotNull(CONTAINER.getSshPort());
	}

	@Test
	public void testSemp() {
		WebClient client = WebClient.builder()
				.baseUrl(CONTAINER.getOrigin(PubSubPlusContainer.Port.SEMP))
				.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.defaultHeaders(h -> h.setBasicAuth(CONTAINER.getAdminUsername(), CONTAINER.getAdminPassword()))
				.build();
		Optional<JsonNode> response = client.get()
				.uri("/SEMP/v2/monitor/about/api")
				.retrieve()
				.bodyToMono(JsonNode.class)
				.blockOptional(Duration.ofSeconds(30));
		LOG.info("Response: {}", response.orElse(null));
		assertEquals("VMR", response.map(n -> n.get("data"))
				.map(n -> n.get("platform"))
				.map(JsonNode::asText)
				.orElseThrow(() -> new IllegalStateException("Unexpected response")));
	}

	@Test
	public void testSmf() throws Exception {
		JCSMPProperties properties = new JCSMPProperties();
		properties.setProperty(JCSMPProperties.HOST, CONTAINER.getOrigin(PubSubPlusContainer.Port.SMF));
		properties.setProperty(JCSMPProperties.USERNAME, "default");
		JCSMPSession jcsmpSession = null;
		try {
			jcsmpSession = JCSMPFactory.onlyInstance().createSession(properties);
			jcsmpSession.connect();
		} finally {
			if (jcsmpSession != null) {
				jcsmpSession.closeSession();
			}
		}
	}
}
