package net.gseek.proxima

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
class ProximaApplication

fun main(args: Array<String>) {
    SpringApplication.run(ProximaApplication::class.java, *args)
}
