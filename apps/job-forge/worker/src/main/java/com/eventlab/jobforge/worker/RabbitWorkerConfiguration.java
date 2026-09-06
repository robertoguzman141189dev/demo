package com.eventlab.jobforge.worker;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(WorkerProperties.class)
public class RabbitWorkerConfiguration {

    @Bean
    public SimpleRabbitListenerContainerFactory workerListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter,
            WorkerProperties properties) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(messageConverter);

        // Sin esto, el broker considera entregada la tarea en cuanto sale por el
        // socket y un worker que muera procesando se la lleva consigo.
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);

        // Arrancan parados: los pone en marcha StateRebuildRunner cuando el worker
        // ya sabe qué tareas están hechas. Consumir antes es repetir trabajo.
        factory.setAutoStartup(false);

        factory.setPrefetchCount(properties.getPrefetch());
        factory.setConcurrentConsumers(properties.getConcurrency());

        // Apagado ordenado: al recibir SIGTERM, el contenedor cancela el consumidor
        // para que el broker deje de enviarle tareas y reparta a los demás, y
        // espera hasta este plazo a que termine la que tiene entre manos. Debe ser
        // menor que el periodo de gracia del pod y mayor que la tarea más lenta; si
        // no, cada despliegue produce reprocesos.
        //
        // El plazo no está en la fábrica sino en el contenedor que esta crea, así
        // que se aplica con un customizer.
        factory.setContainerCustomizer(
                container -> container.setShutdownTimeout(properties.getShutdownTimeout().toMillis()));

        return factory;
    }
}
