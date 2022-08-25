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

import java.util.Objects;
import java.util.Set;

public class BondTerm {

    private static final Logger log = LoggerFactory.getLogger(BondTerm.class);
    private final String bondName;
    private final Double interestRate;
    private final Integer parValue;
    private final Integer unitsAvailable;
    private final String maturityDate;
    private final String bondType;
    private final String currency;
    private final String creditRating;
    private final Integer paymentFrequencyInMonths;
    @JsonIgnore
    private final Cache cache;

    public BondTerm(JsonObject term) {
        this.bondName = term.getString("bondName");
        this.interestRate = term.getDouble("interestRate");
        this.parValue = term.getInteger("parValue");
        this.unitsAvailable = term.getInteger("unitsAvailable");
        this.maturityDate =  term.getString("maturityDate");
        this.bondType = term.getString("bondType");
        this.currency = term.getString("currency");
        this.creditRating = term.getString("creditRating");
        this.paymentFrequencyInMonths = term.getInteger("paymentFrequencyInMonths");
        cache = new Cache();
    }

    public static String getRedisKey(String bondName){
        return String.format("corda:bt#%s", bondName);
    }

    @JsonIgnore
    public String getRedisKey() {
        return getRedisKey(bondName);
    }

    // UniqueId
    public String getBondName() {
        return bondName;
    }

    public Double getInterestRate() {
        return interestRate;
    }

    public Integer getParValue() {
        return parValue;
    }

    public Integer getUnitsAvailable() {
        return unitsAvailable;
    }

    public String getMaturityDate() {
        return maturityDate;
    }

    public String getBondType() {
        return bondType;
    }

    public String getCurrency() {
        return currency;
    }

    public String getCreditRating() {
        return creditRating;
    }

    public Integer getPaymentFrequencyInMonths() {
        return paymentFrequencyInMonths;
    }

    public Cache getCache() {
        return cache;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BondTerm bondTerm = (BondTerm) o;
        return bondName.equals(bondTerm.bondName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bondName);
    }

    public class Cache {

        public static Future<BondTerm> getBondTerm(String bondName){
            Promise<BondTerm> bondTermPromise = Promise.promise();
            String bondKey = BondTerm.getRedisKey(bondName);
            log.info("Fetching bondTerm: {} from cache", bondKey);
            try{
                VRedisClient.getClient()
                        .send(Request.cmd(Command.HGETALL, bondKey), event -> {
                           if(event.succeeded() && event.result().size() > 0){
                               log.info("Found bond term:{} in cache", bondKey);
                               Response result = event.result();
                               Set<String> keys = result.getKeys();
                               JsonObject bondTermJson = new JsonObject();
                               for (String key : keys) {
                                   if(Set.of("interestRate").contains(key)){
                                       bondTermJson.put(key, Double.valueOf(result.get(key).toString()));
                                   }else if(Set.of("parValue","unitsAvailable","paymentFrequencyInMonths").contains(key)){
                                       bondTermJson.put(key, Integer.valueOf(result.get(key).toString()));
                                   }else {
                                       bondTermJson.put(key, result.get(key).toString());
                                   }
                               }
                               bondTermJson.put("bondName", bondName);
                               BondTerm bt = new BondTerm(bondTermJson);
                               bondTermPromise.complete(bt);
                           }else {
                               log.error("Unexpected Exception", event.cause());
                               bondTermPromise.fail(event.cause());
                           }
                        });
            }catch (Exception e){
                log.error("Unable to fetch bondTerms", e);
                bondTermPromise.fail(e);
            }
            return bondTermPromise.future();

        }
        public Future<BondTerm> createTerm(){
            Promise<BondTerm> bondTermPromise = Promise.promise();
            String bondTermKey = BondTerm.getRedisKey(bondName);
            log.info("Caching BondTerm {} ", bondTermKey);
            try{
                VRedisClient.getClient()
                        .send(Request.cmd(
                                Command.HSET,
                                bondTermKey,
                                "interestRate", interestRate.toString(),
                                "parValue", parValue.toString(),
                                "unitsAvailable",unitsAvailable.toString(),
                                "maturityDate", maturityDate,
                                "bondType",bondType,
                                "currency", currency,
                                "creditRating",creditRating,
                                "paymentFrequencyInMonths",paymentFrequencyInMonths.toString()), event -> {
                            if(event.succeeded()){
                                bondTermPromise.complete(BondTerm.this);
                            }else {
                                bondTermPromise.fail(event.cause());
                            }
                        });
            }catch (Exception e){
                log.error("Unable to cache bondTerms", e);
                bondTermPromise.fail(e);
            }
            return bondTermPromise.future();
        }
    }
}
