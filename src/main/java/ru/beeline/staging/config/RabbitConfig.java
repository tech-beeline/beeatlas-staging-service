package ru.beeline.staging.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.beeline.staging.client.AuthSSOClient;

@Slf4j
@Configuration
public class RabbitConfig {

    public static final String STAGING_EVENTS_QUEUE = "staging.events";

    @Value("${app.ambassador-auth}")
    private Boolean ambassadorAuth;

    @Value("${spring.rabbitmq.host}")
    private String host;

    @Value("${spring.rabbitmq.username}")
    private String username;

    @Value("${spring.rabbitmq.password}")
    private String password;

    @Value("${spring.rabbitmq.virtual-host}")
    private String virtualHost;

    @Autowired
    private AuthSSOClient authSSOClient;

    @Bean
    public Queue stagingEventsQueue() {
        return QueueBuilder.durable(STAGING_EVENTS_QUEUE).build();
    }

    @Bean
    public CachingConnectionFactory connectionFactory() {
        return ambassadorAuth ? createConnectionFactoryWithToken() : createConnectionFactoryWithPass();
    }

    private CachingConnectionFactory createConnectionFactoryWithPass() {
        CachingConnectionFactory factory = new CachingConnectionFactory(host);
        factory.setUsername(username);
        factory.setPassword(password);
        factory.setVirtualHost(virtualHost);
        return factory;
    }

    private CachingConnectionFactory createConnectionFactoryWithToken() {
        CachingConnectionFactory factory = new CachingConnectionFactory(host);
        factory.setUsername("");
        factory.setPassword(authSSOClient.getToken());
        factory.setVirtualHost(virtualHost);

        factory.addConnectionListener(new ConnectionListener() {
            @Override
            public void onCreate(Connection connection) {
                log.info("RabbitMQ connection created — refreshing SSO token");
                factory.setPassword(authSSOClient.getToken());
            }

            @Override
            public void onClose(Connection connection) {
            }
        });
        return factory;
    }

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }
}
