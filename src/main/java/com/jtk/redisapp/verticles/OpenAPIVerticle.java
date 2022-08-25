package com.jtk.redisapp.verticles;

import com.jtk.redisapp.client.MainVerticle;
import com.jtk.redisapp.verticles.handler.HandlerFactory;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.openapi.RouterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

public class OpenAPIVerticle extends AbstractVerticle {
    private static final Logger log = LoggerFactory.getLogger(OpenAPIVerticle.class);
    private UUID uuid;

    @Override
    public void start(Promise<Void> startPromise) throws Exception {
        uuid = UUID.randomUUID();
        String configPath = MainVerticle.getConfigPath();
        log.info("Path for OpenAPI spec in {}", configPath);
        RouterBuilder.create(this.vertx, configPath + "/web-api.yaml")
                .onSuccess(routerBuilder -> HandlerFactory.buildRoutes(getVertx(), routerBuilder))
                .onComplete(routerBuilderAsyncResult -> {
                    if (routerBuilderAsyncResult.succeeded()) {
                        log.info("Completed building Router from API");
                        Router router = routerBuilderAsyncResult.result().createRouter();
                        addErrorHandler(router);
                        createHttpServer(startPromise, router);
                    } else {
                        log.info("Failed building Router from API");
                        log.error("Unexpected Exception", routerBuilderAsyncResult.cause());
                        startPromise.fail(routerBuilderAsyncResult.cause());
                    }
                });
    }

    private void createHttpServer(Promise<Void> startPromise, Router router) {
        vertx.createHttpServer(new HttpServerOptions().setSsl(false))
                .requestHandler(router)
                .listen(config().getInteger("http.server.port", 8080))
                .onSuccess(server -> {
                    log.info("Registered routes: \n{} ",
                            router.getRoutes().stream()
                                    .filter(route -> route.getName() != null && route.methods() != null)
                                    .map(route ->
                                            route.methods().stream()
                                                    .filter(Objects::nonNull)
                                                    .map(HttpMethod::toString)
                                                    .collect(Collectors.joining(",")) + " " + route.getName())
                                    .collect(Collectors.joining(",\n")));
                    log.info("HTTP server [{}] started on port {}", uuid.toString(), server.actualPort());
                    startPromise.complete();
                }).onFailure(throwable -> {
                    log.error("Failed to create HttpServer", throwable);
                    startPromise.fail(throwable);
                });
    }

    private void addErrorHandler(Router router) {
        router.errorHandler(HttpResponseStatus.NOT_FOUND.code(), routingContext -> {
            JsonObject errorObject = new JsonObject();
            errorObject.put("message", "something went wrong on " + routingContext.normalizedPath());
            errorObject.put("code", HttpResponseStatus.NOT_FOUND.code());

            routingContext.response()
                    .setStatusCode(HttpResponseStatus.NOT_FOUND.code())
                    .putHeader(HttpHeaders.CONTENT_TYPE, "plain/text")
                    .end(errorObject.encodePrettily());
        });
    }
}
