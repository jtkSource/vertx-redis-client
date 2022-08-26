package com.jtk.redisapp.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.jtk.redisapp.client.VRedisClient;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class BondDetails {

    private static final Logger log = LoggerFactory.getLogger(BondDetails.class);
    private static final DateTimeFormatter bondDateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final String bondId;
    private final String userId;
    private final int unitsAssigned;
    private final String nextCouponDate;
    private final String maturityDate;

    @JsonIgnore
    public final Cache cache;

    @JsonIgnore
    private String bondsDetailsKey; //"corda:bt:bond:details#%s"

    public BondDetails(String bondId, String userId, int unitsAssigned, String nextCouponDate, String maturityDate) {
        this.userId = userId;
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

    public String getUserId() {
        return userId;
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

        public static Future<List<BondDetails>> getAllBondDetails(){
            Promise<List<BondDetails>> promise = Promise.promise();
            VRedisClient.getClient()
                    .send(Request.cmd(Command.SORT, RedisKeys.LIST_BOND_KEYS.getPattern() ,
                                    "BY", "corda:bt:bond:details#*->maturityDate", "DESC",
                                    "GET", "#",
                                    "GET", "corda:bt:bond:details#*->userId",
                                    "GET", "corda:bt:bond:details#*->unitsAssigned",
                                    "GET", "corda:bt:bond:details#*->nextCouponDate",
                                    "GET", "corda:bt:bond:details#*->maturityDate"
                                   ),
                            event -> {

                        if(event.succeeded()){
                            List<BondDetails> bondDetails = new ArrayList<>();
                            String bondId = "";
                            String userId = "";
                            int unitsAssigned = 0;
                            String nextCouponDate = "";
                            String maturityDate = "";

                            for (int index = 0; index < event.result().size(); index++) {
                                int mod = index % 5;
                                switch (mod){
                                    case 0 -> bondId = event.result().get(index).toString();
                                    case 1 -> userId = event.result().get(index).toString();
                                    case 2 -> unitsAssigned = event.result().get(index).toInteger();
                                    case 3 -> nextCouponDate = getFormattedDate(event.result().get(index).toLong());
                                    case 4 -> maturityDate = getFormattedDate(event.result().get(index).toLong());
                                    default -> log.error("Unsupported field#:"+mod+" skipping...");
                                }
                                if(index!=0 && mod == 4){
                                    bondDetails.add(new BondDetails(bondId, userId, unitsAssigned, nextCouponDate, maturityDate));
                                }
                            }
                            promise.complete(bondDetails);
                        }else {
                            log.error("Unexpected Exception",event.cause());
                            promise.fail(event.cause());
                        }
                    });
            return promise.future();
        }
        public static Future<BondDetails> getBondsDetails(String bondId) {
            Promise<BondDetails> promise = Promise.promise();
            VRedisClient.getClient()
                    .send(Request.cmd(Command.HGETALL, getRedisKey(bondId)), event -> {
                        if (event.succeeded() && event.result().size() > 0) {
                            String  userId = event.result().get("userId").toString();
                            int unitsAssigned = event.result().get("unitsAssigned").toInteger();
                            String nextCouponDate = getFormattedDate(event.result().get("nextCouponDate").toLong());
                            String maturityDate = getFormattedDate(event.result().get("maturityDate").toLong());
                            promise.complete(new BondDetails(bondId, userId, unitsAssigned, nextCouponDate, maturityDate));
                        }else {
                            promise.fail(event.cause());
                        }
                    });
            return promise.future();
        }
    }

    private static String getFormattedDate(long epochDate) {
        return LocalDate.ofEpochDay(epochDate).format(bondDateFormatter);
    }
}
