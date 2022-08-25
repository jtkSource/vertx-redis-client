package com.jtk.redisapp.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.jtk.redisapp.client.VRedisClient;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public class BondDetails {

    private static final DateTimeFormatter bondDateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final String bondId;
    private final int unitsAssigned;
    private final String nextCouponDate;
    private final String maturityDate;

    @JsonIgnore
    public final Cache cache;

    @JsonIgnore
    private String bondsDetailsKey; //"corda:bt:bond:details#%s"

    public BondDetails(String bondId, int unitsAssigned, String nextCouponDate, String maturityDate) {
        this.unitsAssigned = unitsAssigned;
        this.nextCouponDate = nextCouponDate;
        this.maturityDate = maturityDate;
        this.bondId = bondId;
        this.bondsDetailsKey = getRedisKey(bondId);
        this.cache = new Cache();
    }

    public static String getRedisKey(String bondId) {
        return String.format(RedisKeys.BOND_DETAILS_KEY.getPattern(), bondId);
    }

    @JsonIgnore
    public String getRedisKey() {
        return getRedisKey(bondId);
    }

    public String getBondId() {
        return bondId;
    }

    public int getUnitsAssigned() {
        return unitsAssigned;
    }

    public String getNextCouponDate() {
        return nextCouponDate;
    }

    public String getMaturityDate() {
        return maturityDate;
    }



    public static class Cache {
        public static Future<BondDetails> getBondsDetails(String bondId) {
            Promise<BondDetails> promise = Promise.promise();
            VRedisClient.getClient()
                    .send(Request.cmd(Command.HGETALL, getRedisKey(bondId)), event -> {
                        if (event.succeeded() && event.result().size() > 0) {
                            int unitsAssigned = event.result().get("unitsAssigned").toInteger();
                            String nextCouponDate = LocalDate.ofEpochDay(event.result().get("nextCouponDate").toLong())
                                    .format(bondDateFormatter);
                            String maturityDate = LocalDate.ofEpochDay(event.result().get("maturityDate").toLong())
                                    .format(bondDateFormatter);
                            promise.complete(new BondDetails(bondId, unitsAssigned, nextCouponDate, maturityDate));
                        }else {
                            promise.fail(event.cause());
                        }
                    });
            return promise.future();
        }
    }
}
