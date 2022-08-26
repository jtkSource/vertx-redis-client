package com.jtk.redisapp.verticles.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jtk.redisapp.client.VRedisClient;
import com.jtk.redisapp.dto.*;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import io.vertx.ext.web.validation.RequestParameters;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.*;

public class HandlerFactory {
    private static final Logger log = LoggerFactory.getLogger(HandlerFactory.class);
    private static final ObjectMapper jsonMapper = new ObjectMapper();

    static {
        jsonMapper.registerModule(new JavaTimeModule());
    }

    public static void buildRoutes(Vertx vertx, RouterBuilder routerBuilder) {


        buildBondAPI(routerBuilder);
        buildUserManagementAPI(routerBuilder);

        routerBuilder
                .operation("get-keys")
                .handler(routingContext -> {
                    RequestParameters params = routingContext.get("parsedParameters");
                    JsonObject patternJson = params.body().getJsonObject();
                    Promise<List<String>> promise = Promise.promise();
                    VRedisClient.getClient()
                            .send(Request.cmd(Command.KEYS, patternJson.getString("pattern")),event -> {
                                if(event.succeeded()){
                                    List<String> keys = new ArrayList<>();
                                    event.result().stream()
                                            .forEach(response -> keys.add(response.toString()));
                                    promise.complete(keys);
                                }else {
                                    promise.fail(event.cause());
                                }
                            });
                    promise.future()
                            .onComplete(event -> {
                               if(event.succeeded()){
                                   JsonArray keyArray = new JsonArray();
                                   event.result().forEach(keyArray::add);
                                   handleResponse(routingContext, keyArray.toString());
                               }else {
                                   handleExceptionResponse(routingContext, event.cause());
                               }
                            });
                });

    }

    private static void buildBondAPI(RouterBuilder routerBuilder) {
        routerBuilder
                .operation("issue-bond-terms")
                .handler(routingContext -> {
                    hasRole(getUserFromHeader(routingContext), "CompanyAdmin")
                            .onComplete(authEvent -> {
                                if (authEvent.succeeded() && authEvent.result() == Boolean.TRUE) {
                                    RequestParameters params = routingContext.get("parsedParameters");
                                    JsonObject term = params.body().getJsonObject();
                                    new BondTerm(term)
                                            .getCache()
                                            .createTerm()
                                            .onComplete(event -> {
                                                if (event.succeeded()) {
                                                    BondTerm cBt = event.result();
                                                    handleResponse(routingContext,
                                                            new JsonObject().put("bondName", cBt.getBondName()));
                                                } else {
                                                    log.error("Failed to cache bond term:{}", term.getString("bondName")
                                                            , event.cause());
                                                    handleExceptionResponse(routingContext, event.cause());
                                                }
                                            });
                                } else {
                                    if (authEvent.failed()) {
                                        log.error("Unexpected Exception", authEvent.cause());
                                        handleExceptionResponse(routingContext, authEvent.cause());
                                    } else
                                        handleExceptionResponse(routingContext, new Throwable("Couldnt authorize User"));
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
                                if (event.succeeded()) {
                                    BondTerm bondTerm = event.result();
                                    try {
                                        handleResponse(routingContext, jsonMapper.writeValueAsString(bondTerm));
                                    } catch (JsonProcessingException e) {
                                        handleExceptionResponse(routingContext, e);
                                    }
                                } else {
                                    handleExceptionResponse(routingContext, event.cause());
                                }
                            });
                });

        routerBuilder
                .operation("request-bond")
                .handler(routingContext -> {
                    String userId = getUserFromHeader(routingContext);
                    hasRole(userId, "Trader")
                            .onComplete(authEvent -> {
                                if (authEvent.succeeded() && authEvent.result() == Boolean.TRUE) {
                                    RequestParameters params = routingContext.get("parsedParameters");
                                    JsonObject bondTerm = params.body().getJsonObject();
                                    final String bondName = bondTerm.getString("bondName");
                                    final Integer unitsAssigned = bondTerm.getInteger("unitsAssigned");
                                    final String redisKey = BondTerm.getRedisKey(bondName);

                                    Promise<JsonObject> termDetailsPromise = Promise.promise();
                                    VRedisClient.getClient()
                                            .send(Request.cmd(Command.HMGET, redisKey,"unitsAvailable", "maturityDate"),
                                             event -> {
                                                if (event.succeeded()) {
                                                 JsonObject jsonObject = new JsonObject();
                                                 jsonObject.put("unitsAvailable",event.result().get(0).toInteger())
                                                         .put("maturityDate", event.result().get(1).toString());
                                                 termDetailsPromise.complete(jsonObject);
                                                }else {
                                                    termDetailsPromise.fail(event.cause());
                                                }
                                            });
                                    termDetailsPromise.future()
                                                    .onComplete(result ->{
                                                        if(result.succeeded()){
                                                            JsonObject termDetails = result.result();
                                                            if(termDetails.getInteger("unitsAvailable") <  unitsAssigned){
                                                                handleExceptionResponse(routingContext, new Throwable("Not enough available Units "));
                                                            }else {
                                                                // perform the transaction...
                                                                new BondAssignment(userId, bondName, unitsAssigned,
                                                                        termDetails.getString("maturityDate"),
                                                                        LocalDate.now().plusDays(2)).getCache()
                                                                        .assignBondsToUser()
                                                                        .onComplete(event -> {
                                                                            if(event.succeeded() && event.result()){
                                                                                handleResponse(routingContext, new JsonObject()
                                                                                        .put("userId",userId)
                                                                                        .put("bondName",bondName)
                                                                                        .put("unitsAssigned",unitsAssigned)
                                                                                );
                                                                            }else {
                                                                                if(event.failed()) {
                                                                                    handleExceptionResponse(routingContext, event.cause());
                                                                                }else {
                                                                                    handleExceptionResponse(routingContext, new Throwable("Couldnt assign bond to user"));
                                                                                }
                                                                            }
                                                                        });
                                                            }
                                                        }else {
                                                            handleExceptionResponse(routingContext, new Throwable("No UnitsAvailable "));
                                                        }
                                                    });



                                } else {
                                    if (authEvent.failed()) {
                                        log.error("Unexpected Exception", authEvent.cause());
                                        handleExceptionResponse(routingContext, authEvent.cause());
                                    } else
                                        handleExceptionResponse(routingContext, new Throwable("Couldnt authorize User"));
                                }
                            });
                });

        routerBuilder
                .operation("get-bonds-on-user")
                        .handler(routingContext -> {
                            RequestParameters params = routingContext.get("parsedParameters");
                            String userName = params.pathParameter("userId").getString();
                            BondAssignment.Cache.getBonds(userName)
                                    .onComplete(event -> {
                                        if (event.succeeded()){
                                            JsonArray array = new JsonArray(event.result().stream().toList());
                                            handleResponse(routingContext, array.toString());
                                        }else {
                                            handleExceptionResponse(routingContext, event.cause());
                                        }
                                    });
                        });
        routerBuilder
                .operation("get-bond-details")
                .handler(routingContext -> {
                    RequestParameters params = routingContext.get("parsedParameters");
                    String bondId = params.pathParameter("bondId").getString();
                    JsonArray arrayOfBondDetails = new JsonArray();
                    if(bondId.equalsIgnoreCase("all")){
                        BondDetails.Cache.getAllBondDetails()
                                .onComplete(event -> {
                                    if(event.succeeded()){
                                        List<BondDetails> listOfBonds = event.result();
                                        boolean exception = false;
                                        for (int i = 0; i < listOfBonds.size(); i++) {
                                            try {
                                                arrayOfBondDetails.add(new JsonObject(jsonMapper.writeValueAsString(listOfBonds.get(i))));
                                            } catch (JsonProcessingException e) {
                                                log.error("Unable to parse json", e);
                                                handleExceptionResponse(routingContext, e);
                                                exception = true;
                                                break;
                                            }
                                        }
                                        if(!exception){
                                            handleResponse(routingContext,arrayOfBondDetails.toString());
                                        }
                                    }else {
                                        handleExceptionResponse(routingContext, event.cause());
                                    }
                                });
                    }else {
                        BondDetails.Cache.getBondsDetails(bondId)
                                .onComplete(event -> {
                                    if (event.succeeded()) {
                                        BondDetails details = event.result();
                                        try {
                                            arrayOfBondDetails.add(new JsonObject(jsonMapper.writeValueAsString(details)));
                                            handleResponse(routingContext, arrayOfBondDetails.toString());
                                        } catch (JsonProcessingException e) {
                                            handleExceptionResponse(routingContext, e);
                                        }
                                    } else {
                                        handleExceptionResponse(routingContext, event.cause());
                                    }
                                });
                    }
                });
    }

    private static void buildUserManagementAPI(RouterBuilder routerBuilder) {
        routerBuilder
                .operation("create-users")
                .handler(routingContext ->
                        hasRole(getUserFromHeader(routingContext), "UserAdmin")
                                .onComplete(event -> {
                                    if (event.succeeded() && event.result() == Boolean.TRUE) {
                                        RequestParameters params = routingContext.get("parsedParameters");
                                        JsonObject users = params.body().getJsonObject();
                                        RUser user = new RUser(users);
                                        try {
                                            user.getCache().createUser();
                                            handleResponse(routingContext, new JsonObject().put("userId", user.getUserName()));
                                        } catch (Exception e) {
                                            handleExceptionResponse(routingContext, e);
                                        }
                                    } else {
                                        if (event.failed()) {
                                            log.error("Unexpected Exception", event.cause());
                                            handleExceptionResponse(routingContext, event.cause());
                                        } else
                                            handleExceptionResponse(routingContext, new Throwable("Couldnt authorize User"));
                                    }
                                }));
        routerBuilder
                .operation("get-users")
                .handler(routingContext -> {
                    RequestParameters params = routingContext.get("parsedParameters");
                    String userId = params.pathParameter("userId").getString();
                    RUser.Cache.getUser(userId)
                            .onComplete(event -> {
                                if (event.succeeded()) {
                                    RUser user = event.result();
                                    try {
                                        handleResponse(routingContext, jsonMapper.writeValueAsString(user));
                                    } catch (JsonProcessingException e) {
                                        handleExceptionResponse(routingContext, e);
                                    }
                                } else {
                                    handleExceptionResponse(routingContext, event.cause());
                                }
                            });
                });
        routerBuilder
                .operation("assign-user-role")
                .handler(routingContext -> {
                    hasRole(getUserFromHeader(routingContext), "UserAdmin")
                            .onComplete(authEvent -> {
                                if (authEvent.succeeded() && authEvent.result() == Boolean.TRUE) {
                                    RequestParameters params = routingContext.get("parsedParameters");
                                    JsonObject userRoleJson = params.body().getJsonObject();
                                    RUserRole userRole = new RUserRole(userRoleJson);
                                    userRole.getCache()
                                            .assignRole()
                                            .onComplete(event -> {
                                                if (event.succeeded()) {
                                                    RUserRole ur = event.result();
                                                    try {
                                                        handleResponse(routingContext, jsonMapper.writeValueAsString(ur));
                                                    } catch (JsonProcessingException e) {
                                                        handleExceptionResponse(routingContext, e);
                                                    }
                                                } else {
                                                    handleExceptionResponse(routingContext, event.cause());
                                                }
                                            });
                                } else {
                                    if (authEvent.failed()) {
                                        log.error("Unexpected Exception", authEvent.cause());
                                        handleExceptionResponse(routingContext, authEvent.cause());
                                    } else
                                        handleExceptionResponse(routingContext, new Throwable("Couldnt authorize User"));
                                }
                            });
                });
        routerBuilder
                .operation("get-user-role")
                .handler(routingContext -> {
                    RequestParameters params = routingContext.get("parsedParameters");
                    String userId = params.pathParameter("userId").getString();
                    RUserRole.Cache.getRoles(userId)
                            .onComplete(event -> {
                                if (event.succeeded()) {
                                    Set<String> setOfRoles = event.result();
                                    JsonArray array = new JsonArray();
                                    setOfRoles.stream().forEach(role -> array.add(role));
                                    handleResponse(routingContext, array.toString());
                                } else {
                                    handleExceptionResponse(routingContext, event.cause());
                                }
                            });
                });
    }

    private static Future<Boolean> hasRole(String userName, String role) {
        if(role.equals("*")){
            Promise<Boolean> promise = Promise.promise();
            promise.complete(true);
            return promise.future();
        }
        return new RUserRole(userName, role)
                .getCache()
                .hasRole();

    }

    private static String getUserFromHeader(RoutingContext routingContext) {
        if (routingContext.request().headers().contains(HttpHeaderNames.AUTHORIZATION)) {
            String authHeader = routingContext.request().getHeader(HttpHeaderNames.AUTHORIZATION);
            String user_PasswordBytes = authHeader.split(" ")[1];
            return new String(Base64.getDecoder().decode(user_PasswordBytes)).split(":")[0];
        } else
            return "";
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
