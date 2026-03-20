package com.solace.test.integration.semp.v2;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;

@Slf4j
public class SempV2Api {
    private final com.solace.test.integration.semp.v2.action.api.AllApi actionApi;
    private final com.solace.test.integration.semp.v2.config.api.AllApi configApi;
    private final com.solace.test.integration.semp.v2.monitor.api.AllApi monitorApi;


    public SempV2Api(String mgmtHost, String mgmtUsername, String mgmtPassword) {
        OkHttpClient client = new OkHttpClient()
                .newBuilder()
                .hostnameVerifier((hostname, session) -> true)
                .build();
        log.trace("Creating Action API Clients for {}", mgmtHost);
        com.solace.test.integration.semp.v2.action.ApiClient actionApiClient = new com.solace.test.integration.semp.v2.action.ApiClient();
        actionApiClient.setBasePath(String.format("%s/SEMP/v2/action", mgmtHost));
        actionApiClient.setUsername(mgmtUsername);
        actionApiClient.setPassword(mgmtPassword);
        actionApiClient.setHttpClient(client);

        log.trace("Creating Config API Clients for {}", mgmtHost);
        com.solace.test.integration.semp.v2.config.ApiClient configApiClient = new com.solace.test.integration.semp.v2.config.ApiClient();
        configApiClient.setBasePath(String.format("%s/SEMP/v2/config", mgmtHost));
        configApiClient.setUsername(mgmtUsername);
        configApiClient.setPassword(mgmtPassword);
        configApiClient.setHttpClient(client);

        log.trace("Creating Monitor API Clients for {}", mgmtHost);
        com.solace.test.integration.semp.v2.monitor.ApiClient monitorApiClient = new com.solace.test.integration.semp.v2.monitor.ApiClient();
        monitorApiClient.setBasePath(String.format("%s/SEMP/v2/monitor", mgmtHost));
        monitorApiClient.setUsername(mgmtUsername);
        monitorApiClient.setPassword(mgmtPassword);
        monitorApiClient.setHttpClient(client);

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