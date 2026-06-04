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
    private final LabelEventRabbitMQProperties labelEventProperties;

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jacksonMessageConverter) {

        RetryOperationsInterceptor retryInterceptor = RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(1_000L, 2.0, 10_000L) // 1s → 2s → 4s (capped at 10s)
                .recoverer(new RejectAndDontRequeueRecoverer()) // → DLQ after 3 attempts
                .build();

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jacksonMessageConverter);
        factory.setPrefetchCount(5);
        factory.setConcurrentConsumers(2);
        factory.setMaxConcurrentConsumers(4);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(retryInterceptor);
        return factory;
    }

    // --- Email-event exchange (outbound only) ---

    @Bean
    public TopicExchange emailEventExchange() {
        return new TopicExchange(emailEventProperties.exchange());
    }

    // --- Label-event exchange + DLX ---

    @Bean
    public TopicExchange labelEventExchange() {
        return new TopicExchange(labelEventProperties.exchange());
    }

    @Bean
    public TopicExchange labelEventDeadLetterExchange() {
        return new TopicExchange(labelEventDlxName());
    }

    // --- Gmail label apply queue ---

    @Bean
    public Queue gmailLabelApplyQueue() {
        return QueueBuilder.durable(labelEventProperties.applyQueue())
                .withArgument("x-dead-letter-exchange", labelEventDlxName())
                .withArgument("x-dead-letter-routing-key", labelEventProperties.applyRoutingKey() + ".dlq")
                .build();
    }

    @Bean
    public Binding gmailLabelApplyBinding(Queue gmailLabelApplyQueue, TopicExchange labelEventExchange) {
        return BindingBuilder.bind(gmailLabelApplyQueue).to(labelEventExchange).with(labelEventProperties.applyRoutingKey());
    }

    @Bean
    public Queue gmailLabelApplyDeadLetterQueue() {
        return QueueBuilder.durable(labelEventProperties.applyQueue() + ".dlq").build();
    }

    @Bean
    public Binding gmailLabelApplyDeadLetterBinding(Queue gmailLabelApplyDeadLetterQueue, TopicExchange labelEventDeadLetterExchange) {
        return BindingBuilder.bind(gmailLabelApplyDeadLetterQueue).to(labelEventDeadLetterExchange).with(labelEventProperties.applyRoutingKey() + ".dlq");
    }

    // --- Message converter ---

    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    private String labelEventDlxName() {
        return labelEventProperties.exchange() + ".dlx";
    }
}
