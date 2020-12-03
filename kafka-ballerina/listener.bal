
// Copyright (c) 2019 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
//
// WSO2 Inc. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import ballerina/lang.'object;

# Represents a Kafka consumer endpoint.
#
# + consumerConfig - Used to store configurations related to a Kafka connection
public client class Listener {

    *'object:Listener;

    public ConsumerConfiguration consumerConfig;
    private string keyDeserializerType;
    private string valueDeserializerType;

    # Creates a new Kafka `Listener`.
    #
    # + config - Configurations related to consumer endpoint
    public isolated function init (ConsumerConfiguration config) {
        self.consumerConfig = config;
        self.keyDeserializerType = config.keyDeserializerType;
        self.valueDeserializerType = config.valueDeserializerType;
        checkpanic connect(self);

        string[]? topics = config?.topics;
        if (topics is string[]){
            checkpanic self->subscribe(topics);
        }
    }

    # Starts the registered services.
    #
    # + return - An `kafka:ConsumerError` if an error is encountered while starting the server or else nil
    public isolated function __start() returns error? {
        return 'start(self);
    }

    # Stops the kafka listener.
    #
    # + return - An `kafka:ConsumerError` if an error is encountered during the listener stopping process or else nil
    public isolated function __gracefulStop() returns error? {
        return stop(self);
    }

    # Stops the kafka listener.
    #
    # + return - An `kafka:ConsumerError` if an error is encountered during the listener stopping process or else nil
    public isolated function __immediateStop() returns error? {
        return stop(self);
    }

    # Gets called every time a service attaches itself to the listener.
    #
    # + s - The service to be attached
    # + name - Name of the service
    # + return - An `kafka:ConsumerError` if an error is encountered while attaching the service or else nil
    public isolated function __attach(service s, string? name = ()) returns error? {
        return register(self, s, name);
    }

    # Detaches a consumer service from the listener.
    #
    # + s - The service to be detached
    # + return - An `kafka:ConsumerError` if an error is encountered while detaching a service or else nil
    public isolated function __detach(service s) returns error? {
        // not implemented
    }

    # Subscribes the consumer to the provided set of topics.
    # ```ballerina
    # kafka:ConsumerError? result = consumer->subscribe(["kafka-topic-1", "kafka-topic-2"]);
    # ```
    #
    # + topics - Array of topics to be subscribed to
    # + return - A `kafka:ConsumerError` if an error is encountered or else '()'
    private isolated remote function subscribe(string[] topics) returns ConsumerError? {
        if (self.consumerConfig?.groupId is string) {
            return consumerSubscribe(self, topics);
        } else {
            panic createConsumerError("The groupId of the consumer must be set to subscribe to the topics");
        }
    }
}
