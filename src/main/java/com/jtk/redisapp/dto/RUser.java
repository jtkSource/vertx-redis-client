package com.jtk.redisapp.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.jtk.redisapp.client.VRedisClient;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public class RUser {

    public static final Logger log = LoggerFactory.getLogger(RUser.class);
    private final String userName;
    private final String password;
    private final String name;

    private final Instant createdTime;
    @JsonIgnore
    private final Cache cache;

    public RUser(JsonObject users) {
        this.userName = users.getString("userName");
        this.password = users.getString("password");
        this.name = users.getString("name");
        this.createdTime = Instant.now();
        this.cache = new Cache();
    }

    public RUser(JsonObject users, Instant createdTime) {
        this.userName = users.getString("userName");
        this.password = users.getString("password");
        this.name = users.getString("name");
        this.createdTime = createdTime;
        this.cache = new Cache();
    }

    public static String getRedisKey(String userName) {
        return String.format("corda:users#%s", userName);
    }

    @JsonIgnore
    public String getRedisKey() {
        return getRedisKey(userName);
    }

    public String getUserName() {
        return userName;
    }

    public String getPassword() {
        return password;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedTime() {
        return createdTime;
    }

    public Cache getCache() {
        return cache;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RUser rUser = (RUser) o;
        return userName.equals(rUser.userName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userName);
    }

    public class Cache {
        public static Future<RUser> getUser(String userId) {
            Promise<RUser> userPromise = Promise.promise();
            String userKey = RUser.getRedisKey(userId);
            try {
                VRedisClient.getClient()
                        .send(Request.cmd(Command.HGETALL, userKey), event -> {
                            if (event.succeeded() && event.result().size() > 0) {
                                log.info("Found UserId: {} in cache ", userKey);
                                Response result = event.result();
                                Set<String> keys = result.getKeys();
                                JsonObject userJson = new JsonObject();
                                Instant cTime = Instant.now();
                                for (String key : keys) {
                                    if (key.equals("createdTime")) {
                                        Long epoch = Long.valueOf(result.get("createdTime").toString());
                                        cTime = Instant.ofEpochMilli(epoch);
                                    } else {
                                        result.get(key).toString();
                                    }
                                    userJson.put(key, result.get(key).toString());
                                }
                                userJson.put("userName", userId);
                                RUser user = new RUser(userJson, cTime);
                                userPromise.complete(user);
                            } else {
                                log.error("Unexpected Exception", event.cause());
                                userPromise.fail(event.cause());
                            }
                        });
            }catch (Exception e){
                log.error("Unable to fetch Users", e);
                userPromise.fail(e);
            }
            return userPromise.future();
        }

        public void createUser() {
            //redis doesnt generate id
            String key = getRedisKey();
            VRedisClient.getClient()
                    .send(Request.cmd(
                            Command.HSET,
                            key,
                            "name", getName(),
                            "password", getPassword(),
                            "createdTime", String.valueOf(getCreatedTime().toEpochMilli())
                            // everything in redis is a string.
                            // so store time in epoch for querying ability
                    ));
        }
    }
}
