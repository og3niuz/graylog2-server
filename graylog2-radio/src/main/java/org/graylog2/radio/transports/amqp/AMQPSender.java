/**
 * This file is part of Graylog.
 *
 * Graylog is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graylog2.radio.transports.amqp;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.graylog2.plugin.Message;
import org.graylog2.plugin.RadioMessage;
import org.graylog2.radio.Configuration;
import org.joda.time.Duration;
import org.msgpack.MessagePack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.util.concurrent.TimeoutException;

import static com.rabbitmq.client.MessageProperties.MINIMAL_BASIC;
import static com.rabbitmq.client.MessageProperties.MINIMAL_PERSISTENT_BASIC;

public class AMQPSender {
    private static final Logger LOG = LoggerFactory.getLogger(AMQPSender.class);

    // Not threadsafe!

    private final String hostname;
    private final int port;
    private final String vHost;
    private final String username;
    private final String password;
    private final String queueName;
    private final String queueType;
    private final String exchangeName;
    private final String routingKey;
    private final boolean amqpPersistentMessagesEnabled;
    private final Duration connectTimeout;

    private Connection connection;
    private Channel channel;

    private final MessagePack pack;

    public AMQPSender(String hostname,
                      int port,
                      String vHost,
                      String username,
                      String password,
                      String queueName,
                      String queueType,
                      String exchangeName,
                      String routingKey,
                      boolean amqpPersistentMessagesEnabled,
                      Duration amqpConnectTimeout) {
        this.queueName = queueName;
        this.queueType = queueType;
        this.exchangeName = exchangeName;
        this.routingKey = routingKey;
        this.amqpPersistentMessagesEnabled = amqpPersistentMessagesEnabled;
        connectTimeout = amqpConnectTimeout;
        pack = new MessagePack();

        // Use a separate class loader for msgpack to avoid generation of duplicate class names.
        // The JavaassistTemplateBuilder used by MessagePack uses a sequence number for class naming
        // and is not thread-safe.
        pack.setClassLoader(new ClassLoader(Thread.currentThread().getContextClassLoader()) {
        });

        this.hostname = hostname;
        this.port = port;
        this.vHost = vHost;
        this.username = username;
        this.password = password;
    }

    @Inject
    public AMQPSender(Configuration configuration) {
        this(configuration.getAmqpHostname(),
                configuration.getAmqpPort(),
                configuration.getAmqpVirtualHost(),
                configuration.getAmqpUsername(),
                configuration.getAmqpPassword(),
                configuration.getAmqpQueueName(),
                configuration.getAmqpQueueType(),
                configuration.getAmqpExchangeName(),
                configuration.getAmqpRoutingKey(),
                configuration.isAmqpPersistentMessagesEnabled(),
                configuration.getAmqpConnectTimeout());
    }

    public void send(Message msg) throws IOException {
        if (!isConnected()) {
            connect();
        }

        final byte[] body = RadioMessage.serialize(pack, msg);

        channel.basicPublish(exchangeName,
                routingKey,
                true, // mandatory
                amqpPersistentMessagesEnabled ? MINIMAL_PERSISTENT_BASIC : MINIMAL_BASIC,
                body);
    }

    public void connect() throws IOException {
        final ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(hostname);
        factory.setPort(port);

        factory.setVirtualHost(vHost);

        // Authenticate?
        if (username != null && !username.isEmpty() && password != null && !password.isEmpty()) {
            factory.setUsername(username);
            factory.setPassword(password);
        }

        factory.setConnectionTimeout((int) connectTimeout.getMillis());

        try {
            connection = factory.newConnection();
        } catch (TimeoutException e) {
            throw new IOException("Timeout while opening new AMQP connection", e);
        }

        channel = connection.createChannel();

        // It's ok if the queue or exchange already exist.
        channel.queueDeclare(queueName, true, false, false, null);
        channel.exchangeDeclare(exchangeName, queueType, false, false, null);

        channel.queueBind(queueName, exchangeName, routingKey);
    }

    public boolean isConnected() {
        return connection != null
                && connection.isOpen()
                && channel != null
                && channel.isOpen();
    }

    public void close() throws IOException {
        if (channel != null && channel.isOpen()) {
            try {
                channel.close();
            } catch (TimeoutException e) {
                LOG.error("Timeout when closing AMQP channel", e);
                channel.abort();
            }
        }

        if (connection != null && connection.isOpen()) {
            connection.close();
        }
    }

}