package com.example.hms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class HelloWorldTest {

    @Test
    void testHelloWorld() {
        HelloWorld helloWorld = new HelloWorld();
        String result = helloWorld.sayHello();
        assertEquals("Hello, World!", result);
    }

    class HelloWorld {
        public String sayHello() {
            return "Hello, World!";
        }
    }
}