package com.richardsand.samltest;

import java.net.http.HttpClient;
import java.sql.SQLException;
import java.time.Duration;

import org.apache.commons.dbcp2.BasicDataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.richardsand.samltest.health.DataSourceHealthCheck;
import com.richardsand.samltest.resources.AicLogResource;
import com.richardsand.samltest.resources.MetadataResource;
import com.richardsand.samltest.services.AicLogService;

import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;

public class SamlTestServer extends Application<SamlTestConfig> {
    Logger logger = LoggerFactory.getLogger(getClass());

    boolean         isPostgres = false;
    BasicDataSource ds         = new BasicDataSource();

    @Override
    public void initialize(Bootstrap<SamlTestConfig> bootstrap) {
        bootstrap.setConfigurationSourceProvider(
                new SubstitutingSourceProvider(
                        bootstrap.getConfigurationSourceProvider(),
                        new EnvironmentVariableSubstitutor(false)));
    }

    @Override
    public void run(SamlTestConfig config, Environment env) throws Exception {
        String jdbcUrl   = config.getDatabase().getUrl();
        String adminUser = config.getDatabase().getAdminUser();
        String adminPwd  = config.getDatabase().getAdminPwd();

        // Optionally set driver class if you like (BasicDataSource can usually infer from URL)
        if (jdbcUrl != null && jdbcUrl.startsWith("jdbc:postgresql:")) {
            logger.info("Loading POSTGRESQL driver");
            ds.setDriverClassName("org.postgresql.Driver");
        } else if (jdbcUrl != null && jdbcUrl.startsWith("jdbc:h2:")) {
            logger.info("Loading H2 driver");
            ds.setDriverClassName("org.h2.Driver");
        }

        logger.debug("URL {}", jdbcUrl);
        ds.setUrl(jdbcUrl);
        ds.setUsername(adminUser);
        ds.setPassword(adminPwd);
        ds.setMinIdle(1);
        ds.setMaxIdle(5);
        ds.setMaxOpenPreparedStatements(100);
        ds.setDefaultAutoCommit(true);
        ds.setMaxTotal(15);
        ds.setMaxWait(Duration.ofMillis(10000)); // fail fast instead of hanging
        ds.setFastFailValidation(true);
        ds.setRemoveAbandonedOnBorrow(true);
        ds.setRemoveAbandonedTimeout(Duration.ofSeconds(30));
        ds.setLogAbandoned(true);

        try {
            String product = ds.getConnection().getMetaData().getDatabaseProductName();
            isPostgres = product != null && product.toLowerCase().contains("postgres");
        } catch (SQLException e) {
            isPostgres = false;
        }

        // Flyway migration, if necessary
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, adminUser, adminPwd)
                .locations("classpath:db/migration/" + ((isPostgres) ? "postgresql" : "h2"))
                .load();
        flyway.repair();
        flyway.migrate();

        // DAOs

        // Resources
        env.jersey().register(MetadataResource.class);

        // ObjectMapper
        ObjectMapper mapper = env.getObjectMapper();
        mapper.findAndRegisterModules();
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Make classes injectable to resources with HK2
        env.jersey().register(new org.glassfish.hk2.utilities.binding.AbstractBinder() {
            @Override
            protected void configure() {
                bind(mapper).to(ObjectMapper.class);
                bind(config).to(SamlTestConfig.class);
//                bind(projectDao).to(ProjectDao.class);

            }
        });

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        AicLogService logService = new AicLogService(
                http,
                mapper,
                config.getTenant().getTenantUrl(),
                config.getTenant().getApiKey(),
                config.getTenant().getApiSecret());

        env.jersey().register(new AicLogResource(logService));

        // Health checks
        env.healthChecks().register("database", new DataSourceHealthCheck(ds));
    }

    public static void main(String[] args) throws Exception {
        new SamlTestServer().run(args);
    }
}