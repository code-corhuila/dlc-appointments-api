package co.edu.corhuila.dlc.appointments.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(
        scanBasePackages = "co.edu.corhuila.dlc.appointments",
        // Broker connections are built explicitly from APPOINTMENTS_RABBITMQ_URI, never from localhost defaults.
        exclude = RabbitAutoConfiguration.class
)
public class AppointmentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AppointmentsApplication.class, args);
    }
}