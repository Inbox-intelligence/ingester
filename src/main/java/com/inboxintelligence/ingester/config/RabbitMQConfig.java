package com.inboxintelligence.ingester.config;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

@Configuration
@RequiredArgsConstructor
public class RabbitMQConfig {

    private final EmailEventPublishProperties emailEventProperties;
    private final ClusterEventRabbitMQProperties clusterEventProperties;

    // --- Listener factory (shared by all @RabbitListener consumers) ---

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jacksonMessageConverter) {

        RetryOperationsInterceptor retryInterceptor = RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(1_000L, 2.0, 10_000L)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jacksonMessageConverter);
        factory.setPrefetchCount(5);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(3);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(retryInterceptor);
        return factory;
    }

    // --- Email-event exchange (existing — outbound only) ---

    @Bean
    public TopicExchange emailEventExchange() {
        return new TopicExchange(emailEventProperties.exchange());
    }

    // --- Cluster-event exchange + DLX ---

    @Bean
    public TopicExchange clusterEventExchange() {
        return new TopicExchange(clusterEventProperties.exchange());
    }

    @Bean
    public TopicExchange clusterEventDeadLetterExchange() {
        return new TopicExchange(clusterEventDlxName());
    }

    // --- cluster.completed queue (inbound) ---

    @Bean
    public Queue clusterCompletedQueue() {
        return QueueBuilder.durable(clusterEventProperties.completedQueue())
                .withArgument("x-dead-letter-exchange", clusterEventDlxName())
                .withArgument("x-dead-letter-routing-key", clusterEventProperties.completedRoutingKey() + ".dlq")
                .build();
    }

    @Bean
    public Binding clusterCompletedBinding(Queue clusterCompletedQueue, TopicExchange clusterEventExchange) {
        return BindingBuilder.bind(clusterCompletedQueue)
                .to(clusterEventExchange)
                .with(clusterEventProperties.completedRoutingKey());
    }

    @Bean
    public Queue clusterCompletedDeadLetterQueue() {
        return QueueBuilder.durable(clusterEventProperties.completedQueue() + ".dlq").build();
    }

    @Bean
    public Binding clusterCompletedDeadLetterBinding(
            Queue clusterCompletedDeadLetterQueue,
            TopicExchange clusterEventDeadLetterExchange) {
        return BindingBuilder.bind(clusterCompletedDeadLetterQueue)
                .to(clusterEventDeadLetterExchange)
                .with(clusterEventProperties.completedRoutingKey() + ".dlq");
    }

    // --- Message converter ---

    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    private String clusterEventDlxName() {
        return clusterEventProperties.exchange() + ".dlx";
    }
}
