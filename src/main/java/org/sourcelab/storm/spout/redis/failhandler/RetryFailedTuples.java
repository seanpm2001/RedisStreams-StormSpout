package org.sourcelab.storm.spout.redis.failhandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sourcelab.storm.spout.redis.FailureHandler;
import org.sourcelab.storm.spout.redis.funnel.Message;
import org.sourcelab.storm.spout.redis.util.StormToClientConfigurationUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Handler which will replay failed tuples a maximum number of times.
 *
 * It uses two configuration properties:
 *
 * @see StormToClientConfigurationUtil.CONSUMER_MAX_TUPLE_QUEUE_SIZE to set the max capacity of the internal queue.
 * @see StormToClientConfigurationUtil.FAILURE_HANDLER_MAX_RETRIES to set max number of times a message will be retried.
 */
public class RetryFailedTuples implements FailureHandler {
    private static final Logger logger = LoggerFactory.getLogger(RetryFailedTuples.class);

    /**
     * This tracks how many times a specific msgId has been replayed.
     * MsgId => Number of times we've replayed it.
     */
    private final Map<String, Long> messageCounter = new HashMap<>();

    /**
     * Contains a FIFO queue for failed messages.
     */
    private LinkedBlockingQueue<Message> messageQueue;

    /**
     * How many times a failed message should be replayed.
     * A value of 0 means never give up on a message and always replay it.
     */
    private int maxRetries = 10;

    @Override
    public void open(final Map<String, Object> stormConfig) {
        maxRetries = 10;
        if (stormConfig.containsKey(StormToClientConfigurationUtil.FAILURE_HANDLER_MAX_RETRIES)) {
            maxRetries = ((Number) stormConfig.get(StormToClientConfigurationUtil.FAILURE_HANDLER_MAX_RETRIES)).intValue();
        }

        int maxCapacity = 1024;
        if (stormConfig.containsKey(StormToClientConfigurationUtil.CONSUMER_MAX_TUPLE_QUEUE_SIZE)) {
            maxCapacity = ((Number) stormConfig.get(StormToClientConfigurationUtil.CONSUMER_MAX_TUPLE_QUEUE_SIZE)).intValue();
        }
        messageQueue = new LinkedBlockingQueue<>(maxCapacity);
    }

    @Override
    public boolean fail(final Message message) {
        if (message == null) {
            return false;
        }

        // Get it's Id.
        final String msgId = message.getId();

        // Determine if we should replay it.
        if (!shouldReplay(msgId)) {
            // return false
            return false;
        }

        // We do want to replay it, so drop into our counter and queue.
        try {
            // Only track if we have a limited number.
            if (maxRetries > 0) {
                messageCounter.compute(msgId, (key, value) -> (value == null) ? 1L : value + 1L);
            }
            messageQueue.put(message);
        } catch (final InterruptedException exception) {
            logger.error("Interrupted while attempting to add to Failure Queue: {}", exception.getMessage(), exception);
            messageCounter.remove(msgId);
            return false;
        }

        // return true.
        return true;
    }

    private boolean shouldReplay(final String msgId) {
        if (messageCounter.getOrDefault(msgId, 0L) > maxRetries) {
            return false;
        }
        return true;
    }

    @Override
    public void ack(final String msgId) {
        // Remove from our tracking map
        messageCounter.remove(msgId);
    }

    @Override
    public Message getMessage() {
        return messageQueue.poll();
    }
}
