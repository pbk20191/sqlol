package org.example.demo1webfl

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class Demo1webflApplicationTests {

    @Test
    fun contextLoads() {
        LoomSupport.isSupported
    }

}
