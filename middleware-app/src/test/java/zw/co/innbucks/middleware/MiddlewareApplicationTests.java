package zw.co.innbucks.middleware;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import zw.co.innbucks.middleware.support.PostgresTestContainer;

@SpringBootTest
@Import(PostgresTestContainer.class)
class MiddlewareApplicationTests {

    @Test
    void contextLoads() {
    }

}
