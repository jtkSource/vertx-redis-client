package com.jtk.redisapp.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.jtk.redisapp.client.VRedisClient;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

public class RUserRole {
    public static final Logger log = LoggerFactory.getLogger(RUserRole.class);
    private final String userName;
    private final String role;

    @JsonIgnore
    private final RUserRole.Cache cache;

    public RUserRole(JsonObject userRoleJson) {
        this.userName = userRoleJson.getString("userId");
        this.role = userRoleJson.getString("role");
        this.cache = new RUserRole.Cache();
    }

    public RUserRole(String userName, String role) {
        this.userName = userName;
        this.role = role;
        this.cache = new RUserRole.Cache();

    }

    public String getRole() {
        return role;
    }

    public String getUserName() {
        return userName;
    }

    public static String getRedisKey(String userName) {
        return String.format(RedisKeys.USER_ROLES_ASSIGNMENT_KEY.getPattern(), userName);
    }

    @JsonIgnore
    public String getRedisKey() {
        return getRedisKey(userName);
    }

    @JsonIgnore
    public Cache getCache() {
        return cache;
    }

    public class Cache {
        public Future<RUserRole> assignRole(){
            Promise<RUserRole> promise = Promise.promise();
            String key = getRedisKey(userName);
            VRedisClient.getClient()
                    .send(Request.cmd(Command.SADD, key, role), event -> {
                        if (event.succeeded()){
                            log.info("Assigned Role:{} to user:{} ", role, userName);
                            promise.complete(RUserRole.this);
                        }else{
                            log.info("Failed to Assigned Role:{} to user:{} ", role, userName);
                            promise.fail(event.cause());
                        }
                    });
            return promise.future();
        }
        public Future<Boolean> hasRole(){
            Promise<Boolean> promise = Promise.promise();
            String key = getRedisKey(userName);
            VRedisClient.getClient()
                    .send(Request.cmd(Command.SISMEMBER, key, role), event -> {
                        if (event.succeeded()){
                            if(event.result().toLong() == 1){
                                promise.complete(true);
                            }else {
                                promise.complete(false);
                            }
                        }else{
                            promise.fail(event.cause());
                        }
                    });

            return promise.future();
        }

        public static Future<Set<String>> getRoles(String userName){
            Promise<Set<String>> promise = Promise.promise();
            String key = getRedisKey(userName);
            VRedisClient.getClient()
                    .send(Request.cmd(Command.SMEMBERS,key),event -> {
                        if(event.succeeded()){
                            Set<String> roles = new HashSet<>();
                            event.result().stream()
                                    .forEach(response -> roles.add(response.toString()));
                            promise.complete(roles);
                        }else {
                            promise.fail(event.cause());
                        }
                    });
            return promise.future();
        }
    }
}
