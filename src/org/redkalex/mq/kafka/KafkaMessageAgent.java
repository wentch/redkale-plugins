/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkalex.mq.kafka;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.redkale.mq.*;
import org.redkale.util.*;

/**
 *
 * @author zhangjx
 */
public class KafkaMessageAgent extends MessageAgent {

    protected String servers;

    protected Properties consumerConfig = new Properties();

    protected Properties producerConfig = new Properties();

    protected Properties streamsConfig = new Properties();

    protected KafkaAdminClient adminClient;

    @Override
    public void init(AnyValue config) {
        this.servers = config.getAnyValue("servers").getValue("value");
        {
            AnyValue consumerAnyValue = config.getAnyValue("consumer");
            if (consumerAnyValue != null) {
                for (AnyValue val : consumerAnyValue.getAnyValues("property")) {
                    this.consumerConfig.put(val.getValue("name"), val.getValue("value"));
                }
            }
        }
        {
            AnyValue producerAnyValue = config.getAnyValue("producer");
            if (producerAnyValue != null) {
                for (AnyValue val : producerAnyValue.getAnyValues("property")) {
                    this.producerConfig.put(val.getValue("name"), val.getValue("value"));
                }
            }
        }
        {
            AnyValue streamsAnyValue = config.getAnyValue("streams");
            if (streamsAnyValue != null) {
                for (AnyValue val : streamsAnyValue.getAnyValues("property")) {
                    this.streamsConfig.put(val.getValue("name"), val.getValue("value"));
                }
            }
        }
    }

    @Override
    public void destroy(AnyValue config) {

    }

    @Override
    public boolean createTopic(String... topics) {
        if (topics == null || topics.length < 1) return true;
        try {
            List<NewTopic> newTopics = new ArrayList<>(topics.length);
            for (String t : topics) {
                newTopics.add(new NewTopic(t, Optional.empty(), Optional.empty()));
            }
            adminClient.createTopics(newTopics, new CreateTopicsOptions().timeoutMs(3000)).all().get(3, TimeUnit.SECONDS);
            return true;
        } catch (Exception ex) {
            logger.log(Level.SEVERE, "createTopic error: " + Arrays.toString(topics), ex);
            return false;
        }
    }

    @Override
    public boolean deleteTopic(String... topics) {
        if (topics == null || topics.length < 1) return true;
        try {
            adminClient.deleteTopics(Utility.ofList(topics), new DeleteTopicsOptions().timeoutMs(3000)).all().get(3, TimeUnit.SECONDS);
            return true;
        } catch (Exception ex) {
            logger.log(Level.SEVERE, "deleteTopic error: " + Arrays.toString(topics), ex);
            return false;
        }
    }

    @Override
    public List<String> queryTopic() {
        try {
            Collection<TopicListing> list = adminClient.listTopics(new ListTopicsOptions().timeoutMs(3000)).listings().get(3, TimeUnit.SECONDS);
            List<String> result = new ArrayList<>(list.size());
            for (TopicListing t : list) {
                if (!t.isInternal()) result.add(t.name());
            }
            return result;
        } catch (Exception ex) {
            logger.log(Level.SEVERE, "queryTopic error ", ex);
        }
        return null;
    }

    @Override //创建指定topic的消费处理器
    public MessageConsumer createConsumer(String topic, java.util.function.Consumer<MessageRecord> processor) {
        final Properties props = new Properties();
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"); //可以被自定义覆盖
        props.putAll(this.consumerConfig);
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "consumer-" + topic);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, MessageRecordDeserializer.class);
        return new KafkaMessageConsumer(topic, processor, props);
    }

    @Override //创建指定topic的生产处理器
    public MessageProducer createProducer() {
        final Properties props = new Properties();
        props.put(ProducerConfig.RETRIES_CONFIG, 0);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 1);
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432);
        props.put(ProducerConfig.ACKS_CONFIG, "all");//所有follower都响应了才认为消息提交成功，即"committed"
        props.putAll(this.producerConfig);
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, MessageRecordSerializer.class);
        return new KafkaMessageProducer(props);
    }

    @Override //创建指定topic的流处理器
    public MessageStreams createStreams(String topic, Function<MessageRecord, MessageRecord> processor) {
        final Properties props = new Properties();
        props.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, Runtime.getRuntime().availableProcessors());
        props.putAll(this.streamsConfig);
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "streams-" + topic);
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, MessageRecordSerde.class);
        return new KafkaMessageStreams(topic, processor, props);
    }
}
