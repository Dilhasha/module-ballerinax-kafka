/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.ballerinalang.messaging.kafka.nativeimpl.consumer;

import io.ballerina.runtime.api.Environment;
import io.ballerina.runtime.api.Future;
import io.ballerina.runtime.api.ValueCreator;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.scheduling.Scheduler;
import io.ballerina.runtime.scheduling.Strand;
import io.ballerina.runtime.types.BArrayType;
import io.ballerina.runtime.values.FPValue;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.ballerinalang.messaging.kafka.observability.KafkaMetricsUtil;
import org.ballerinalang.messaging.kafka.observability.KafkaObservabilityConstants;
import org.ballerinalang.messaging.kafka.observability.KafkaTracingUtil;
import org.ballerinalang.messaging.kafka.utils.KafkaConstants;

import java.io.PrintStream;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.ballerinalang.messaging.kafka.utils.KafkaConstants.CONSUMER_ERROR;
import static org.ballerinalang.messaging.kafka.utils.KafkaConstants.NATIVE_CONSUMER;
import static org.ballerinalang.messaging.kafka.utils.KafkaConstants.ON_PARTITION_ASSIGNED_METADATA;
import static org.ballerinalang.messaging.kafka.utils.KafkaConstants.ON_PARTITION_REVOKED_METADATA;
import static org.ballerinalang.messaging.kafka.utils.KafkaUtils.createKafkaError;
import static org.ballerinalang.messaging.kafka.utils.KafkaUtils.getStringListFromStringBArray;
import static org.ballerinalang.messaging.kafka.utils.KafkaUtils.getTopicNamesString;
import static org.ballerinalang.messaging.kafka.utils.KafkaUtils.getTopicPartitionRecord;
import static org.ballerinalang.messaging.kafka.utils.KafkaUtils.populateTopicPartitionRecord;

/**
 * Native methods to handle subscription of the ballerina kafka consumer.
 */
public class SubscriptionHandler {
    private static final PrintStream console = System.out;

    /**
     * Subscribe the ballerina kafka consumer to the given array of topics.
     *
     * @param consumerObject Kafka consumer object from ballerina.
     * @param topics         Ballerina {@code string[]} of topics.
     * @return {@code BError}, if there's any error, null otherwise.
     */
    public static Object subscribe(BObject consumerObject, BArray topics) {
        KafkaTracingUtil.traceResourceInvocation(Scheduler.getStrand(), consumerObject);
        KafkaConsumer kafkaConsumer = (KafkaConsumer) consumerObject.getNativeData(NATIVE_CONSUMER);
        List<String> topicsList = getStringListFromStringBArray(topics);
        try {
            kafkaConsumer.subscribe(topicsList);
            Set<String> subscribedTopics = kafkaConsumer.subscription();
            KafkaMetricsUtil.reportBulkSubscription(consumerObject, subscribedTopics);
        } catch (IllegalArgumentException | IllegalStateException | KafkaException e) {
            KafkaMetricsUtil.reportConsumerError(consumerObject, KafkaObservabilityConstants.ERROR_TYPE_SUBSCRIBE);
            return createKafkaError("Failed to subscribe to the provided topics: " + e.getMessage(), CONSUMER_ERROR);
        }
        console.println(KafkaConstants.SUBSCRIBED_TOPICS + getTopicNamesString(topicsList));
        return null;
    }

    /**
     * Subscribes the ballerina kafka consumer to the topics matching the given regex.
     *
     * @param consumerObject Kafka consumer object from ballerina.
     * @param topicRegex     Regex to match topics to subscribe.
     * @return {@code BError}, if there's any error, null otherwise.
     */
    public static Object subscribeToPattern(BObject consumerObject, BString topicRegex) {
        KafkaTracingUtil.traceResourceInvocation(Scheduler.getStrand(), consumerObject);
        KafkaConsumer kafkaConsumer = (KafkaConsumer) consumerObject.getNativeData(NATIVE_CONSUMER);
        try {
            kafkaConsumer.subscribe(Pattern.compile(topicRegex.getValue()));
            // TODO: This sometimes not updating since Kafka not updates the subscription tight away
            Set<String> topicsList = kafkaConsumer.subscription();
            KafkaMetricsUtil.reportBulkSubscription(consumerObject, topicsList);
        } catch (IllegalArgumentException | IllegalStateException | KafkaException e) {
            KafkaMetricsUtil.reportConsumerError(consumerObject,
                                                 KafkaObservabilityConstants.ERROR_TYPE_SUBSCRIBE_PATTERN);
            return createKafkaError("Failed to subscribe to the topics: " + e.getMessage(), CONSUMER_ERROR);
        }
        return null;
    }

    /**
     * Subscribes the ballerina kafka consumer and re-balances the assignments.
     *
     * @param consumerObject       Kafka consumer object from ballerina.
     * @param topics               Ballerina {@code string[]} of topics.
     * @param onPartitionsRevoked  Function pointer to invoke if partitions are revoked.
     * @param onPartitionsAssigned Function pointer to invoke if partitions are assigned.
     * @return {@code BError}, if there's any error, null otherwise.
     */
    public static Object subscribeWithPartitionRebalance(Environment env, BObject consumerObject, BArray topics,
                                                         FPValue onPartitionsRevoked, FPValue onPartitionsAssigned) {
        Strand strand = Scheduler.getStrand();
        KafkaTracingUtil.traceResourceInvocation(strand, consumerObject);
        Future balFuture = env.markAsync();
        KafkaConsumer kafkaConsumer = (KafkaConsumer) consumerObject.getNativeData(NATIVE_CONSUMER);
        List<String> topicsList = getStringListFromStringBArray(topics);
        ConsumerRebalanceListener consumer = new SubscriptionHandler.KafkaRebalanceListener(strand, strand.scheduler,
                                                                                            onPartitionsRevoked,
                                                                                            onPartitionsAssigned,
                                                                                            consumerObject);
        try {
            kafkaConsumer.subscribe(topicsList, consumer);
            Set<String> subscribedTopics = kafkaConsumer.subscription();
            KafkaMetricsUtil.reportBulkSubscription(consumerObject, subscribedTopics);
            balFuture.complete(null);
        } catch (IllegalArgumentException | IllegalStateException | KafkaException e) {
            KafkaMetricsUtil.reportConsumerError(consumerObject,
                                                 KafkaObservabilityConstants.ERROR_TYPE_SUBSCRIBE_PARTITION_REBALANCE);
            balFuture.complete(createKafkaError("Failed to subscribe the consumer: " + e.getMessage(),
                                                    CONSUMER_ERROR));
        }
        return null;
    }

    /**
     * Unsubscribe the ballerina kafka consumer from all the topics.
     *
     * @param consumerObject Kafka consumer object from ballerina.
     * @return {@code BError}, if there's any error, null otherwise.
     */
    public static Object unsubscribe(BObject consumerObject) {
        KafkaTracingUtil.traceResourceInvocation(Scheduler.getStrand(), consumerObject);
        KafkaConsumer kafkaConsumer = (KafkaConsumer) consumerObject.getNativeData(NATIVE_CONSUMER);
        try {
            Set<String> topics = kafkaConsumer.subscription();
            kafkaConsumer.unsubscribe();
            KafkaMetricsUtil.reportBulkUnsubscription(consumerObject, topics);
        } catch (KafkaException e) {
            KafkaMetricsUtil.reportConsumerError(consumerObject, KafkaObservabilityConstants.ERROR_TYPE_UNSUBSCRIBE);
            return createKafkaError("Failed to unsubscribe the consumer: " + e.getMessage(), CONSUMER_ERROR);
        }
        return null;
    }

    /**
     * Implementation for {@link ConsumerRebalanceListener} interface from connector side. We register this listener at
     * subscription.
     * <p>
     * {@inheritDoc}
     */
    static class KafkaRebalanceListener implements ConsumerRebalanceListener {

        private Strand strand;
        private Scheduler scheduler;
        private FPValue onPartitionsRevoked;
        private FPValue onPartitionsAssigned;
        private BObject consumer;

        KafkaRebalanceListener(Strand strand, Scheduler scheduler, FPValue onPartitionsRevoked,
                               FPValue onPartitionsAssigned, BObject consumer) {
            this.strand = strand;
            this.scheduler = scheduler;
            this.onPartitionsRevoked = onPartitionsRevoked;
            this.onPartitionsAssigned = onPartitionsAssigned;
            this.consumer = consumer;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            Object[] inputArgs = {null, consumer, true, getPartitionsArray(partitions), true};
            this.scheduler.schedule(inputArgs, onPartitionsRevoked.getConsumer(), strand, null, null,
                                    ON_PARTITION_REVOKED_METADATA);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            Object[] inputArgs = {null, consumer, true, getPartitionsArray(partitions), true};
            this.scheduler.schedule(inputArgs, onPartitionsAssigned.getConsumer(), strand, null, null,
                                    ON_PARTITION_ASSIGNED_METADATA);
        }

        private BArray getPartitionsArray(Collection<TopicPartition> partitions) {
            BArray topicPartitionArray = ValueCreator.createArrayValue(
                    new BArrayType(getTopicPartitionRecord().getType()));
            for (TopicPartition partition : partitions) {
                BMap<BString, Object> topicPartition = populateTopicPartitionRecord(partition.topic(),
                                                                                        partition.partition());
                topicPartitionArray.append(topicPartition);
            }
            return topicPartitionArray;
        }
    }
}
