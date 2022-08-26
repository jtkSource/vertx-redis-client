package com.jtk.redisapp.client;

import io.vertx.core.*;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetClientOptions;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisClientType;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.RedisOptions;
import io.vertx.redis.client.RedisReplicas;
import io.vertx.redis.client.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Notes:
 * Vert.x redis client was implemented from scratch to work in pipeline since that is the way redis server works.
 * Other drivers did not implement this from start (like jedis) so they created this "pipeline" mode later on,
 */
public class VRedisClient {
    private static final Logger log = LoggerFactory.getLogger(VRedisClient.class);

    private static RedisConnection client;

    private static final int MAX_RECONNECT_RETRIES = 16;
    private static RedisOptions redisOptions;
    private static final AtomicBoolean CONNECTING = new AtomicBoolean();
    private static Vertx vertx;

    public static Future<RedisConnection> init(Vertx vertx, JsonObject config){
        VRedisClient.vertx = vertx;
        String host = config.getString("redis.host");
        String masterName = config.getString("redis.master.name");
        String redisReplica = config.getString("redis.replicas");
        String redisClientType = config.getString("redis.client.type");
        Integer port = config.getInteger("redis.port");
        Integer maxPool = config.getInteger("redis.maxpool");
        Integer maxPoolWaiting = config.getInteger("redis.maxpool.waiting");
        String redisUrl = String.format("redis://%s:%d",host,port);
        redisOptions = new RedisOptions()
                .setType(RedisClientType.valueOf(redisClientType))
                .setNetClientOptions(new NetClientOptions()
                        .setConnectTimeout(2000)
                        .setTcpKeepAlive(true)
                        .setTcpNoDelay(true))
                .addConnectionString(redisUrl)
                .setMasterName(masterName)
                .setUseReplicas(RedisReplicas.valueOf(redisReplica))
                //when the configuration states that the
                // use of replica nodes is ALWAYS then any read operation will be performed on a replica node
                .setMaxPoolSize(maxPool)
                .setMaxPoolWaiting(maxPoolWaiting);
        log.info("Redis Options {}", redisOptions.toJson());

        return createRedisClient()
                .onComplete(new RedisClientHandler(1));
    }

    private static void attemptReconnect(int retry) {
        if (retry > MAX_RECONNECT_RETRIES) {
            log.error("Max retries {} already, cannot connect to redis...", retry);
            // we should stop now, as there's nothing we can do.
            CONNECTING.set(false);
        } else {
            log.info("Trying to reconnect ({})...",retry);
            // retry with backoff up to 10240 ms
            long backoff = (long) (Math.pow(2, Math.min(retry, 10)) * 10);
            vertx.setTimer(backoff, timer ->
                    createRedisClient()
                            .onComplete(new RedisClientHandler(retry + 1)));
        }
    }

    private static Future<RedisConnection> createRedisClient() {
        Promise<RedisConnection> promise = Promise.promise();

        if (CONNECTING.compareAndSet(false, true)) {
            Redis.createClient(vertx, redisOptions)
                    .connect()
                    .onSuccess(conn -> {
                        // make sure to invalidate old connection if present
                        if (client != null) {
                            client.close();
                        }
                        // make sure the client is reconnected on error
                        conn.exceptionHandler(e -> {
                            log.error("Unexpected connection with Redis...", e);
                            // attempt to reconnect,
                            // if there is an unrecoverable error
                            attemptReconnect(0);
                        });
                        promise.complete(conn);
                        CONNECTING.set(false);
                        client =  conn;

                    }).onFailure(t -> {
                        promise.fail(t);
                        CONNECTING.set(false);
                    });
        } else {
            promise.complete();
        }
        return promise.future();
    }

    public static RedisConnection getClient() {
        try {
            client.send(Request.cmd(Command.PING));
        }catch (Exception e){
            createRedisClient()
                    .onComplete(new RedisClientHandler(1));
        }
        return client;
    }

    static class RedisClientHandler implements Handler<AsyncResult<RedisConnection>> {
        private int retry;

        public RedisClientHandler(int retry) {
            this.retry = retry;
        }

        @Override
        public void handle(AsyncResult<RedisConnection> event) {
            if(event.failed()){
                attemptReconnect(retry);
            }else {
               log.info("Redis Connection Successful...");
            }
        }
    }

}
