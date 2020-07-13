package org.sourcelab.storm.spout.redis.client;

import io.lettuce.core.Consumer;
import io.lettuce.core.RedisBusyException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sourcelab.storm.spout.redis.RedisStreamSpoutConfig;
import org.sourcelab.storm.spout.redis.Message;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Redis Stream Consumer using the Lettuce RedisLabs java library.
 */
public class LettuceClient implements Client {
    private static final Logger logger = LoggerFactory.getLogger(LettuceClient.class);

    /**
     * Configuration properties for the client.
     */
    private final RedisStreamSpoutConfig config;

    /**
     * Generated from config.getConsumerIdPrefix() along with the spout's instance
     * id to come to a unique consumerId to support parallelism.
     */
    private final String consumerId;

    /**
     * The underlying Redis Client.
     */
    private final RedisClient redisClient;

    /**
     * Underlying connection objects.
     */
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> syncCommands;

    /**
     * Re-usable instance to prevent unnecessary garbage creation.
     */
    private final XReadArgs xreadArgs;
    private final Consumer<String> consumerFrom;
    private final XReadArgs.StreamOffset<String> lastConsumed;

    /**
     * Constructor.
     * @param config Configuration.
     * @param instanceId Which instance number is this running under.
     */
    public LettuceClient(final RedisStreamSpoutConfig config, final int instanceId) {
        this(
            config,
            instanceId,
            RedisClient.create(config.getConnectString())
        );
    }

    /**
     * Protected constructor for injecting a RedisClient instance, typically for tests.
     * @param config Configuration.
     * @param instanceId Which instance number is this running under.
     * @param redisClient RedisClient instance.
     */
    LettuceClient(final RedisStreamSpoutConfig config, final int instanceId, final RedisClient redisClient) {
        this.config = Objects.requireNonNull(config);
        this.redisClient = Objects.requireNonNull(redisClient);

        // Calculate consumerId
        this.consumerId = config.getConsumerIdPrefix() + instanceId;

        // Create re-usable xReadArgs object.
        xreadArgs = XReadArgs.Builder.noack()
            // Define limit on number of messages to read per request
            .count(config.getMaxConsumePerRead())
            // Require Acks
            .noack(false);

        // Create re-usable ConsumerFrom instance.
        consumerFrom = Consumer.from(config.getGroupName(), consumerId);

        // Create re-usable lastConsumed instance.
        lastConsumed = XReadArgs.StreamOffset.lastConsumed(config.getStreamKey());
    }

    @Override
    public void connect() {
        if (connection != null) {
            throw new IllegalStateException("Cannot call connect more than once!");
        }

        // Connect
        connection = redisClient.connect();
        syncCommands = connection.sync();

        try {
            // Attempt to create consumer group
            syncCommands.xgroupCreate(
                // Start the group at first offset for our key.
                XReadArgs.StreamOffset.from(config.getStreamKey(), "0-0"),
                // Define the group name
                config.getGroupName(),
                // Create the stream if it doesn't already exist.
                XGroupCreateArgs.Builder
                    .mkstream(true)
            );
        }
        catch (final RedisBusyException redisBusyException) {
            // Consumer group already exists, that's ok. Just swallow this.
            logger.debug("Group {} for key {} already exists.", config.getGroupName(), config.getStreamKey());
        }
        catch (final RedisCommandExecutionException exception) {
            logger.error(
                "Key {} does not exist or is invalid! {}",
                config.getStreamKey(), exception.getMessage(), exception
            );

            // Re-throw exception
            throw exception;
        }
    }

    @Override
    public List<Message> nextMessages() {
        // Get next batch of messages.
        final List<StreamMessage<String, String>> messages = syncCommands.xreadgroup(
            consumerFrom,
            xreadArgs,
            lastConsumed
        );

        // Loop over each message
        return messages.stream()
            // Map into Message Object
            .map((streamMsg) -> new Message(streamMsg.getId(), streamMsg.getBody()))
            .collect(Collectors.toList());
    }

    @Override
    public void commitMessage(final String msgId) {
        // Confirm that the message has been processed using XACK
        syncCommands.xack(
            config.getStreamKey(),
            config.getGroupName(),
            msgId
        );
    }

    @Override
    public void disconnect() {
        // Close our connection and shutdown.
        if (connection != null) {
            connection.close();
            connection = null;
        }
        redisClient.shutdown();
    }
}
