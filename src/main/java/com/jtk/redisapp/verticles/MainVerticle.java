package com.jtk.redisapp.verticles;


import com.google.common.collect.ImmutableList;
import com.jtk.redisapp.client.VRedisClient;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.CompositeFuture;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Launcher;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MainVerticle extends AbstractVerticle {
    private static final Logger log = LoggerFactory.getLogger(MainVerticle.class);
    private static String CONFIG_PATH = "";
    private DeploymentOptions deploymentOptions;
    private final Map<String, String> deploymentMap = new HashMap<>();

    public static String getConfigPath() {
        return CONFIG_PATH;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        log.info("Starting MainVertX...");
        log.info("Configuring VertX...");
        ConfigRetriever configRetriever = getAppConfig();
        configRetriever.getConfig(rs -> {
            if (rs.succeeded()) {
                JsonObject config = rs.result();
                VertxOptions options = new VertxOptions(config);
                vertx = Vertx.vertx(options);
                VRedisClient.init(vertx, config);
                deploymentOptions = new DeploymentOptions();
                deploymentOptions.setConfig(config);
                deploymentOptions.setWorkerPoolSize(config.getInteger("vertx.worker.pool.size", 3));
                deploymentOptions.setWorkerPoolName("vert.x-workerloop");
                deploymentOptions.setWorker(true);
                deploymentOptions.setMaxWorkerExecuteTime(config.getInteger("vertx.worker.executetime.ms"));
                deploymentOptions.setMaxWorkerExecuteTimeUnit(TimeUnit.MILLISECONDS);
                CompositeFuture.join(deployAppVerticles(config))
                        .onComplete(result -> {
                            if(result.succeeded()) {
                                log.info("Completed all verticles !!! ");
                                log.info("deployments:[{}]", deploymentMap);

                                startPromise.complete();
                            }else {
                                log.error("Error deploying Verticles...");
                                log.info("deployments:[{}]", deploymentMap);
                                startPromise.fail(result.cause());
                            }
                        });

                log.info("Started MainVertx");
            } else {
                log.error("Config Exception ", rs.cause());
                startPromise.fail("Failed to retrieve Config");
            }
        });
    }
    private ConfigRetriever getAppConfig() {
        //The Vert.x Config module allows an application to pull it's configuration from a number of different source,
        // either in isolation or in combination.
        // One of the options as a source for configuration information is a Kubernetes/OpenShift ConfigMap
        String appConfig = CONFIG_PATH  + "/app-prop.json";
        log.info("App config: {}", appConfig);
        ConfigStoreOptions fileStore = new ConfigStoreOptions()
                .setType("file")
                .setOptional(false)
                .setConfig(new JsonObject().put("path", appConfig));
        List<ConfigStoreOptions> storeOptionsList = ImmutableList.of(fileStore);
        ConfigRetrieverOptions configRetrieverOptions = new ConfigRetrieverOptions()
                .setScanPeriod(5000) // scan for changes
                .setStores(storeOptionsList);
        ConfigRetriever configRetriever = ConfigRetriever.create(vertx, configRetrieverOptions);
        // changes to configstore
        configRetriever.listen(configChange -> {
            JsonObject json = configChange.getNewConfiguration();
            log.info("config changed {}", json);
            try {
                undeploy();
            } catch (InterruptedException e) {
                log.error("Unexpected error", e);
            }
            deploymentOptions.setConfig(json);
            log.info("Redeploying verticles...");
            CompositeFuture
                    .join(deployAppVerticles(json))
                    .onComplete(result -> {
                        if (result.succeeded()) {
                            log.info("Completed all verticles !!! \n deploymentId:{}", deploymentMap);
                        }else {
                            log.error("Error deploying verticles ", result.cause());
                        }
                    });
        });
        return configRetriever;
    }

    private void undeploy() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(deploymentMap.size());
        deploymentMap.entrySet().stream()
                .forEach(entry -> vertx.undeploy(entry.getValue(), result -> {
                    if (result.succeeded()) {
                        deploymentMap.remove(entry.getKey());
                        log.info("Undeployed verticle {}", entry.getValue());
                    } else {
                        log.error("failed to undeploy {}", entry.getValue(), result.cause());
                    }
                    latch.countDown();
                }));
        latch.await();
    }
    private List<Future> deployAppVerticles(JsonObject json) {
        return ImmutableList
                .of(deployHelper(OpenAPIVerticle.class, new DeploymentOptions(deploymentOptions).setInstances(1)));
    }
    private Future<?> deployHelper(Class vertxClass, DeploymentOptions deploymentOptions) {
        return Future.future(voidPromise -> {
            if (!deploymentMap.containsKey(vertxClass.getName())) {
                vertx.deployVerticle(vertxClass.getName(), deploymentOptions,
                        result -> {
                            if (result.succeeded()) {
                                deploymentMap.put(vertxClass.getName(), result.result());
                                voidPromise.complete();
                            } else {
                                voidPromise.fail(result.cause());
                            }
                        });
            } else {
                log.error("{} is already deployed with id {}", vertxClass.getName(), deploymentMap.get(vertxClass.getName()));
            }
        });
    }

    public static void main(String[] args) {
        log.info("log4j property: {}",System.getProperty("log4j.configurationFile"));
        log.info("Starting Vertx...");
        CONFIG_PATH = args[0];
        log.info("Config path: {}", CONFIG_PATH);
        Launcher.executeCommand("run", MainVerticle.class.getName());
    }
}
