package com.jtk.redisapp.verticles.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jtk.redisapp.dto.BondTerm;
import com.jtk.redisapp.dto.RUser;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import io.vertx.ext.web.validation.RequestParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

public class HandlerFactory {
    private static final Logger log = LoggerFactory.getLogger(HandlerFactory.class);
    private static final ObjectMapper jsonMapper = new ObjectMapper();

    static {
        jsonMapper.registerModule(new JavaTimeModule());
    }
    public static void buildRoutes(Vertx vertx, RouterBuilder routerBuilder) {
        routerBuilder
                .operation("issue-bond-terms")
                .handler(routingContext -> {
                    RequestParameters params = routingContext.get("parsedParameters");
                    JsonObject term = params.body().getJsonObject();
                    BondTerm bt = new BondTerm(term);
                    bt.getCache()
                            .createTerm()
                            .onComplete(event -> {
                                if(event.succeeded()){
                                    BondTerm cBt = event.result();
                                    handleResponse(routingContext,
                                            new JsonObject().put("bondName",cBt.getBondName()));
                                }else {
                                    log.error("Failed to cache bond term:{}", bt.getBondName(),event.cause());
                                    handleExceptionResponse(routingContext, event.cause());
                                }
                            });
                });
        routerBuilder
                .operation("get-bond-terms")
                .handler(routingContext -> {
                    RequestParameters params = routingContext.get("parsedParameters");
                    String bondName = params.pathParameter("bondName").getString();
                    BondTerm.Cache.getBondTerm(bondName)
                            .onComplete(event -> {
                                if(event.succeeded()){
                                    BondTerm bondTerm = event.result();
                                    try {
                                        handleResponse(routingContext, jsonMapper.writeValueAsString(bondTerm));
                                    } catch (JsonProcessingException e) {
                                        handleExceptionResponse(routingContext, e);
                                    }
                                }else {
                                    handleExceptionResponse(routingContext, event.cause());
                                }
                            });
                });

        routerBuilder
                .operation("create-users")
                .handler(routingContext -> {
                    RequestParameters params = routingContext.get("parsedParameters");
                    JsonObject users = params.body().getJsonObject();
                    RUser user = new RUser(users);
                    try {
                        user.getCache().createUser();
                        handleResponse(routingContext,new JsonObject().put("userId",user.getUserName()));
                    }catch (Exception e){
                        handleExceptionResponse(routingContext, e);
                    }

                });
        routerBuilder
                .operation("get-users")
                .handler(routingContext -> {
                    RequestParameters params = routingContext.get("parsedParameters");
                    String userId = params.pathParameter("userId").getString();
                    RUser.Cache.getUser(userId)
                            .onComplete(event -> {
                                if(event.succeeded()){
                                    RUser user = event.result();
                                    try {
                                        handleResponse(routingContext, jsonMapper.writeValueAsString(user));
                                    } catch (JsonProcessingException e) {
                                        handleExceptionResponse(routingContext, e);
                                    }
                                }else {
                                    handleExceptionResponse(routingContext, event.cause());
                                }
                            });
                });
    }
    private static void handleExceptionResponse(RoutingContext routingContext, Throwable e) {
        String jsonMsg = String.format("{'msg':'%s'}", e.getMessage());
        routingContext.response()
                .setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR.code())
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(jsonMsg);
    }

    private static void handleResponse(RoutingContext routingContext, String json) {
        routingContext.response()
                .setStatusCode(HttpResponseStatus.OK.code())
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(json);
    }

    private static void handleResponse(RoutingContext routingContext, JsonObject msg) {
        routingContext.response()
                .setStatusCode(HttpResponseStatus.OK.code())
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(msg.encodePrettily());
    }
}
