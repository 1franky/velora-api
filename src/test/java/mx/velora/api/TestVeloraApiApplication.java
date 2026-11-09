package mx.velora.api;

import org.springframework.boot.SpringApplication;

public class TestVeloraApiApplication {

    public static void main(String[] args) {
        SpringApplication.from(VeloraApiApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
