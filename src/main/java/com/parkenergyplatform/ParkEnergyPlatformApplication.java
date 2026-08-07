package com.parkenergyplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.parkenergyplatform.mapper")
@EnableAspectJAutoProxy
@EnableScheduling
public class ParkEnergyPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(ParkEnergyPlatformApplication.class, args);
    }

}
