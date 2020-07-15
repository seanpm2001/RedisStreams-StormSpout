package org.sourcelab.storm.spout.redis.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.sourcelab.storm.spout.redis.Message;
import org.sourcelab.storm.spout.redis.RedisStreamSpoutConfig;
import org.sourcelab.storm.spout.redis.example.TestTupleConverter;
import org.sourcelab.storm.spout.redis.util.test.RedisTestHelper;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.MinimumDurationRunningStartupCheckStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@Tag("Integration")
class LettuceClientIntegrationTest {

    /**
     * This test depends ont he following Redis Container.
     */
    @Container
//    public GenericContainer redis = new GenericContainer<>(RedisTestHelper.REDIS_DOCKER_CONTAINER_IMAGE)
//        .withExposedPorts(6379);

    //public GenericContainer redis = new GenericContainer<>("grokzen/redis-cluster:latest")
    public GenericContainer redis = new FixedHostPortGenericContainer("grokzen/redis-cluster:latest")
        //.withEnv("IP", DockerClientFactory.instance().dockerHostIpAddress())
        //.withEnv("IP", "0.0.0.0")
        .withFixedExposedPort(7000, 7000)
        .withFixedExposedPort(7001, 7001)
        .withFixedExposedPort(7002, 7002)
        .withFixedExposedPort(7003, 7003)
        .withFixedExposedPort(7004, 7004)
        .withFixedExposedPort(7005, 7005)
        .withEnv("IP", "127.0.0.1")
        .withStartupCheckStrategy(
            new MinimumDurationRunningStartupCheckStrategy(Duration.ofSeconds(10))
        );


    private static final String CONSUMER_ID_PREFIX = "ConsumerId";
    private static final int MAX_CONSUMED_PER_READ = 10;

    private RedisTestHelper redisTestHelper;

    private RedisStreamSpoutConfig config;
    private LettuceClient client;
    private String streamKey;

    @BeforeEach
    void setUp(){
        // Generate a random stream key
        streamKey = "MyStreamKey" + System.currentTimeMillis();

        // Create a config
        config = createConfiguration(CONSUMER_ID_PREFIX + "1");

        // Create client instance under test.
        client = new LettuceClient(config, 1);

        // Ensure that the key exists!
        redisTestHelper = RedisTestHelper.createClusterHelper(config.getConnectString());
    }

    @AfterEach
    void cleanUp() {
        redisTestHelper.close();

        // Always disconnect client
        client.disconnect();
    }

    /**
     * Simple connect and disconnect smoke test.
     */
    @Test
    void testConnectAndDisconnect_smokeTest() {
        client.connect();
        client.disconnect();
    }

    /**
     * Simple connect, consume, and disconnect smoke test for a single consumer.
     */
    @Test
    void testSimpleConsume() throws InterruptedException {
        // Connect
        client.connect();

        // Ask for messages.
        List<Message> messages = client.nextMessages();
        assertNotNull(messages, "Should be non-null");
        assertTrue(messages.isEmpty(), "Should be empty");

        // Ask for messages, should be empty
        messages = client.nextMessages();
        assertNotNull(messages, "Should be non-null");
        assertTrue(messages.isEmpty(), "Should be empty");
    }

    /**
     * Simple connect, consume, commit, and disconnect smoke test.
     */
    @Test
    void testSimpleConsumeMultipleMessages() {
        // Connect
        client.connect();

        // Ask for messages.
        List<Message> messages = client.nextMessages();
        assertNotNull(messages, "Should be non-null");
        assertTrue(messages.isEmpty(), "Should be empty");

        // Now Submit more messages to the stream
        final List<String> expectedMessageIds = redisTestHelper.produceMessages(streamKey, MAX_CONSUMED_PER_READ);

        // Ask for the next messages
        messages = client.nextMessages();

        // Validate
        verifyConsumedMessagesInOrder(expectedMessageIds, messages);

        // Commit each
        messages.stream()
            .map(Message::getId)
            .forEach((msgId) -> client.commitMessage(msgId));

        // Ask for messages, should be empty
        messages = client.nextMessages();
        assertNotNull(messages, "Should be non-null");
        assertEquals(0, messages.size(), "Should be empty");
    }

    /**
     * This sets up the client such that there are
     * multiple consumers on the same group.
     *
     * Each consumer should receive its own set of messages
     * without duplicates.
     */
    @Test
    void testConsumeMultipleConsumers() {
        // Define 2nd client, but don't connect yet
        final RedisStreamSpoutConfig config2 = createConfiguration(CONSUMER_ID_PREFIX + "2");
        final LettuceClient client2 = new LettuceClient(config2, 2);

        try {
            // Connect first client
            client.connect();

            // Ask for messages.
            List<Message> messagesClient1 = client.nextMessages();
            assertTrue(messagesClient1.isEmpty(), "Should be empty");

            // Now Connect 2nd client and ask for messages
            client2.connect();
            assertTrue(client2.nextMessages().isEmpty(), "Client2 should have no messages");

            // Now Submit more messages to the stream
            List<String> expectedMessageIdsClient2 = redisTestHelper.produceMessages(streamKey, MAX_CONSUMED_PER_READ);
            List<Message> messagesClient2 = client2.nextMessages();

            List<String> expectedMessageIdsClient1 = redisTestHelper.produceMessages(streamKey, MAX_CONSUMED_PER_READ);
            messagesClient1 = client.nextMessages();

            // Validate
            verifyConsumedMessagesInOrder(expectedMessageIdsClient1, messagesClient1);
            verifyConsumedMessagesInOrder(expectedMessageIdsClient2, messagesClient2);

            // Commit each
            messagesClient1.stream()
                .map(Message::getId)
                .forEach((client::commitMessage));

            messagesClient2.stream()
                .map(Message::getId)
                .forEach(client2::commitMessage);

            // Ask for messages, should be empty
            assertTrue(client.nextMessages().isEmpty(), "Should be empty");
            assertTrue(client2.nextMessages().isEmpty(), "Should be empty");
        } finally {
            // Make sure we always disconnect client 2
            client2.disconnect();
        }
    }

    /**
     * This sets up the client such that there are
     * multiple consumers on the same group.
     *
     * Each consumer should receive its own set of messages
     * without duplicates.
     */
    @Test
    void testConsumeMultipleConsumers_scenario2() {
        // Define 2nd client, but don't connect yet
        final RedisStreamSpoutConfig config2 = createConfiguration(CONSUMER_ID_PREFIX + "2");
        final LettuceClient client2 = new LettuceClient(config2, 2);

        try {
            // Connect first client
            client.connect();

            // Ask for messages.
            List<Message> messagesClient1 = client.nextMessages();
            assertNotNull(messagesClient1, "Should be non-null");
            assertTrue(messagesClient1.isEmpty(), "Client1 Should have no messages");

            // Now Connect 2nd client and ask for messages
            client2.connect();
            assertTrue(client2.nextMessages().isEmpty(), "Client2 should have no messages");

            // Now lots of messages to the stream.
            final int totalMessages = MAX_CONSUMED_PER_READ * 4;
            List<String> expectedMessageIds = redisTestHelper.produceMessages(streamKey, totalMessages);

            // Ask for messages for each client.
            messagesClient1 = client.nextMessages();
            List<Message> messagesClient2 = client2.nextMessages();

            // Do this 1 more times for each client
            messagesClient1.addAll(client.nextMessages());
            messagesClient2.addAll(client2.nextMessages());

            // We should have no more messages
            assertTrue(client.nextMessages().isEmpty(), "Should have no more messages");
            assertTrue(client2.nextMessages().isEmpty(), "Should have no more messages");

            // Validate
            // Total number of messages found should be equal to totalMessages
            assertEquals(totalMessages, messagesClient1.size() + messagesClient2.size(), "Mismatch on messages consumes");

            // Verify each client got half
            assertEquals(totalMessages / 2, messagesClient1.size(), "Correct number of messages for client1");
            assertEquals(totalMessages / 2, messagesClient2.size(), "Correct number of messages for client2");

            // Validate we found all the correct messages
            verifyConsumedMessagesExistWithNoDuplicates(expectedMessageIds, messagesClient1, messagesClient2);

            // Commit each
            messagesClient1.stream()
                .map(Message::getId)
                .forEach((client::commitMessage));

            messagesClient2.stream()
                .map(Message::getId)
                .forEach(client2::commitMessage);

            // Ask for messages, should be empty
            assertTrue(client.nextMessages().isEmpty(), "Should be empty");
            assertTrue(client2.nextMessages().isEmpty(), "Should be empty");
        } finally {
            // Make sure we always disconnect client 2
            client2.disconnect();
        }
    }

    /**
     * Simple connect, consume, commit, and disconnect smoke test.
     */
    @Test
    void testSimpleConsumeMultipleMessages_withReconnect() {
        // Connect
        client.connect();

        // Ask for messages.
        List<Message> messages = client.nextMessages();
        assertNotNull(messages, "Should be non-null");
        assertTrue(messages.isEmpty(), "Should be empty");

        // Now Submit more messages to the stream
        List<String> expectedMessageIds = redisTestHelper.produceMessages(streamKey, MAX_CONSUMED_PER_READ);

        // Ask for the next messages
        messages = client.nextMessages();

        // Validate
        verifyConsumedMessagesInOrder(expectedMessageIds, messages);

        // Commit each
        messages.stream()
            .map(Message::getId)
            .forEach((msgId) -> client.commitMessage(msgId));

        // Ask for messages, should be empty
        messages = client.nextMessages();
        assertNotNull(messages, "Should be non-null");
        assertEquals(0, messages.size(), "Should be empty");

        // Disconnect client.
        client.disconnect();

        // Write more messages to stream while client is disconnected.
        expectedMessageIds = redisTestHelper.produceMessages(streamKey, MAX_CONSUMED_PER_READ);

        // Create new client using the same client
        final LettuceClient client2 = new LettuceClient(config, 1);
        client2.connect();

        // Consume messages, should be the messages we got.
        messages = client2.nextMessages();
        verifyConsumedMessagesInOrder(expectedMessageIds, messages);

        client2.disconnect();
    }

    private void verifyConsumedMessagesInOrder(final List<String> expectedMessageIds, final List<Message> foundMessages) {
        // Validate
        assertNotNull(foundMessages, "Should never be null");
        assertEquals(expectedMessageIds.size(), foundMessages.size(), "Wrong number of messages found");

        // Verify each found in expected order
        for (int index = 0; index < expectedMessageIds.size(); index++) {
            final String expectedId = expectedMessageIds.get(index);
            final String foundId = foundMessages.get(index).getId();

            assertEquals(expectedId, foundId, "Mismatch on ids!");
        }

        final List<String> missingMessageIds = foundMessages.stream()
            .map(Message::getId)
            .filter((msgId) -> !expectedMessageIds.contains(msgId))
            .collect(Collectors.toList());
        assertTrue(missingMessageIds.isEmpty(), "We shouldn't be missing any msgIds!");
    }

    private void verifyConsumedMessagesExistWithNoDuplicates(
        final List<String> expectedMessageIds, final List<Message>...foundMessageLists
    ) {
        // Validate
        assertNotEquals(0, foundMessageLists.length, "Should never be empty");

        // Collect all the foundMessages into a master list
        final List<Message> masterList = new ArrayList<>();
        for (final List<Message> foundMessagesList : foundMessageLists) {
            masterList.addAll(foundMessagesList);
        }

        // Verify totals match
        assertEquals(expectedMessageIds.size(), masterList.size(), "Found different number of messages!");

        // Make sure we found every message.
        final List<String> missingMessageIds = masterList.stream()
            .map(Message::getId)
            .filter((msgId) -> !expectedMessageIds.contains(msgId))
            .collect(Collectors.toList());
        assertTrue(missingMessageIds.isEmpty(), "We shouldn't be missing any msgIds!");

        // Make sure all unique values
        final Set<String> uniqueMessageIds = masterList.stream()
            .map(Message::getId)
            .collect(Collectors.toSet());

        assertEquals(expectedMessageIds.size(), uniqueMessageIds.size(), "Duplicate ids found!");
        assertEquals(masterList.size(), uniqueMessageIds.size(), "Duplicate ids found!");
    }

    private RedisStreamSpoutConfig createConfiguration(final String consumerId) {
        return RedisStreamSpoutConfig.newBuilder()
//            .withHost(redis.getHost())
//            .withPort(redis.getFirstMappedPort())
            .withHost(redis.getHost())
            .withPort(7000)
            .withGroupName("DefaultGroupName")
            .withStreamKey(streamKey)
            .withConsumerIdPrefix(consumerId)
            .withMaxConsumePerRead(MAX_CONSUMED_PER_READ)
            .withNoRetryFailureHandler()
            .withTupleConverter(new TestTupleConverter())
            .build();
    }
}