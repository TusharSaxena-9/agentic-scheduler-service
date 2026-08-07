package com.yesmadam.schedulerservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

@SpringBootApplication
@EntityScan({"com.yesmadam.schedulerservice", "com.yesmadam.persistence"})
public class SchedulerServiceApplication {
  /**
   * Main method to start the Spring Boot application.
   *
   * @param args command line arguments
   */
  public static void main(String[] args) {
    SpringApplication.run(SchedulerServiceApplication.class, args);
  }
}
