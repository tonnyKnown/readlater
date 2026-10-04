package com.readlater;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@MapperScan("com.readlater.dao")
@SpringBootApplication
public class ReadlaterApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReadlaterApplication.class, args);
    }
}
