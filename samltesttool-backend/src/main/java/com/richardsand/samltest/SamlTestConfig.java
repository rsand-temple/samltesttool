package com.richardsand.samltest;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.dropwizard.core.Configuration;
import lombok.Getter;

@Getter
public class SamlTestConfig extends Configuration {
    @Getter
    public static class Database {
    	private String url;
    	private String adminUser;
    	private String adminPwd;
    }

    @Getter
    public static class Tenant {
    	private String tenantUrl;
    	private String apiKey;
    	private String apiSecret;
    }

    @JsonProperty
    private Database database;

    @JsonProperty
    private Tenant tenant;
}
