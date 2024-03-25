package com.solace.test.integration.semp.v2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SempV2Api {
	private final com.solace.test.integration.semp.v2.action.api.AllApi actionApi;
	private final com.solace.test.integration.semp.v2.config.api.AllApi configApi;
	private final com.solace.test.integration.semp.v2.monitor.api.AllApi monitorApi;

	private static final Logger LOG = LoggerFactory.getLogger(SempV2Api.class);

	public SempV2Api(String mgmtHost, String mgmtUsername, String mgmtPassword) {
		LOG.info("Creating Action API Clients for {}", mgmtHost);
		com.solace.test.integration.semp.v2.action.ApiClient actionApiClient = new com.solace.test.integration.semp.v2.action.ApiClient();
		actionApiClient.setBasePath(String.format("%s/SEMP/v2/action", mgmtHost));
		actionApiClient.setUsername(mgmtUsername);
		actionApiClient.setPassword(mgmtPassword);

		LOG.info("Creating Config API Clients for {}", mgmtHost);
		com.solace.test.integration.semp.v2.config.ApiClient configApiClient = new com.solace.test.integration.semp.v2.config.ApiClient();
		configApiClient.setBasePath(String.format("%s/SEMP/v2/config", mgmtHost));
		configApiClient.setUsername(mgmtUsername);
		configApiClient.setPassword(mgmtPassword);

		LOG.info("Creating Monitor API Clients for {}", mgmtHost);
		com.solace.test.integration.semp.v2.monitor.ApiClient monitorApiClient = new com.solace.test.integration.semp.v2.monitor.ApiClient();
		monitorApiClient.setBasePath(String.format("%s/SEMP/v2/monitor", mgmtHost));
		monitorApiClient.setUsername(mgmtUsername);
		monitorApiClient.setPassword(mgmtPassword);

		this.actionApi = new com.solace.test.integration.semp.v2.action.api.AllApi(actionApiClient);
		this.configApi = new com.solace.test.integration.semp.v2.config.api.AllApi(configApiClient);
		this.monitorApi = new com.solace.test.integration.semp.v2.monitor.api.AllApi(monitorApiClient);
	}

	public com.solace.test.integration.semp.v2.action.api.AllApi action() {
		return actionApi;
	}

	public com.solace.test.integration.semp.v2.config.api.AllApi config() {
		return configApi;
	}

	public com.solace.test.integration.semp.v2.monitor.api.AllApi monitor() {
		return monitorApi;
	}
}