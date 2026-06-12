package org.example.sqlol

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class SqlolApplicationTests {

    @Test
    fun contextLoads() {
        LoomSupport.isSupported
    }

}
