package com.jtk.redisapp.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.jtk.redisapp.client.VRedisClient;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.stream.Collectors;

import static com.jtk.redisapp.dto.RedisKeys.LIST_BOND_KEYS;

public class BondAssignment {
    private static final Logger log = LoggerFactory.getLogger(BondAssignment.class);

    private static final DateTimeFormatter bondDateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");
    private LocalDate nextCouponDate;
    private String maturityDate;
    private String userId;
    final String bondName;
    final Integer unitsAssigned;
    @JsonIgnore
    private final Cache cache;
    @JsonIgnore
    private final String bondTermKey;
    @JsonIgnore
    private final String userToBondAssignmentKey; //corda:bt:user:assign:bonds#%s
    @JsonIgnore
    private String bondToUserAssignmentKey; //"corda:bt:bond:assign:users#%s"
    @JsonIgnore
    private String bondsDetailsKey; //"corda:bt:bond:details#%s"

    public BondAssignment(String userId,
                          String bondName,
                          Integer unitsAssigned,
                          String maturityDate,
                          LocalDate nextCouponDate) {
        this.userId = userId;
        this.bondName = bondName;
        this.unitsAssigned = unitsAssigned;
        this.nextCouponDate = nextCouponDate;
        this.maturityDate = maturityDate;
        this.bondTermKey = BondTerm.getRedisKey(bondName);
        this.userToBondAssignmentKey = getUserToBondAssignmentKey(userId);
        this.cache = new Cache();
    }

    public static String getUserToBondAssignmentKey(String userId) {
        return String.format(RedisKeys.USER_TO_BOND_ASSIGNMENT_KEY.getPattern(), userId);
    }

    public String getUserToBondAssignmentKey() {
        return userToBondAssignmentKey;
    }

    public String getBondName() {
        return bondName;
    }

    public Integer getUnitsAssigned() {
        return unitsAssigned;
    }

    public String getBondTermKey() {
        return bondTermKey;
    }

    public String getUserId() {
        return userId;
    }

    public LocalDate getNextCouponDate() {
        return nextCouponDate;
    }

    public String getMaturityDate() {
        return maturityDate;
    }

    public Cache getCache() {
        return cache;
    }

    public class Cache {

        public static Future<Set<String>> getBonds(String userName) {
            Promise<Set<String>> promise = Promise.promise();
            VRedisClient.getClient()
                    .send(Request.cmd(Command.SMEMBERS, BondAssignment.getUserToBondAssignmentKey(userName)),
                            event -> {
                                if (event.succeeded()) {
                                    Set<String> bonds = event.result().stream()
                                            .map(response -> response.toString())
                                            .collect(Collectors.toSet());
                                    promise.complete(bonds);
                                } else {
                                    promise.fail(event.cause());
                                }
                            });
            return promise.future();
        }

        public Future<Boolean> assignBondsToUser() {
            Promise<Boolean> promise = Promise.promise();
            Promise<JsonObject> validTransaction = Promise.promise();
            RedisConnection conn = VRedisClient
                    .getClient()
                    .send(Request.cmd(Command.WATCH, bondTermKey),
                            event -> {
                                if (event.succeeded() && event.result().toString().equals("OK")) {
                                    log.info("Watching key [{}] for changes", bondTermKey);
                                }
                            })
                    .send(Request.cmd(Command.HMGET, bondTermKey, "unitsAvailable", "numberOfBonds"),
                            event -> {
                                if (event.succeeded()) {
                                    Integer unitsAvailable = event.result().get(0).toInteger();
                                    JsonObject jsonObject = new JsonObject()
                                            .put("unitsAvailable", unitsAvailable)
                                            .put("numberOfBonds", event.result().get(1).toInteger())
                                            .put("validTransaction", unitsAvailable >= unitsAssigned);

                                    validTransaction.complete(jsonObject);
                                } else {
                                    validTransaction.fail(event.cause());
                                }
                            });

            validTransaction.future()
                    .onComplete(rs -> {
                        if (rs.succeeded() && rs.result().getBoolean("validTransaction")) {
                            final Integer nextBondNo = rs.result().getInteger("numberOfBonds") + 1;
                            final String bondId = String.format("%s-%d", bondName, nextBondNo);
                            bondToUserAssignmentKey = String.format(RedisKeys.BOND_TO_USER_ASSIGNMENT_KEY.getPattern(), bondId);
                            bondsDetailsKey = String.format(RedisKeys.BOND_DETAILS_KEY.getPattern(), bondId);
                            conn.send(Request.cmd(Command.MULTI),
                                            event -> {
                                                if (event.succeeded() && event.result().toString().equals("OK")) {
                                                    log.info("Issuing multiple commands in a transaction...");
                                                }
                                            })
                                    .send(Request.cmd(Command.HINCRBY, bondTermKey, "unitsAvailable", -unitsAssigned),
                                            event -> {
                                                if (event.succeeded()) {
                                                    log.info("CMD:[HINCRBY {} unitsAvailable -{}] > {}",
                                                            bondTermKey,
                                                            unitsAssigned,
                                                            event.result().toString());
                                                }
                                            })
                                    .send(Request.cmd(Command.HINCRBY, bondTermKey, "numberOfBonds", 1),
                                            event -> {
                                                if (event.succeeded()) {
                                                    log.info("CMD:[HINCRBY {} numberOfBonds 1] > {}",
                                                            bondTermKey,
                                                            event.result().toString());
                                                }
                                            })
                                    .send(Request.cmd(Command.SADD, bondToUserAssignmentKey, userId), event -> {
                                        if (event.succeeded()) {
                                            log.info("CMD:[SADD {} {}] > {}",
                                                    bondToUserAssignmentKey,
                                                    userId,
                                                    event.result().toString());
                                        }
                                    })
                                    .send(Request.cmd(Command.SADD, userToBondAssignmentKey, bondId), event -> {
                                        if (event.succeeded()) {
                                            log.info("CMD:[SADD {} {}] > {}",
                                                    userToBondAssignmentKey,
                                                    bondId,
                                                    event.result().toString());
                                        }
                                    })
                                    .send(Request.cmd(Command.HSET, bondsDetailsKey,
                                                    "unitsAssigned", unitsAssigned,
                                                    "nextCouponDate", String.valueOf(nextCouponDate.toEpochDay()),
                                                    "maturityDate", String.valueOf(LocalDate.parse(maturityDate, bondDateFormatter).toEpochDay()),
                                                    "userId",userId),
                                            event -> {
                                                log.info("CMD:[HSET {} {} {} {} {} {} {} {} {} ] > {}",
                                                        bondsDetailsKey,
                                                        "unitsAssigned", unitsAssigned,
                                                        "nextCouponDate", nextCouponDate.toEpochDay(),
                                                        "maturityDate", LocalDate.parse(maturityDate, bondDateFormatter).toEpochDay(),
                                                        "userId",userId,
                                                        event.result().toString());
                                            })
                                    .send(Request.cmd(Command.ZADD, LIST_BOND_KEYS.getPattern(), nextBondNo, bondId),
                                            event -> {
                                                log.info("CMD:[ZADD {} {} {} ] > {}",
                                                        LIST_BOND_KEYS.getPattern(),
                                                        nextBondNo,
                                                        bondId,
                                                        event.result().toString());
                                            })
                                    .send(Request.cmd(Command.EXEC), event -> {
                                        if (event.succeeded()) {

                                            for (int i = 0; i < event.result().size(); i++) {
                                                log.info("Tx:{} result {}", i, event.result().get(i));
                                            }
                                            promise.complete(true);

                                            log.info("Completed Transaction...");
                                        } else {
                                            log.error("Transaction failed!!", event.cause());
                                        }
                                    });

                        } else {
                            log.warn("Not a valid transaction...");
                            promise.complete(false);
                        }
                    });
            return promise.future();
        }

    }
}
