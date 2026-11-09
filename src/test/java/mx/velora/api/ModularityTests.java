package mx.velora.api;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Verifica que los módulos no tengan ciclos ni accedan a tipos internos de otros módulos. */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(VeloraApiApplication.class);

    @Test
    void modulesRespectBoundaries() {
        modules.verify();
    }
}
